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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	private static final int DIAGNOSTIC_CHUNK_SCAN_RADIUS = Integer.getInteger("darude.farming.diagnostic_chunk_scan_radius", 8);
	private static final int MIN_VERTICAL_CHECKS_PER_TICK = 256;
	private static final int MAX_FARMING_OPERATIONS_PER_TICK = 64;
	private static final int MAX_EMITTER_DEPTH_FROM_SURFACE = Integer.getInteger("darude.farming.max_emitter_depth_from_surface", 2);
	private static final long MAX_FARMING_WORK_NANOS = Long.getLong("darude.farming.max_work_ms", 10L) * 1_000_000L;
	private static final boolean DEBUG_COMMANDS_ENABLED = Boolean.parseBoolean(System.getProperty("darude.debug.commands", "false"));
	private static final int DEFAULT_EMITTER_MAX_Y = Integer.getInteger("darude.farming.default_emitter_max_y", 100);
	private static final int MAX_CHUNK_EMITTER_CACHE_ENTRIES = Integer.getInteger("darude.farming.max_chunk_emitter_cache_entries", 4096);
	private static final int CHUNK_EMITTER_CACHE_TTL_TICKS = Integer.getInteger("darude.farming.chunk_emitter_cache_ttl_ticks", 200);
	private static final float THUNDERSTORM_FARMING_MULTIPLIER = 2.5f;
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<Block> FARMING_EMITTERS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "farming_emitters"));
	private static boolean registered;
	private static boolean farmingEmitterFallbackLogged;
	private static final WeakHashMap<ServerLevel, Boolean> AMPLIFIED_WORLD_CACHE = new WeakHashMap<>();
	private static final WeakHashMap<ServerLevel, Map<Long, ChunkEmitterCache>> CHUNK_EMITTER_CACHE = new WeakHashMap<>();
	private static final WeakHashMap<ServerLevel, FarmingDebugStats> LAST_FARMING_DEBUG_STATS = new WeakHashMap<>();

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
		if (DEBUG_COMMANDS_ENABLED) {
			CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("darude")
					.requires(SandLayerFarmingService::canRunDebugCommands)
					.then(Commands.literal("debug_farming_emitters")
						.executes(context -> runDebugFarmingEmitters(context.getSource())))
					.then(Commands.literal("debug_farming_stats")
						.executes(context -> runDebugFarmingStats(context.getSource())))
					.then(Commands.literal("debug_farming_emitter_tag")
						.executes(context -> runDebugFarmingEmitterTag(context.getSource())))
					.then(Commands.literal("locate_farming_emitters")
						.executes(context -> runPaintEmitterMarkers(context.getSource(), Blocks.WHITE_CONCRETE.defaultBlockState(), "Updated ")))
			));
		}
		registered = true;
	}

	private static boolean canRunDebugCommands(CommandSourceStack source) {
		Object entity = invokeAny(source, "getEntity");
		if (entity == null) {
			Boolean allowed = invokeIntPermissionCheck(source, 4, "hasPermission", "hasPermissionLevel");
			return Boolean.TRUE.equals(allowed);
		}

		Object server = invokeAny(source, "getServer");
		Object playerList = invokeAny(server, "getPlayerList", "getPlayerManager");
		Object gameProfile = invokeAny(entity, "getGameProfile");
		if (playerList == null || gameProfile == null) {
			return false;
		}

		try {
			Object result = invokeMethod(playerList, "isOp", gameProfile);
			if (result instanceof Boolean allowed) {
				return allowed;
			}
		} catch (ReflectiveOperationException ignored) {
		}

		try {
			Object result = invokeMethod(playerList, "isOperator", gameProfile);
			if (result instanceof Boolean allowed) {
				return allowed;
			}
		} catch (ReflectiveOperationException ignored) {
		}

		return false;
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

		throw new NoSuchMethodException(methodName);
	}

	private static Boolean invokeIntPermissionCheck(Object target, int value, String... methodNames) {
		for (String methodName : methodNames) {
			for (Method method : target.getClass().getMethods()) {
				if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
					continue;
				}

				Class<?> parameterType = method.getParameterTypes()[0];
				if (parameterType != int.class && parameterType != Integer.class) {
					continue;
				}

				try {
					Object result = method.invoke(target, value);
					if (result instanceof Boolean bool) {
						return bool;
					}
				} catch (ReflectiveOperationException ignored) {
				}
			}
		}

		return null;
	}

	public static void onBlockChanged(ServerLevel world, BlockPos pos) {
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
		worldCache.remove(ChunkPos.pack(chunkX, chunkZ));
	}

	public static boolean shouldInvalidateEmitterCache(ServerLevel world, BlockPos pos, BlockState previousState, BlockState newState) {
		if (previousState == null || previousState.equals(newState)) {
			return false;
		}

		if (isEmitterBlock(previousState) || isEmitterBlock(newState)) {
			return true;
		}

		int minRelevantY = Math.max(world.getMinY(), world.getSeaLevel()) - 1;
		int maxRelevantY = resolveEmitterMaxY(world) + 1;
		return pos.getY() >= minRelevantY && pos.getY() <= maxRelevantY;
	}

	private static void onEndWorldTick(ServerLevel world) {
		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		long gameTime = world.getGameTime();
		int randomTickSpeed = Math.max(1, world.getGameRules().get(GameRules.RANDOM_TICK_SPEED));
		int effectiveIntervalTicks = Math.max(1, config.farmingTickIntervalTicks() / randomTickSpeed);
		if (gameTime % effectiveIntervalTicks != 0L) {
			return;
		}

		if (!world.isRaining()) {
			return;
		}

		int farmingOperationLimit = resolveFarmingOperationLimit(world);

		Direction windDirection = SandstormWindService.getWindDirection(world);
		RandomSource random = world.getRandom();
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

			int chunkX = ChunkPos.getX(packedChunkPos);
			int chunkZ = ChunkPos.getZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof LevelChunk levelChunk)) {
				continue;
			}

			scanChunk(world, levelChunk, config, windDirection, random, biomeCache, chunkBiomeCache, operationsUsed, verticalChecksUsed, farmingOperationLimit, maxVerticalChecks, deadlineNanos, emitterMaxY, stats);
		}

		stats.operationsUsed = operationsUsed[0];
		stats.verticalChecksUsed = verticalChecksUsed[0];
		stats.deadlineHit = System.nanoTime() >= deadlineNanos;
		stats.raining = world.isRaining();
		stats.randomTickSpeed = randomTickSpeed;
		stats.effectiveIntervalTicks = effectiveIntervalTicks;
		LAST_FARMING_DEBUG_STATS.put(world, stats);

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
		return collectCandidateChunks(center, PLAYER_CHUNK_SCAN_RADIUS);
	}

	private static Set<Long> collectCandidateChunks(ChunkPos center, int radius) {
		Set<Long> chunks = new TreeSet<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
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

	private static int resolveFarmingOperationLimit(ServerLevel world) {
		int baseLimit = MAX_FARMING_OPERATIONS_PER_TICK;
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
		int knownEmitterBlocksInRange = countKnownEmitterBlocks(world, scannedChunks);
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

		String summary = prefix + updated + " emitter markers" + buildLocateFailureReason(world, player.chunkPosition(), updated, knownEmitterBlocksInRange);
		source.sendSuccess(() -> Component.literal(summary), false);
		return updated;
	}

	private static int runDebugFarmingEmitterTag(CommandSourceStack source) {
		String summary = buildKnownEmitterTagSummary();
		source.sendSuccess(() -> Component.literal(summary), false);
		return 1;
	}

	private static int runDebugFarmingStats(CommandSourceStack source) {
		ServerLevel world = source.getLevel();
		FarmingDebugStats stats = LAST_FARMING_DEBUG_STATS.get(world);
		String summary = stats == null
			? "No farming tick stats recorded yet for this world"
			: stats.toSummary();
		source.sendSuccess(() -> Component.literal(summary), false);
		return 1;
	}

	private static String buildKnownEmitterTagSummary() {
		BlockState[] states = new BlockState[]{
			Blocks.MANGROVE_ROOTS.defaultBlockState(),
			Blocks.COPPER_GRATE.defaultBlockState(),
			Blocks.EXPOSED_COPPER_GRATE.defaultBlockState(),
			Blocks.WEATHERED_COPPER_GRATE.defaultBlockState(),
			Blocks.OXIDIZED_COPPER_GRATE.defaultBlockState(),
			Blocks.WAXED_COPPER_GRATE.defaultBlockState(),
			Blocks.WAXED_EXPOSED_COPPER_GRATE.defaultBlockState(),
			Blocks.WAXED_WEATHERED_COPPER_GRATE.defaultBlockState(),
			Blocks.WAXED_OXIDIZED_COPPER_GRATE.defaultBlockState()
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
			boolean inTag = states[i].is(FARMING_EMITTERS);
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

	private static String buildLocateFailureReason(ServerLevel world, ChunkPos center, int taggedEmittersInRange, int knownEmitterBlocksInRange) {
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

	private static int countKnownEmitterBlocks(ServerLevel world, Set<Long> scannedChunks) {
		int count = 0;
		for (long packedChunkPos : scannedChunks) {
			int chunkX = ChunkPos.getX(packedChunkPos);
			int chunkZ = ChunkPos.getZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof LevelChunk levelChunk)) {
				continue;
			}

			count += countKnownEmitterBlocksInChunk(world, levelChunk);
		}
		return count;
	}

	private static int countKnownEmitterBlocksInChunk(ServerLevel world, LevelChunk chunk) {
		int count = 0;
		ChunkPos chunkPos = chunk.getPos();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;

				for (int y = world.getMinY(); y < world.getMaxY(); y++) {
					if (isKnownEmitterBlock(world.getBlockState(new BlockPos(x, y, z)))) {
						count++;
					}
				}
			}
		}

		return count;
	}

	private static boolean isKnownEmitterBlock(BlockState state) {
		return state.is(Blocks.MANGROVE_ROOTS)
			|| state.is(Blocks.COPPER_GRATE)
			|| state.is(Blocks.EXPOSED_COPPER_GRATE)
			|| state.is(Blocks.WEATHERED_COPPER_GRATE)
			|| state.is(Blocks.OXIDIZED_COPPER_GRATE)
			|| state.is(Blocks.WAXED_COPPER_GRATE)
			|| state.is(Blocks.WAXED_EXPOSED_COPPER_GRATE)
			|| state.is(Blocks.WAXED_WEATHERED_COPPER_GRATE)
			|| state.is(Blocks.WAXED_OXIDIZED_COPPER_GRATE);
	}

	private static boolean isEmitterBlock(BlockState state) {
		if (state.is(FARMING_EMITTERS)) {
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

	private static int paintEmitterMarkersInChunk(ServerLevel world, LevelChunk chunk, BlockState markerState) {
		int updated = 0;
		ChunkPos chunkPos = chunk.getPos();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;

				for (int y = world.getMinY(); y < world.getMaxY(); y++) {
					BlockPos emitterPos = new BlockPos(x, y, z);
					if (!isEmitterBlock(world.getBlockState(emitterPos))) {
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
					if (!isEmitterBlock(world.getBlockState(emitterPos))) {
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
		ServerLevel world,
		LevelChunk chunk,
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

		long chunkKey = chunk.getPos().pack();
		ChunkEmitterCache cached = worldCache.get(chunkKey);
		if (cached != null && cached.emitterMaxY == emitterMaxY && !isChunkEmitterCacheExpired(world, cached)) {
			return cached;
		}

		ChunkEmitterCache rebuilt = buildChunkEmitterCache(world, chunk, biomeCache, emitterMaxY, operationsUsed, farmingOperationLimit, verticalChecksUsed, maxVerticalChecks, deadlineNanos);
		if (rebuilt == null) {
			return cached != null ? cached : new ChunkEmitterCache(world.getGameTime(), emitterMaxY, List.of());
		}

		worldCache.put(chunkKey, rebuilt);
		return rebuilt;
	}

	private static boolean isChunkEmitterCacheExpired(ServerLevel world, ChunkEmitterCache cache) {
		return world.getGameTime() - cache.builtAtTick > CHUNK_EMITTER_CACHE_TTL_TICKS;
	}

	private static ChunkEmitterCache buildChunkEmitterCache(
		ServerLevel world,
		LevelChunk chunk,
		Map<Long, Boolean> biomeCache,
		int emitterMaxY,
		int[] operationsUsed,
		int farmingOperationLimit,
		int[] verticalChecksUsed,
		int maxVerticalChecks,
		long deadlineNanos
	) {
		ChunkPos chunkPos = chunk.getPos();
		int maxBuildY = world.getMaxY() - 1;
		int minY = Math.max(world.getMinY(), world.getSeaLevel() + 1);
		int maxY = Math.min(maxBuildY, emitterMaxY);
		if (maxY < minY) {
			return new ChunkEmitterCache(world.getGameTime(), emitterMaxY, List.of());
		}

		List<BlockPos> qualifiedEmitterPositions = new ArrayList<>();
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				if (System.nanoTime() >= deadlineNanos) {
					return null;
				}

				if (operationsUsed[0] >= farmingOperationLimit) {
					return null;
				}

				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;
				if (!isInSandstormBiomeColumn(world, x, z, biomeCache)) {
					continue;
				}

				int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
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
					}
				}
			}
		}

		return new ChunkEmitterCache(world.getGameTime(), emitterMaxY, qualifiedEmitterPositions);
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

		BlockPos supportPos = emitterPos.below();
		BlockState supportState = world.getBlockState(supportPos);
		boolean pyramidSupport = supportState.is(DarudeBlocks.PYRAMID) || supportState.is(DarudeBlocks.FULL_PYRAMID);

		if (!pyramidSupport) {
			if (random.nextFloat() >= config.baseUnderGrateChance()) {
				return false;
			}
			stats.underGrateRollPasses++;
			return attemptPlacementWithFallthrough(world, emitterPos.below(), config, windDirection, random, biomeCache, operationsUsed, farmingOperationLimit, depth, stats);
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
		ServerLevel world,
		BlockPos targetPos,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		RandomSource random,
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
			BlockState layerState = DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, 1);
			if (!layerState.canSurvive(world, targetPos)) {
				return false;
			}

			if (world.setBlock(targetPos, layerState, 3)) {
				stats.successfulPlacements++;
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
				stats.successfulPlacements++;
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
