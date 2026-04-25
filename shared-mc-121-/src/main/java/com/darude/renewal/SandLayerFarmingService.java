package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.DarudeDiagnostics;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import com.darude.worldgen.SandLayerGenerationConfig;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V1 sand-layer farming runtime.
 *
 * Kept separate from initial chunk/world generation logic.
 */
public final class SandLayerFarmingService {
	private static final int PLAYER_CHUNK_SCAN_RADIUS = Integer.getInteger("darude.farming.player_chunk_scan_radius", 4);
	private static final int DIAGNOSTIC_CHUNK_SCAN_RADIUS = Integer.getInteger("darude.farming.diagnostic_chunk_scan_radius", 8);
	private static final int MIN_VERTICAL_CHECKS_PER_TICK = 256;
	private static final int MAX_EMITTER_DEPTH_FROM_SURFACE = Integer.getInteger("darude.farming.max_emitter_depth_from_surface", 2);
	private static final long MAX_FARMING_WORK_NANOS = Long.getLong("darude.farming.max_work_ms", 2L) * 1_000_000L;
	private static final boolean FARMING_DISABLED = Boolean.parseBoolean(System.getProperty("darude.farming.disable", "false"));
	private static final int DEFAULT_EMITTER_MAX_Y = Integer.getInteger("darude.farming.default_emitter_max_y", 100);
	private static final float THUNDERSTORM_FARMING_MULTIPLIER = 2.5f;
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<net.minecraft.block.Block> FARMING_EMITTERS = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "farming_emitters"));
	private static boolean registered;
	private static final WeakHashMap<ServerWorld, Boolean> AMPLIFIED_WORLD_CACHE = new WeakHashMap<>();

	private SandLayerFarmingService() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}

		ServerTickEvents.END_WORLD_TICK.register(SandLayerFarmingService::onEndWorldTick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			CommandManager.literal("darude")
				.then(CommandManager.literal("debug_farming_emitters")
					.executes(context -> runDebugFarmingEmitters(context.getSource())))
				.then(CommandManager.literal("locate_farming_emitters")
					.executes(context -> runPaintEmitterMarkers(context.getSource(), Blocks.WHITE_CONCRETE.getDefaultState(), "Updated ")))
		));
		registered = true;
	}

	private static void onEndWorldTick(ServerWorld world) {
		if (FARMING_DISABLED) {
			return;
		}

		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.maxFarmingOperationsPerTick() <= 0) {
			return;
		}

		long gameTime = world.getTime();
		int randomTickSpeed = resolveRandomTickSpeed(world);
		int effectiveIntervalTicks = Math.max(1, config.farmingTickIntervalTicks() / randomTickSpeed);
		if (gameTime % effectiveIntervalTicks != 0L) {
			return;
		}

		if (!world.isRaining()) {
			return;
		}

		int farmingOperationLimit = resolveFarmingOperationLimit(world, config);

		Direction windDirection = SandstormWindService.getWindDirection(world);
		Random random = world.getRandom();
		Set<Long> scannedChunks = collectCandidateChunks(world);
		Map<Long, Boolean> biomeCache = new HashMap<>();
		Map<Long, Boolean> chunkBiomeCache = new HashMap<>();
		int[] operationsUsed = new int[]{0};
		int[] verticalChecksUsed = new int[]{0};
		int maxVerticalChecks = Math.max(MIN_VERTICAL_CHECKS_PER_TICK, farmingOperationLimit * 32);
		int emitterMaxY = resolveEmitterMaxY(world);
		long startedAtNanos = System.nanoTime();
		long deadlineNanos = startedAtNanos + MAX_FARMING_WORK_NANOS;

		for (long packedChunkPos : scannedChunks) {
			if (System.nanoTime() >= deadlineNanos) {
				break;
			}

			if (operationsUsed[0] >= farmingOperationLimit) {
				break;
			}

			int chunkX = ChunkPos.getPackedX(packedChunkPos);
			int chunkZ = ChunkPos.getPackedZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof WorldChunk worldChunk)) {
				continue;
			}

			scanChunk(world, worldChunk, config, windDirection, random, biomeCache, chunkBiomeCache, operationsUsed, verticalChecksUsed, farmingOperationLimit, maxVerticalChecks, deadlineNanos, emitterMaxY);
		}

		if (System.nanoTime() >= deadlineNanos && Boolean.getBoolean("darude.debug.hotspots")) {
			DarudeMod.LOGGER.warn("Hotspot[farming-budget] world={} exhausted {} ms budget", world.getRegistryKey().getValue(), MAX_FARMING_WORK_NANOS / 1_000_000L);
		}

		DarudeDiagnostics.logFarmingTick(
			world.getRegistryKey().getValue().toString(),
			scannedChunks.size(),
			operationsUsed[0],
			verticalChecksUsed[0],
			startedAtNanos
		);
	}

	private static Set<Long> collectCandidateChunks(ServerWorld world) {
		Set<Long> chunks = new TreeSet<>();
		for (ServerPlayerEntity player : world.getPlayers()) {
			chunks.addAll(collectCandidateChunks(player.getChunkPos()));
		}
		return chunks;
	}

	private static Set<Long> collectCandidateChunks(ChunkPos center) {
		return collectCandidateChunks(center, PLAYER_CHUNK_SCAN_RADIUS);
	}

	private static Set<Long> collectCandidateChunks(ChunkPos center, int radius) {
		Set<Long> chunks = new TreeSet<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				chunks.add(ChunkPos.toLong(center.x + dx, center.z + dz));
			}
		}
		return chunks;
	}

	private static int resolveRandomTickSpeed(ServerWorld world) {
		Object gameRules = world.getGameRules();
		int resolved = readRandomTickSpeedReflective(gameRules, "net.minecraft.world.GameRules");
		if (resolved > 0) {
			return resolved;
		}

		resolved = readRandomTickSpeedReflective(gameRules, "net.minecraft.world.level.GameRules");
		if (resolved > 0) {
			return resolved;
		}

		return 1;
	}

	private static int readRandomTickSpeedReflective(Object gameRules, String gameRulesClassName) {
		try {
			Class<?> gameRulesClass = Class.forName(gameRulesClassName);
			Field randomTickSpeedField = gameRulesClass.getField("RANDOM_TICK_SPEED");
			Object randomTickKey = randomTickSpeedField.get(null);
			Method getInt = gameRules.getClass().getMethod("getInt", randomTickKey.getClass());
			Object value = getInt.invoke(gameRules, randomTickKey);
			if (value instanceof Integer intValue) {
				return Math.max(1, intValue);
			}
		} catch (ReflectiveOperationException ignored) {
		}

		return -1;
	}

	private static int resolveEmitterMaxY(ServerWorld world) {
		if (isAmplifiedWorld(world)) {
			return world.getTopYInclusive();
		}

		return Math.min(world.getTopYInclusive(), DEFAULT_EMITTER_MAX_Y);
	}

	private static int resolveFarmingOperationLimit(ServerWorld world, SandLayerGenerationConfig.Values config) {
		int baseLimit = config.maxFarmingOperationsPerTick();
		if (!world.isThundering()) {
			return baseLimit;
		}

		return Math.max(baseLimit + 1, Math.round(baseLimit * THUNDERSTORM_FARMING_MULTIPLIER));
	}

	private static int runDebugFarmingEmitters(ServerCommandSource source) throws CommandSyntaxException {
		ServerPlayerEntity player = source.getPlayerOrThrow();
		ServerWorld world = source.getWorld();
		Set<Long> scannedChunks = collectCandidateChunks(player.getChunkPos());
		Map<Long, Boolean> biomeCache = new HashMap<>();
		EnumMap<DebugEmitterState, Integer> counts = new EnumMap<>(DebugEmitterState.class);
		int emitterMinY = Math.max(world.getBottomY(), world.getSeaLevel() + 1);
		int emitterMaxY = resolveEmitterMaxY(world);
		int updated = 0;

		for (long packedChunkPos : scannedChunks) {
			int chunkX = ChunkPos.getPackedX(packedChunkPos);
			int chunkZ = ChunkPos.getPackedZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof WorldChunk worldChunk)) {
				continue;
			}

			updated += markDebugEmittersInChunk(world, worldChunk, biomeCache, counts, emitterMinY, emitterMaxY);
		}

		String summary = buildDebugEmitterSummary(updated, counts);
		source.sendFeedback(() -> Text.literal(summary), false);
		return updated;
	}

	private static int runPaintEmitterMarkers(ServerCommandSource source, BlockState markerState, String prefix) throws CommandSyntaxException {
		ServerPlayerEntity player = source.getPlayerOrThrow();
		ServerWorld world = source.getWorld();
		Set<Long> scannedChunks = collectCandidateChunks(player.getChunkPos());
		int knownEmitterBlocksInRange = countKnownEmitterBlocks(world, scannedChunks);
		int updated = 0;

		for (long packedChunkPos : scannedChunks) {
			int chunkX = ChunkPos.getPackedX(packedChunkPos);
			int chunkZ = ChunkPos.getPackedZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof WorldChunk worldChunk)) {
				continue;
			}

			updated += paintEmitterMarkersInChunk(world, worldChunk, markerState);
		}

		String summary = prefix + updated + " emitter markers" + buildLocateFailureReason(world, player.getChunkPos(), updated, knownEmitterBlocksInRange);
		source.sendFeedback(() -> Text.literal(summary), false);
		return updated;
	}

	private static String buildLocateFailureReason(ServerWorld world, ChunkPos center, int taggedEmittersInRange, int knownEmitterBlocksInRange) {
		if (taggedEmittersInRange > 0) {
			return "";
		}

		if (knownEmitterBlocksInRange > 0) {
			return " | reason: tag failure (found " + knownEmitterBlocksInRange + " known emitter blocks in active range, but 0 tag matches)";
		}

		int nearbyKnownEmitterBlocks = countKnownEmitterBlocks(world, collectCandidateChunks(center, DIAGNOSTIC_CHUNK_SCAN_RADIUS));
		if (nearbyKnownEmitterBlocks > 0) {
			return " | reason: scan radius issue (found " + nearbyKnownEmitterBlocks + " known emitter blocks within " + DIAGNOSTIC_CHUNK_SCAN_RADIUS + " chunks)";
		}

		return " | reason: wrong block assumption (no known emitter blocks found within " + DIAGNOSTIC_CHUNK_SCAN_RADIUS + " chunks)";
	}

	private static int countKnownEmitterBlocks(ServerWorld world, Set<Long> scannedChunks) {
		int count = 0;
		for (long packedChunkPos : scannedChunks) {
			int chunkX = ChunkPos.getPackedX(packedChunkPos);
			int chunkZ = ChunkPos.getPackedZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof WorldChunk worldChunk)) {
				continue;
			}

			count += countKnownEmitterBlocksInChunk(world, worldChunk);
		}
		return count;
	}

	private static int countKnownEmitterBlocksInChunk(ServerWorld world, WorldChunk chunk) {
		int count = 0;
		ChunkPos chunkPos = chunk.getPos();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;

				for (int y = world.getBottomY(); y <= world.getTopYInclusive(); y++) {
					if (isKnownEmitterBlock(world.getBlockState(new BlockPos(x, y, z)))) {
						count++;
					}
				}
			}
		}

		return count;
	}

	private static boolean isKnownEmitterBlock(BlockState state) {
		return state.isOf(Blocks.MANGROVE_ROOTS)
			|| state.isOf(Blocks.COPPER_GRATE)
			|| state.isOf(Blocks.EXPOSED_COPPER_GRATE)
			|| state.isOf(Blocks.WEATHERED_COPPER_GRATE)
			|| state.isOf(Blocks.OXIDIZED_COPPER_GRATE)
			|| state.isOf(Blocks.WAXED_COPPER_GRATE)
			|| state.isOf(Blocks.WAXED_EXPOSED_COPPER_GRATE)
			|| state.isOf(Blocks.WAXED_WEATHERED_COPPER_GRATE)
			|| state.isOf(Blocks.WAXED_OXIDIZED_COPPER_GRATE);
	}

	private static int paintEmitterMarkersInChunk(ServerWorld world, WorldChunk chunk, BlockState markerState) {
		int updated = 0;
		ChunkPos chunkPos = chunk.getPos();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;

				for (int y = world.getBottomY(); y <= world.getTopYInclusive(); y++) {
					BlockPos emitterPos = new BlockPos(x, y, z);
					if (!world.getBlockState(emitterPos).isIn(FARMING_EMITTERS)) {
						continue;
					}

					BlockPos markerPos = emitterPos.down(2);
					if (markerPos.getY() < world.getBottomY()) {
						continue;
					}

					world.setBlockState(markerPos, markerState, 3);
					updated++;
				}
			}
		}

		return updated;
	}

	private static int markDebugEmittersInChunk(
		ServerWorld world,
		WorldChunk chunk,
		Map<Long, Boolean> biomeCache,
		EnumMap<DebugEmitterState, Integer> counts,
		int emitterMinY,
		int emitterMaxY
	) {
		int updated = 0;
		ChunkPos chunkPos = chunk.getPos();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;

				for (int y = world.getBottomY(); y <= world.getTopYInclusive(); y++) {
					BlockPos emitterPos = new BlockPos(x, y, z);
					if (!world.getBlockState(emitterPos).isIn(FARMING_EMITTERS)) {
						continue;
					}

					BlockPos markerPos = emitterPos.down(2);
					if (markerPos.getY() < world.getBottomY()) {
						continue;
					}

					DebugEmitterState state = classifyDebugEmitterState(world, emitterPos, biomeCache, emitterMinY, emitterMaxY);
					world.setBlockState(markerPos, state.concrete.getDefaultState(), 3);
					counts.merge(state, 1, Integer::sum);
					updated++;
				}
			}
		}

		return updated;
	}

	private static DebugEmitterState classifyDebugEmitterState(ServerWorld world, BlockPos emitterPos, Map<Long, Boolean> biomeCache, int emitterMinY, int emitterMaxY) {
		if (emitterPos.getY() < emitterMinY || emitterPos.getY() > emitterMaxY) {
			return DebugEmitterState.OUTSIDE_Y_RANGE;
		}

		if (!isInSandstormBiomeColumn(world, emitterPos.getX(), emitterPos.getZ(), biomeCache)) {
			return DebugEmitterState.INVALID_BIOME;
		}

		if (!areHorizontalAndAboveAir(world, emitterPos)) {
			return DebugEmitterState.NOT_SURROUNDED_BY_AIR;
		}

		if (!hasValidBelowBlock(world, emitterPos)) {
			return DebugEmitterState.INVALID_BELOW_BLOCKS;
		}

		if (!world.isSkyVisible(emitterPos.up())) {
			return DebugEmitterState.SKY_BLOCKED;
		}

		if (isQualifiedEmitter(world, emitterPos, biomeCache)) {
			return DebugEmitterState.CAN_SPAWN;
		}

		return DebugEmitterState.FALLBACK;
	}

	private static boolean hasValidBelowBlock(ServerWorld world, BlockPos pos) {
		BlockState below = world.getBlockState(pos.down());
		return below.isAir() || below.isOf(DarudeBlocks.SAND_LAYER) || below.isOf(DarudeBlocks.PYRAMID) || below.isOf(DarudeBlocks.FULL_PYRAMID);
	}

	private static String buildDebugEmitterSummary(int updated, EnumMap<DebugEmitterState, Integer> counts) {
		StringBuilder summary = new StringBuilder("Updated ").append(updated).append(" emitter markers");
		for (DebugEmitterState state : DebugEmitterState.values()) {
			int count = counts.getOrDefault(state, 0);
			if (count <= 0) {
				continue;
			}
			summary.append(" | ").append(state.label).append(": ").append(count);
		}
		return summary.toString();
	}

	private static boolean isAmplifiedWorld(ServerWorld world) {
		Boolean cached = AMPLIFIED_WORLD_CACHE.get(world);
		if (cached != null) {
			return cached;
		}

		boolean amplified = containsAmplifiedHint(world);
		AMPLIFIED_WORLD_CACHE.put(world, amplified);
		return amplified;
	}

	private static boolean containsAmplifiedHint(ServerWorld world) {
		Object chunkManager = invokeAny(world, "getChunkManager", "getChunkSource");
		if (containsAmplifiedText(chunkManager)) {
			return true;
		}

		Object chunkGenerator = invokeAny(chunkManager, "getChunkGenerator", "getGenerator");
		if (containsAmplifiedText(chunkGenerator)) {
			return true;
		}

		Object settings = invokeAny(chunkGenerator, "getSettings", "settings");
		if (containsAmplifiedText(settings)) {
			return true;
		}

		Object server = invokeAny(world, "getServer");
		Object saveData = invokeAny(server, "getSaveProperties", "getWorldData", "getSaveData");
		return containsAmplifiedText(saveData);
	}

	private static Object invokeAny(Object target, String... methodNames) {
		if (target == null) {
			return null;
		}

		for (String methodName : methodNames) {
			try {
				Method method = target.getClass().getMethod(methodName);
				return method.invoke(target);
			} catch (ReflectiveOperationException ignored) {
			}
		}

		return null;
	}

	private static boolean containsAmplifiedText(Object value) {
		if (value == null) {
			return false;
		}

		String text = value.toString().toLowerCase();
		return text.contains("amplified");
	}

	private static void scanChunk(
		ServerWorld world,
		WorldChunk chunk,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		Random random,
		Map<Long, Boolean> biomeCache,
		Map<Long, Boolean> chunkBiomeCache,
		int[] operationsUsed,
		int[] verticalChecksUsed,
		int farmingOperationLimit,
		int maxVerticalChecks,
		long deadlineNanos,
		int emitterMaxY
	) {
		ChunkPos chunkPos = chunk.getPos();
		if (!isChunkInSandstormBiome(world, chunkPos, chunkBiomeCache)) {
			return;
		}

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				if (System.nanoTime() >= deadlineNanos) {
					return;
				}

				if (operationsUsed[0] >= farmingOperationLimit) {
					return;
				}

				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;
				if (!isInSandstormBiomeColumn(world, x, z, biomeCache)) {
					continue;
				}

				int minY = Math.max(world.getBottomY(), world.getSeaLevel() + 1);
				int maxY = Math.min(world.getTopYInclusive(), emitterMaxY);
				if (maxY < minY) {
					continue;
				}

				for (int y = maxY; y >= minY; y--) {
					if (System.nanoTime() >= deadlineNanos) {
						return;
					}

					if (verticalChecksUsed[0]++ >= maxVerticalChecks) {
						return;
					}

					if (operationsUsed[0] >= farmingOperationLimit) {
						return;
					}

					BlockPos emitterPos = new BlockPos(x, y, z);
					BlockState emitterState = world.getBlockState(emitterPos);
					if (!emitterState.isIn(FARMING_EMITTERS)) {
						continue;
					}

					processEmitterAt(world, emitterPos, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, 0);
				}
			}
		}
	}

	private static boolean isChunkInSandstormBiome(ServerWorld world, ChunkPos chunkPos, Map<Long, Boolean> chunkBiomeCache) {
		long key = chunkPos.toLong();
		Boolean cached = chunkBiomeCache.get(key);
		if (cached != null) {
			return cached;
		}

		int centerX = chunkPos.getStartX() + 8;
		int centerZ = chunkPos.getStartZ() + 8;
		int sampleY = Math.max(world.getBottomY() + 1, world.getSeaLevel());
		boolean inBiome = world.getBiome(new BlockPos(centerX, sampleY, centerZ)).isIn(SANDSTORM_BIOMES);
		chunkBiomeCache.put(key, inBiome);
		return inBiome;
	}

	private static boolean processEmitterAt(
		ServerWorld world,
		BlockPos emitterPos,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		Random random,
		Map<Long, Boolean> biomeCache,
		int[] operationsUsed,
		int farmingOperationLimit,
		int depth
	) {
		if (depth > config.maxFallthroughDepth()) {
			return false;
		}

		if (operationsUsed[0] >= farmingOperationLimit) {
			return false;
		}
		operationsUsed[0]++;

		if (!isQualifiedEmitter(world, emitterPos, biomeCache)) {
			return false;
		}

		BlockPos supportPos = emitterPos.down();
		BlockState supportState = world.getBlockState(supportPos);
		boolean pyramidSupport = supportState.isOf(DarudeBlocks.PYRAMID) || supportState.isOf(DarudeBlocks.FULL_PYRAMID);

		if (!pyramidSupport) {
			if (random.nextFloat() >= config.baseUnderGrateChance()) {
				return false;
			}
			return attemptPlacementWithFallthrough(world, emitterPos.down(), config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth);
		}

		boolean generated = false;
		Direction windward = windDirection.getOpposite();
		float baseChance = supportState.isOf(DarudeBlocks.FULL_PYRAMID)
			? config.fullPyramidSideChance()
			: config.basePyramidSideChance();

		for (Direction direction : Direction.Type.HORIZONTAL) {
			float chance = baseChance;
			if (direction == windward) {
				chance *= config.windwardSideMultiplier();
			}
			chance = Math.max(0.0f, Math.min(1.0f, chance));

			if (random.nextFloat() >= chance) {
				continue;
			}

			BlockPos sideTarget = supportPos.offset(direction);
			if (attemptPlacementWithFallthrough(world, sideTarget, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth)) {
				generated = true;
			}
		}

		if (generated) {
			applyPyramidErosion(world, supportPos, supportState, config, random);
		}

		return generated;
	}

	private static boolean attemptPlacementWithFallthrough(
		ServerWorld world,
		BlockPos targetPos,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		Random random,
		Map<Long, Boolean> biomeCache,
		int[] operationsUsed,
		int farmingOperationLimit,
		int depth
	) {
		if (depth > config.maxFallthroughDepth()) {
			return false;
		}

		BlockState state = world.getBlockState(targetPos);
		if (state.isIn(FARMING_EMITTERS)) {
			return processEmitterAt(world, targetPos, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth + 1);
		}

		if (state.isAir()) {
			BlockState layerState = DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, 1);
			if (!layerState.canPlaceAt(world, targetPos)) {
				return false;
			}

			if (world.setBlockState(targetPos, layerState, 3)) {
				SandLayerAvalancheService.enqueue(world, targetPos);
				return true;
			}
			return false;
		}

		if (state.isOf(DarudeBlocks.SAND_LAYER)) {
			int layers = state.get(SandLayerBlock.LAYERS);
			BlockState next = layers >= 15
				? Blocks.SAND.getDefaultState()
				: state.with(SandLayerBlock.LAYERS, layers + 1);
			if (world.setBlockState(targetPos, next, 3)) {
				SandLayerAvalancheService.enqueue(world, targetPos);
				return true;
			}
		}

		return false;
	}

	private static boolean isQualifiedEmitter(ServerWorld world, BlockPos pos, Map<Long, Boolean> biomeCache) {
		if (!world.isSkyVisible(pos.up())) {
			return false;
		}

		if (!isInSandstormBiomeColumn(world, pos.getX(), pos.getZ(), biomeCache)) {
			return false;
		}

		if (!areHorizontalAndAboveAir(world, pos)) {
			return false;
		}

		return hasValidBelowBlock(world, pos);
	}

	private static boolean areHorizontalAndAboveAir(ServerWorld world, BlockPos pos) {
		if (!world.getBlockState(pos.up()).isAir()) {
			return false;
		}

		for (Direction direction : Direction.Type.HORIZONTAL) {
			if (!world.getBlockState(pos.offset(direction)).isAir()) {
				return false;
			}
		}

		return true;
	}

	private static boolean isInSandstormBiomeColumn(ServerWorld world, int x, int z, Map<Long, Boolean> biomeCache) {
		long key = (((long) x) << 32) ^ (z & 0xffffffffL);
		Boolean cached = biomeCache.get(key);
		if (cached != null) {
			return cached;
		}

		int sampleY = Math.max(world.getSeaLevel(), world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1);
		if (sampleY < world.getBottomY()) {
			sampleY = world.getBottomY();
		} else if (sampleY > world.getTopYInclusive()) {
			sampleY = world.getTopYInclusive();
		}

		boolean inBiome = world.getBiome(new BlockPos(x, sampleY, z)).isIn(SANDSTORM_BIOMES);
		biomeCache.put(key, inBiome);
		return inBiome;
	}

	private static void applyPyramidErosion(ServerWorld world, BlockPos supportPos, BlockState supportState, SandLayerGenerationConfig.Values config, Random random) {
		if (supportState.isOf(DarudeBlocks.FULL_PYRAMID)) {
			if (random.nextFloat() < config.fullPyramidErodeToPyramidChance()) {
				world.setBlockState(supportPos, DarudeBlocks.PYRAMID.getDefaultState(), 3);
			}
			return;
		}

		if (supportState.isOf(DarudeBlocks.PYRAMID) && random.nextFloat() < config.pyramidBreakChance()) {
			world.setBlockState(supportPos, Blocks.AIR.getDefaultState(), 3);
		}
	}

	private enum DebugEmitterState {
		INVALID_BELOW_BLOCKS("pink", Blocks.PINK_CONCRETE),
		CAN_SPAWN("lime", Blocks.LIME_CONCRETE),
		INVALID_BIOME("black", Blocks.BLACK_CONCRETE),
		NOT_SURROUNDED_BY_AIR("light_blue", Blocks.LIGHT_BLUE_CONCRETE),
		SKY_BLOCKED("cyan", Blocks.CYAN_CONCRETE),
		OUTSIDE_Y_RANGE("brown", Blocks.BROWN_CONCRETE),
		FALLBACK("red", Blocks.RED_CONCRETE);

		private final String label;
		private final net.minecraft.block.Block concrete;

		DebugEmitterState(String label, net.minecraft.block.Block concrete) {
			this.label = label;
			this.concrete = concrete;
		}
	}
}
