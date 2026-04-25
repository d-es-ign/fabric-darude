package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.DarudeDiagnostics;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import com.darude.worldgen.SandLayerGenerationConfig;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.lang.reflect.Method;

/**
 * V1 sand-layer farming runtime.
 *
 * Kept separate from initial chunk/world generation logic.
 */
public final class SandLayerFarmingService {
	private static final int PLAYER_CHUNK_SCAN_RADIUS = Integer.getInteger("darude.farming.player_chunk_scan_radius", 4);
	private static final int MIN_VERTICAL_CHECKS_PER_TICK = 256;
	private static final int MAX_EMITTER_DEPTH_FROM_SURFACE = Integer.getInteger("darude.farming.max_emitter_depth_from_surface", 2);
	private static final long MAX_FARMING_WORK_NANOS = Long.getLong("darude.farming.max_work_ms", 2L) * 1_000_000L;
	private static final boolean FARMING_DISABLED = Boolean.parseBoolean(System.getProperty("darude.farming.disable", "false"));
	private static final int DEFAULT_EMITTER_MAX_Y = Integer.getInteger("darude.farming.default_emitter_max_y", 100);
	private static final float THUNDERSTORM_FARMING_MULTIPLIER = 2.5f;
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<Block> FARMING_EMITTERS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "farming_emitters"));
	private static boolean registered;
	private static final WeakHashMap<ServerLevel, Boolean> AMPLIFIED_WORLD_CACHE = new WeakHashMap<>();

	private SandLayerFarmingService() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerLevel world : server.getAllLevels()) {
				onEndWorldTick(world);
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("darude")
				.then(Commands.literal("debug_farming_emitters")
					.executes(context -> runDebugFarmingEmitters(context.getSource())))
				.then(Commands.literal("locate_farming_emitters")
					.executes(context -> runPaintEmitterMarkers(context.getSource(), Blocks.WHITE_CONCRETE.defaultBlockState(), "Updated ")))
		));
		registered = true;
	}

	private static void onEndWorldTick(ServerLevel world) {
		if (FARMING_DISABLED) {
			return;
		}

		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.maxFarmingOperationsPerTick() <= 0) {
			return;
		}

		long gameTime = world.getGameTime();
		int randomTickSpeed = Math.max(1, world.getGameRules().get(GameRules.RANDOM_TICK_SPEED));
		int effectiveIntervalTicks = Math.max(1, config.farmingTickIntervalTicks() / randomTickSpeed);
		if (gameTime % effectiveIntervalTicks != 0L) {
			return;
		}

		if (!world.isRaining()) {
			return;
		}

		int farmingOperationLimit = resolveFarmingOperationLimit(world, config);

		Direction windDirection = SandstormWindService.getWindDirection(world);
		RandomSource random = world.getRandom();
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

			int chunkX = ChunkPos.getX(packedChunkPos);
			int chunkZ = ChunkPos.getZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof LevelChunk levelChunk)) {
				continue;
			}

			scanChunk(world, levelChunk, config, windDirection, random, biomeCache, chunkBiomeCache, operationsUsed, verticalChecksUsed, farmingOperationLimit, maxVerticalChecks, deadlineNanos, emitterMaxY);
		}

		if (System.nanoTime() >= deadlineNanos && Boolean.getBoolean("darude.debug.hotspots")) {
			DarudeMod.LOGGER.warn("Hotspot[farming-budget] world={} exhausted {} ms budget", world.dimension(), MAX_FARMING_WORK_NANOS / 1_000_000L);
		}

		DarudeDiagnostics.logFarmingTick(
			world.dimension().toString(),
			scannedChunks.size(),
			operationsUsed[0],
			verticalChecksUsed[0],
			startedAtNanos
		);
	}

	private static Set<Long> collectCandidateChunks(ServerLevel world) {
		Set<Long> chunks = new TreeSet<>();
		for (ServerPlayer player : world.players()) {
			chunks.addAll(collectCandidateChunks(player.chunkPosition()));
		}
		return chunks;
	}

	private static Set<Long> collectCandidateChunks(ChunkPos center) {
		Set<Long> chunks = new TreeSet<>();
		for (int dz = -PLAYER_CHUNK_SCAN_RADIUS; dz <= PLAYER_CHUNK_SCAN_RADIUS; dz++) {
			for (int dx = -PLAYER_CHUNK_SCAN_RADIUS; dx <= PLAYER_CHUNK_SCAN_RADIUS; dx++) {
				chunks.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
			}
		}
		return chunks;
	}

	private static int resolveEmitterMaxY(ServerLevel world) {
		if (isAmplifiedWorld(world)) {
			return world.getMaxY() - 1;
		}

		return Math.min(world.getMaxY() - 1, DEFAULT_EMITTER_MAX_Y);
	}

	private static int resolveFarmingOperationLimit(ServerLevel world, SandLayerGenerationConfig.Values config) {
		int baseLimit = config.maxFarmingOperationsPerTick();
		if (!world.isThundering()) {
			return baseLimit;
		}

		return Math.max(baseLimit + 1, Math.round(baseLimit * THUNDERSTORM_FARMING_MULTIPLIER));
	}

	private static int runDebugFarmingEmitters(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel world = (ServerLevel) player.level();
		Set<Long> scannedChunks = collectCandidateChunks(player.chunkPosition());
		Map<Long, Boolean> biomeCache = new HashMap<>();
		EnumMap<DebugEmitterState, Integer> counts = new EnumMap<>(DebugEmitterState.class);
		int emitterMinY = Math.max(world.getMinY(), world.getSeaLevel() + 1);
		int emitterMaxY = resolveEmitterMaxY(world);
		int updated = 0;

		for (long packedChunkPos : scannedChunks) {
			int chunkX = ChunkPos.getX(packedChunkPos);
			int chunkZ = ChunkPos.getZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof LevelChunk levelChunk)) {
				continue;
			}

			updated += markDebugEmittersInChunk(world, levelChunk, biomeCache, counts, emitterMinY, emitterMaxY);
		}

		String summary = buildDebugEmitterSummary(updated, counts);
		source.sendSuccess(() -> Component.literal(summary), false);
		return updated;
	}

	private static int runPaintEmitterMarkers(CommandSourceStack source, BlockState markerState, String prefix) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel world = (ServerLevel) player.level();
		Set<Long> scannedChunks = collectCandidateChunks(player.chunkPosition());
		int updated = 0;

		for (long packedChunkPos : scannedChunks) {
			int chunkX = ChunkPos.getX(packedChunkPos);
			int chunkZ = ChunkPos.getZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof LevelChunk levelChunk)) {
				continue;
			}

			updated += paintEmitterMarkersInChunk(world, levelChunk, markerState);
		}

		int markerCount = updated;
		source.sendSuccess(() -> Component.literal(prefix + markerCount + " emitter markers"), false);
		return updated;
	}

	private static int paintEmitterMarkersInChunk(ServerLevel world, LevelChunk chunk, BlockState markerState) {
		int updated = 0;
		ChunkPos chunkPos = chunk.getPos();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;

				for (int y = world.getMinY(); y < world.getMaxY(); y++) {
					BlockPos emitterPos = new BlockPos(x, y, z);
					if (!world.getBlockState(emitterPos).is(FARMING_EMITTERS)) {
						continue;
					}

					BlockPos markerPos = emitterPos.below(2);
					if (markerPos.getY() < world.getMinY()) {
						continue;
					}

					world.setBlock(markerPos, markerState, 3);
					updated++;
				}
			}
		}

		return updated;
	}

	private static int markDebugEmittersInChunk(
		ServerLevel world,
		LevelChunk chunk,
		Map<Long, Boolean> biomeCache,
		EnumMap<DebugEmitterState, Integer> counts,
		int emitterMinY,
		int emitterMaxY
	) {
		int updated = 0;
		ChunkPos chunkPos = chunk.getPos();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;

				for (int y = world.getMinY(); y < world.getMaxY(); y++) {
					BlockPos emitterPos = new BlockPos(x, y, z);
					if (!world.getBlockState(emitterPos).is(FARMING_EMITTERS)) {
						continue;
					}

					BlockPos markerPos = emitterPos.below(2);
					if (markerPos.getY() < world.getMinY()) {
						continue;
					}

					DebugEmitterState state = classifyDebugEmitterState(world, emitterPos, biomeCache, emitterMinY, emitterMaxY);
					world.setBlock(markerPos, state.concrete.defaultBlockState(), 3);
					counts.merge(state, 1, Integer::sum);
					updated++;
				}
			}
		}

		return updated;
	}

	private static DebugEmitterState classifyDebugEmitterState(ServerLevel world, BlockPos emitterPos, Map<Long, Boolean> biomeCache, int emitterMinY, int emitterMaxY) {
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

		if (!world.canSeeSkyFromBelowWater(emitterPos.above())) {
			return DebugEmitterState.SKY_BLOCKED;
		}

		if (isQualifiedEmitter(world, emitterPos, biomeCache)) {
			return DebugEmitterState.CAN_SPAWN;
		}

		return DebugEmitterState.FALLBACK;
	}

	private static boolean hasValidBelowBlock(ServerLevel world, BlockPos pos) {
		BlockState below = world.getBlockState(pos.below());
		return below.isAir() || below.is(DarudeBlocks.SAND_LAYER) || below.is(DarudeBlocks.PYRAMID) || below.is(DarudeBlocks.FULL_PYRAMID);
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

	private static boolean isAmplifiedWorld(ServerLevel world) {
		Boolean cached = AMPLIFIED_WORLD_CACHE.get(world);
		if (cached != null) {
			return cached;
		}

		boolean amplified = containsAmplifiedHint(world);
		AMPLIFIED_WORLD_CACHE.put(world, amplified);
		return amplified;
	}

	private static boolean containsAmplifiedHint(ServerLevel world) {
		Object chunkSource = invokeAny(world, "getChunkSource", "getChunkManager");
		if (containsAmplifiedText(chunkSource)) {
			return true;
		}

		Object generator = invokeAny(chunkSource, "getGenerator", "getChunkGenerator");
		if (containsAmplifiedText(generator)) {
			return true;
		}

		Object settings = invokeAny(generator, "getSettings", "settings");
		if (containsAmplifiedText(settings)) {
			return true;
		}

		Object server = invokeAny(world, "getServer");
		Object worldData = invokeAny(server, "getWorldData", "getSaveData");
		return containsAmplifiedText(worldData);
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
		ServerLevel world,
		LevelChunk chunk,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		RandomSource random,
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

				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;
				if (!isInSandstormBiomeColumn(world, x, z, biomeCache)) {
					continue;
				}

				int maxBuildY = world.getMaxY() - 1;
				int minY = Math.max(world.getMinY(), world.getSeaLevel() + 1);
				int maxY = Math.min(maxBuildY, emitterMaxY);
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
					if (!emitterState.is(FARMING_EMITTERS)) {
						continue;
					}

					processEmitterAt(world, emitterPos, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, 0);
				}
			}
		}
	}

	private static boolean isChunkInSandstormBiome(ServerLevel world, ChunkPos chunkPos, Map<Long, Boolean> chunkBiomeCache) {
		long key = ChunkPos.pack(chunkPos.x(), chunkPos.z());
		Boolean cached = chunkBiomeCache.get(key);
		if (cached != null) {
			return cached;
		}

		int centerX = chunkPos.getMinBlockX() + 8;
		int centerZ = chunkPos.getMinBlockZ() + 8;
		int sampleY = Math.max(world.getMinY() + 1, world.getSeaLevel());
		boolean inBiome = world.getBiome(new BlockPos(centerX, sampleY, centerZ)).is(SANDSTORM_BIOMES);
		chunkBiomeCache.put(key, inBiome);
		return inBiome;
	}

	private static boolean processEmitterAt(
		ServerLevel world,
		BlockPos emitterPos,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		RandomSource random,
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

		BlockPos supportPos = emitterPos.below();
		BlockState supportState = world.getBlockState(supportPos);
		boolean pyramidSupport = supportState.is(DarudeBlocks.PYRAMID) || supportState.is(DarudeBlocks.FULL_PYRAMID);

		if (!pyramidSupport) {
			if (random.nextFloat() >= config.baseUnderGrateChance()) {
				return false;
			}
			return attemptPlacementWithFallthrough(world, emitterPos.below(), config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth);
		}

		boolean generated = false;
		Direction windward = windDirection.getOpposite();
		float baseChance = supportState.is(DarudeBlocks.FULL_PYRAMID)
			? config.fullPyramidSideChance()
			: config.basePyramidSideChance();

		for (Direction direction : Direction.Plane.HORIZONTAL) {
			float chance = baseChance;
			if (direction == windward) {
				chance *= config.windwardSideMultiplier();
			}
			chance = Math.max(0.0f, Math.min(1.0f, chance));

			if (random.nextFloat() >= chance) {
				continue;
			}

			BlockPos sideTarget = supportPos.relative(direction);
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
		ServerLevel world,
		BlockPos targetPos,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		RandomSource random,
		Map<Long, Boolean> biomeCache,
		int[] operationsUsed,
		int farmingOperationLimit,
		int depth
	) {
		if (depth > config.maxFallthroughDepth()) {
			return false;
		}

		BlockState state = world.getBlockState(targetPos);
		if (state.is(FARMING_EMITTERS)) {
			return processEmitterAt(world, targetPos, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth + 1);
		}

		if (state.isAir()) {
			BlockState layerState = DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, 1);
			if (!layerState.canSurvive(world, targetPos)) {
				return false;
			}

			if (world.setBlock(targetPos, layerState, 3)) {
				SandLayerAvalancheService.enqueue(world, targetPos);
				return true;
			}
			return false;
		}

		if (state.is(DarudeBlocks.SAND_LAYER)) {
			int layers = state.getValue(SandLayerBlock.LAYERS);
			BlockState next = layers >= 15
				? Blocks.SAND.defaultBlockState()
				: state.setValue(SandLayerBlock.LAYERS, layers + 1);
			if (world.setBlock(targetPos, next, 3)) {
				SandLayerAvalancheService.enqueue(world, targetPos);
				return true;
			}
		}

		return false;
	}

	private static boolean isQualifiedEmitter(ServerLevel world, BlockPos pos, Map<Long, Boolean> biomeCache) {
		if (!world.canSeeSkyFromBelowWater(pos.above())) {
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

	private static boolean areHorizontalAndAboveAir(ServerLevel world, BlockPos pos) {
		if (!world.getBlockState(pos.above()).isAir()) {
			return false;
		}

		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (!world.getBlockState(pos.relative(direction)).isAir()) {
				return false;
			}
		}

		return true;
	}

	private static boolean isInSandstormBiomeColumn(ServerLevel world, int x, int z, Map<Long, Boolean> biomeCache) {
		long key = (((long) x) << 32) ^ (z & 0xffffffffL);
		Boolean cached = biomeCache.get(key);
		if (cached != null) {
			return cached;
		}

		int sampleY = Math.max(world.getSeaLevel(), world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
		if (sampleY < world.getMinY()) {
			sampleY = world.getMinY();
		} else if (sampleY > world.getMaxY() - 1) {
			sampleY = world.getMaxY() - 1;
		}

		boolean inBiome = world.getBiome(new BlockPos(x, sampleY, z)).is(SANDSTORM_BIOMES);
		biomeCache.put(key, inBiome);
		return inBiome;
	}

	private static void applyPyramidErosion(ServerLevel world, BlockPos supportPos, BlockState supportState, SandLayerGenerationConfig.Values config, RandomSource random) {
		if (supportState.is(DarudeBlocks.FULL_PYRAMID)) {
			if (random.nextFloat() < config.fullPyramidErodeToPyramidChance()) {
				world.setBlock(supportPos, DarudeBlocks.PYRAMID.defaultBlockState(), 3);
			}
			return;
		}

		if (supportState.is(DarudeBlocks.PYRAMID) && random.nextFloat() < config.pyramidBreakChance()) {
			world.setBlock(supportPos, Blocks.AIR.defaultBlockState(), 3);
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
		private final Block concrete;

		DebugEmitterState(String label, Block concrete) {
			this.label = label;
			this.concrete = concrete;
		}
	}
}
