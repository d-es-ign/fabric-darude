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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	private static final long MAX_FARMING_WORK_NANOS = Long.getLong("darude.farming.max_work_ms", 10L) * 1_000_000L;
	private static final boolean FARMING_DISABLED = Boolean.parseBoolean(System.getProperty("darude.farming.disable", "false"));
	private static final boolean DEBUG_COMMANDS_ENABLED = Boolean.parseBoolean(System.getProperty("darude.debug.commands", "false"));
	private static final int DEFAULT_EMITTER_MAX_Y = Integer.getInteger("darude.farming.default_emitter_max_y", 100);
	private static final int MAX_CHUNK_EMITTER_CACHE_ENTRIES = Integer.getInteger("darude.farming.max_chunk_emitter_cache_entries", 4096);
	private static final int CHUNK_EMITTER_CACHE_TTL_TICKS = Integer.getInteger("darude.farming.chunk_emitter_cache_ttl_ticks", 200);
	private static final float THUNDERSTORM_FARMING_MULTIPLIER = 2.5f;
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<net.minecraft.block.Block> FARMING_EMITTERS = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "farming_emitters"));
	private static boolean registered;
	private static boolean farmingEmitterFallbackLogged;
	private static final WeakHashMap<ServerWorld, Boolean> AMPLIFIED_WORLD_CACHE = new WeakHashMap<>();
	private static final WeakHashMap<ServerWorld, Map<Long, ChunkEmitterCache>> CHUNK_EMITTER_CACHE = new WeakHashMap<>();
	private static final WeakHashMap<ServerWorld, FarmingDebugStats> LAST_FARMING_DEBUG_STATS = new WeakHashMap<>();

	private SandLayerFarmingService() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}

		ServerTickEvents.END_WORLD_TICK.register(SandLayerFarmingService::onEndWorldTick);
		if (DEBUG_COMMANDS_ENABLED) {
			CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				CommandManager.literal("darude")
					.requires(SandLayerFarmingService::canRunDebugCommands)
					.then(CommandManager.literal("debug_farming_emitters")
						.executes(context -> runDebugFarmingEmitters(context.getSource())))
					.then(CommandManager.literal("debug_farming_stats")
						.executes(context -> runDebugFarmingStats(context.getSource())))
					.then(CommandManager.literal("debug_farming_emitter_tag")
						.executes(context -> runDebugFarmingEmitterTag(context.getSource())))
					.then(CommandManager.literal("locate_farming_emitters")
						.executes(context -> runPaintEmitterMarkers(context.getSource(), Blocks.WHITE_CONCRETE.getDefaultState(), "Updated ")))
			));
		}
		registered = true;
	}

	private static boolean canRunDebugCommands(ServerCommandSource source) {
		Object entity = invokeAny(source, "getEntity");
		if (entity == null) {
			return true;
		}

		Object server = invokeAny(source, "getServer");
		Object playerManager = invokeAny(server, "getPlayerManager", "getPlayerList");
		Object gameProfile = invokeAny(entity, "getGameProfile");
		if (playerManager == null || gameProfile == null) {
			return false;
		}

		try {
			Object result = invokeMethod(playerManager, "isOperator", gameProfile);
			if (result instanceof Boolean allowed) {
				return allowed;
			}
		} catch (ReflectiveOperationException ignored) {
		}

		try {
			Object result = invokeMethod(playerManager, "isOp", gameProfile);
			if (result instanceof Boolean allowed) {
				return allowed;
			}
		} catch (ReflectiveOperationException ignored) {
		}

		return false;
	}

	public static void onBlockChanged(ServerWorld world, BlockPos pos) {
		Map<Long, ChunkEmitterCache> worldCache = CHUNK_EMITTER_CACHE.get(world);
		if (worldCache == null || worldCache.isEmpty()) {
			return;
		}

		invalidateChunkEmitterCache(worldCache, pos.getX() >> 4, pos.getZ() >> 4);
		int localX = pos.getX() & 15;
		int localZ = pos.getZ() & 15;
		if (localX == 0) {
			invalidateChunkEmitterCache(worldCache, (pos.getX() >> 4) - 1, pos.getZ() >> 4);
		} else if (localX == 15) {
			invalidateChunkEmitterCache(worldCache, (pos.getX() >> 4) + 1, pos.getZ() >> 4);
		}

		if (localZ == 0) {
			invalidateChunkEmitterCache(worldCache, pos.getX() >> 4, (pos.getZ() >> 4) - 1);
		} else if (localZ == 15) {
			invalidateChunkEmitterCache(worldCache, pos.getX() >> 4, (pos.getZ() >> 4) + 1);
		}
	}

	private static void invalidateChunkEmitterCache(Map<Long, ChunkEmitterCache> worldCache, int chunkX, int chunkZ) {
		worldCache.remove(ChunkPos.toLong(chunkX, chunkZ));
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
		FarmingDebugStats stats = new FarmingDebugStats();
		stats.scannedChunks = scannedChunks.size();
		stats.farmingOperationLimit = farmingOperationLimit;
		stats.maxVerticalChecks = maxVerticalChecks;
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

			scanChunk(world, worldChunk, config, windDirection, random, biomeCache, chunkBiomeCache, operationsUsed, verticalChecksUsed, farmingOperationLimit, maxVerticalChecks, deadlineNanos, emitterMaxY, stats);
		}

		stats.operationsUsed = operationsUsed[0];
		stats.verticalChecksUsed = verticalChecksUsed[0];
		stats.deadlineHit = System.nanoTime() >= deadlineNanos;
		stats.raining = world.isRaining();
		stats.randomTickSpeed = randomTickSpeed;
		stats.effectiveIntervalTicks = effectiveIntervalTicks;
		LAST_FARMING_DEBUG_STATS.put(world, stats);

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
			Field randomTickSpeedField = getField(gameRulesClass, "RANDOM_TICK_SPEED");
			Object randomTickKey = randomTickSpeedField.get(null);
			Object value = invokeMethod(gameRules, "getInt", randomTickKey);
			int resolved = extractPositiveInt(value);
			if (resolved > 0) {
				return resolved;
			}

			Object rule = invokeMethod(gameRules, "get", randomTickKey);
			Object ruleValue = invokeAny(rule, "get", "intValue", "value");
			resolved = extractPositiveInt(ruleValue);
			if (resolved > 0) {
				return resolved;
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

	private static int runDebugFarmingEmitterTag(ServerCommandSource source) {
		String summary = buildKnownEmitterTagSummary();
		source.sendFeedback(() -> Text.literal(summary), false);
		return 1;
	}

	private static int runDebugFarmingStats(ServerCommandSource source) throws CommandSyntaxException {
		ServerWorld world = source.getWorld();
		FarmingDebugStats stats = LAST_FARMING_DEBUG_STATS.get(world);
		String summary = stats == null
			? "No farming tick stats recorded yet for this world"
			: stats.toSummary();
		source.sendFeedback(() -> Text.literal(summary), false);
		return 1;
	}

	private static String buildKnownEmitterTagSummary() {
		BlockState[] states = new BlockState[]{
			Blocks.MANGROVE_ROOTS.getDefaultState(),
			Blocks.COPPER_GRATE.getDefaultState(),
			Blocks.EXPOSED_COPPER_GRATE.getDefaultState(),
			Blocks.WEATHERED_COPPER_GRATE.getDefaultState(),
			Blocks.OXIDIZED_COPPER_GRATE.getDefaultState(),
			Blocks.WAXED_COPPER_GRATE.getDefaultState(),
			Blocks.WAXED_EXPOSED_COPPER_GRATE.getDefaultState(),
			Blocks.WAXED_WEATHERED_COPPER_GRATE.getDefaultState(),
			Blocks.WAXED_OXIDIZED_COPPER_GRATE.getDefaultState()
		};
		String[] names = new String[]{
			"mangrove_roots",
			"copper_grate",
			"exposed_copper_grate",
			"weathered_copper_grate",
			"oxidized_copper_grate",
			"waxed_copper_grate",
			"waxed_exposed_copper_grate",
			"waxed_weathered_copper_grate",
			"waxed_oxidized_copper_grate"
		};
		StringBuilder summary = new StringBuilder("Runtime tag darude:farming_emitters: ");
		int resolved = 0;
		for (int i = 0; i < states.length; i++) {
			boolean inTag = states[i].isIn(FARMING_EMITTERS);
			if (inTag) {
				resolved++;
			}
			if (i > 0) {
				summary.append(", ");
			}
			summary.append(names[i]).append('=').append(inTag ? 'Y' : 'N');
		}
		return "resolved " + resolved + "/" + states.length + " known emitters | " + summary;
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

	private static boolean isEmitterBlock(BlockState state) {
		if (state.isIn(FARMING_EMITTERS)) {
			return true;
		}

		if (!isKnownEmitterBlock(state)) {
			return false;
		}

		logFarmingEmitterFallbackOnce();
		return true;
	}

	private static void logFarmingEmitterFallbackOnce() {
		if (farmingEmitterFallbackLogged) {
			return;
		}

		farmingEmitterFallbackLogged = true;
		DarudeMod.LOGGER.warn("Tag darude:farming_emitters resolved empty at runtime; using built-in emitter fallback list");
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
					if (!isEmitterBlock(world.getBlockState(emitterPos))) {
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
					if (!isEmitterBlock(world.getBlockState(emitterPos))) {
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

	private static Object invokeMethod(Object target, String methodName, Object argument) throws ReflectiveOperationException {
		for (Method method : target.getClass().getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
				continue;
			}

			Class<?> parameterType = method.getParameterTypes()[0];
			if (!parameterType.isInstance(argument)) {
				continue;
			}

			return method.invoke(target, argument);
		}

		for (Method method : target.getClass().getDeclaredMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
				continue;
			}

			Class<?> parameterType = method.getParameterTypes()[0];
			if (!parameterType.isInstance(argument)) {
				continue;
			}

			method.setAccessible(true);
			return method.invoke(target, argument);
		}

		throw new NoSuchMethodException(methodName);
	}

	private static Field getField(Class<?> type, String fieldName) throws ReflectiveOperationException {
		try {
			return type.getField(fieldName);
		} catch (NoSuchFieldException ignored) {
			Field field = type.getDeclaredField(fieldName);
			field.setAccessible(true);
			return field;
		}
	}

	private static int extractPositiveInt(Object value) {
		if (value instanceof Number number) {
			return Math.max(1, number.intValue());
		}

		return -1;
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
		int emitterMaxY,
		FarmingDebugStats stats
	) {
		ChunkPos chunkPos = chunk.getPos();
		if (!isChunkInSandstormBiome(world, chunkPos, chunkBiomeCache)) {
			return;
		}

		ChunkEmitterCache emitterCache = getChunkEmitterCache(world, chunk, biomeCache, emitterMaxY, operationsUsed, farmingOperationLimit, verticalChecksUsed, maxVerticalChecks, deadlineNanos);
		stats.cachedEmitters += emitterCache.qualifiedEmitterPositions.size();
		if (emitterCache.qualifiedEmitterPositions.isEmpty()) {
			return;
		}

		for (BlockPos emitterPos : emitterCache.qualifiedEmitterPositions) {
			if (System.nanoTime() >= deadlineNanos) {
				return;
			}

			if (operationsUsed[0] >= farmingOperationLimit) {
				return;
			}

			stats.emittersVisited++;

			if (!isEmitterBlock(world.getBlockState(emitterPos))) {
				continue;
			}

			processEmitterAt(world, emitterPos, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, 0, stats);
		}
	}

	private static ChunkEmitterCache getChunkEmitterCache(
		ServerWorld world,
		WorldChunk chunk,
		Map<Long, Boolean> biomeCache,
		int emitterMaxY,
		int[] operationsUsed,
		int farmingOperationLimit,
		int[] verticalChecksUsed,
		int maxVerticalChecks,
		long deadlineNanos
	) {
		Map<Long, ChunkEmitterCache> worldCache = CHUNK_EMITTER_CACHE.computeIfAbsent(world, ignored -> new HashMap<>());
		if (worldCache.size() > MAX_CHUNK_EMITTER_CACHE_ENTRIES) {
			worldCache.clear();
		}

		long chunkKey = chunk.getPos().toLong();
		ChunkEmitterCache cached = worldCache.get(chunkKey);
		if (cached != null && cached.emitterMaxY == emitterMaxY && !isChunkEmitterCacheExpired(world, cached)) {
			return cached;
		}

		ChunkEmitterCache rebuilt = buildChunkEmitterCache(world, chunk, biomeCache, emitterMaxY, operationsUsed, farmingOperationLimit, verticalChecksUsed, maxVerticalChecks, deadlineNanos);
		if (rebuilt == null) {
			return cached != null ? cached : new ChunkEmitterCache(world.getTime(), emitterMaxY, List.of());
		}

		worldCache.put(chunkKey, rebuilt);
		return rebuilt;
	}

	private static boolean isChunkEmitterCacheExpired(ServerWorld world, ChunkEmitterCache cache) {
		return world.getTime() - cache.builtAtTick > CHUNK_EMITTER_CACHE_TTL_TICKS;
	}

	private static ChunkEmitterCache buildChunkEmitterCache(
		ServerWorld world,
		WorldChunk chunk,
		Map<Long, Boolean> biomeCache,
		int emitterMaxY,
		int[] operationsUsed,
		int farmingOperationLimit,
		int[] verticalChecksUsed,
		int maxVerticalChecks,
		long deadlineNanos
	) {
		ChunkPos chunkPos = chunk.getPos();
		int minY = Math.max(world.getBottomY(), world.getSeaLevel() + 1);
		int maxY = Math.min(world.getTopYInclusive(), emitterMaxY);
		if (maxY < minY) {
			return new ChunkEmitterCache(world.getTime(), emitterMaxY, List.of());
		}

		List<BlockPos> qualifiedEmitterPositions = new ArrayList<>();
		int remainingEmitterBudget = Math.max(0, farmingOperationLimit - operationsUsed[0]);
		if (remainingEmitterBudget == 0) {
			return new ChunkEmitterCache(world.getTime(), emitterMaxY, List.of());
		}
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				if (System.nanoTime() >= deadlineNanos) {
					return null;
				}

				if (operationsUsed[0] >= farmingOperationLimit) {
					return null;
				}

				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;
				if (!isInSandstormBiomeColumn(world, x, z, biomeCache)) {
					continue;
				}

				int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
				int columnMaxY = Math.min(maxY, surfaceY);
				int columnMinY = Math.max(minY, surfaceY - MAX_EMITTER_DEPTH_FROM_SURFACE);
				if (columnMaxY < columnMinY) {
					continue;
				}

				for (int y = columnMaxY; y >= columnMinY; y--) {
					if (System.nanoTime() >= deadlineNanos) {
						return null;
					}

					if (operationsUsed[0] >= farmingOperationLimit) {
						return null;
					}

					if (verticalChecksUsed[0]++ >= maxVerticalChecks) {
						return null;
					}

					BlockPos emitterPos = new BlockPos(x, y, z);
					if (!isEmitterBlock(world.getBlockState(emitterPos))) {
						continue;
					}

					if (isQualifiedEmitter(world, emitterPos, biomeCache)) {
						qualifiedEmitterPositions.add(emitterPos);
						if (qualifiedEmitterPositions.size() >= remainingEmitterBudget) {
							return new ChunkEmitterCache(world.getTime(), emitterMaxY, qualifiedEmitterPositions);
						}
					}
				}
			}
		}

		return new ChunkEmitterCache(world.getTime(), emitterMaxY, qualifiedEmitterPositions);
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
		int depth,
		FarmingDebugStats stats
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
		stats.qualifiedEmitters++;

		BlockPos supportPos = emitterPos.down();
		BlockState supportState = world.getBlockState(supportPos);
		boolean pyramidSupport = supportState.isOf(DarudeBlocks.PYRAMID) || supportState.isOf(DarudeBlocks.FULL_PYRAMID);

		if (!pyramidSupport) {
			if (random.nextFloat() >= config.baseUnderGrateChance()) {
				return false;
			}
			stats.underGrateRollPasses++;
			return attemptPlacementWithFallthrough(world, emitterPos.down(), config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth, stats);
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
			if (attemptPlacementWithFallthrough(world, sideTarget, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth, stats)) {
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
		int depth,
		FarmingDebugStats stats
	) {
		if (depth > config.maxFallthroughDepth()) {
			return false;
		}

		BlockState state = world.getBlockState(targetPos);
		if (isEmitterBlock(state)) {
			return processEmitterAt(world, targetPos, config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth + 1, stats);
		}

		stats.placementAttempts++;

		if (state.isAir()) {
			BlockState layerState = DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, 1);
			if (!layerState.canPlaceAt(world, targetPos)) {
				return false;
			}

			if (world.setBlockState(targetPos, layerState, 3)) {
				stats.successfulPlacements++;
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
				stats.successfulPlacements++;
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

	private record ChunkEmitterCache(long builtAtTick, int emitterMaxY, List<BlockPos> qualifiedEmitterPositions) {
	}

	private static final class FarmingDebugStats {
		private int scannedChunks;
		private int cachedEmitters;
		private int emittersVisited;
		private int qualifiedEmitters;
		private int underGrateRollPasses;
		private int placementAttempts;
		private int successfulPlacements;
		private int operationsUsed;
		private int verticalChecksUsed;
		private int farmingOperationLimit;
		private int maxVerticalChecks;
		private int randomTickSpeed;
		private int effectiveIntervalTicks;
		private boolean deadlineHit;
		private boolean raining;

		private String toSummary() {
			return "chunks=" + scannedChunks
				+ " cached_emitters=" + cachedEmitters
				+ " visited=" + emittersVisited
				+ " qualified=" + qualifiedEmitters
				+ " grate_roll_passes=" + underGrateRollPasses
				+ " placement_attempts=" + placementAttempts
				+ " successes=" + successfulPlacements
				+ " ops=" + operationsUsed + "/" + farmingOperationLimit
				+ " vertical_checks=" + verticalChecksUsed + "/" + maxVerticalChecks
				+ " random_tick_speed=" + randomTickSpeed
				+ " interval=" + effectiveIntervalTicks
				+ " raining=" + raining
				+ " deadline_hit=" + deadlineHit;
		}
	}
}
