package com.darude.worldgen;

import com.darude.DarudeBlocks;
import com.darude.DarudeDiagnostics;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SandLayerChunkGeneration {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<Block> SAND_LAYER_DESERT_SUPPORT = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sand_layer_desert_support"));
	private static final TagKey<Block> SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sand_layer_near_desert_spawnable_blocks"));
	private static final long STARTUP_SKIP_TICKS = Long.getLong("darude.chunkgen.startup_skip_ticks", 0L);
	private static final int MAX_PLACEMENTS_PER_CHUNK = Integer.getInteger("darude.chunkgen.max_placements_per_chunk", 8);
	private static final int MAX_NEAR_DESERT_CHECKS_PER_CHUNK = Integer.getInteger("darude.chunkgen.max_near_desert_checks_per_chunk", 48);
	private static final boolean USE_NEIGHBOR_LAYER_BIAS = Boolean.parseBoolean(System.getProperty("darude.chunkgen.use_neighbor_layer_bias", "false"));
	private static final boolean USE_SKY_VISIBILITY_CHECK = Boolean.parseBoolean(System.getProperty("darude.chunkgen.use_sky_visibility_check", "false"));
	private static final int MAX_COLUMNS_PER_CHUNK = Integer.getInteger("darude.chunkgen.max_columns_per_chunk", 64);
	private static final long MAX_CHUNK_WORK_NANOS = Long.getLong("darude.chunkgen.max_chunk_work_ms", 1L) * 1_000_000L;
	private static final long MAX_TICK_WORK_NANOS = Long.getLong("darude.chunkgen.max_tick_work_ms", 2L) * 1_000_000L;
	private static final boolean DEBUG_HOTSPOTS = Boolean.getBoolean("darude.debug.hotspots");
	private static final boolean PROFILE_CHUNKGEN = Boolean.getBoolean("darude.debug.chunkgen.profile");
	private static final long PROFILE_MIN_LOG_NANOS = Long.getLong("darude.debug.chunkgen.profile_min_ms", 1L) * 1_000_000L;
	private static final long TRACE_DESERT_MIN_LOG_NANOS = Long.getLong("darude.debug.chunkgen.trace_desert_min_ms", 1L) * 1_000_000L;
	private static final long TRACE_SUMMARY_INTERVAL_TICKS = Long.getLong("darude.debug.chunkgen.summary_interval_ticks", 40L);
	private static final boolean TRACE_SUMMARY_ENABLED = Boolean.getBoolean("darude.debug.chunkgen.summary");
	private static final boolean TRACE_DESERT_ENABLED = Boolean.getBoolean("darude.debug.chunkgen.trace_desert");
	private static final boolean USE_FAST_BIOME_SKIP = Boolean.parseBoolean(System.getProperty("darude.chunkgen.use_fast_biome_skip", "false"));
	private static final boolean PROCESS_DIRECT_ON_GENERATE = Boolean.parseBoolean(System.getProperty("darude.chunkgen.process_direct_on_generate", "false"));
	private static final int MAX_QUEUED_CHUNKS_PER_TICK = Integer.getInteger("darude.chunkgen.max_queued_chunks_per_tick", 16);
	private static final int MAX_UNAVAILABLE_RETRIES = Integer.getInteger("darude.chunkgen.max_unavailable_retries", 128);
	private static final boolean CHUNKGEN_DISABLED = Boolean.parseBoolean(System.getProperty("darude.chunkgen.disable", "false"));
	private static final boolean NEAR_DESERT_DISABLED = Boolean.parseBoolean(System.getProperty("darude.chunkgen.near_desert.disable", "true"));
	private static final boolean DEBUG_DESERT_GLASS_LAYER = Boolean.parseBoolean(System.getProperty("darude.debug.chunkgen.desert_glass_layer", "true"));
	private static final boolean DEBUG_DESERT_SAMPLE_SUPPORT_MARKERS = Boolean.parseBoolean(System.getProperty("darude.debug.chunkgen.desert_sample_support_markers", "true"));
	private static final Set<String> STARTUP_SKIP_LOGGED_WORLDS = ConcurrentHashMap.newKeySet();
	private static final Set<String> CHUNKGEN_ENABLED_LOGGED_WORLDS = ConcurrentHashMap.newKeySet();
	private static final int MAX_OFFSET_RADIUS = 8;
	private static final int REGION_SHIFT = 3; // 8x8 chunk regions
	private static final int MAX_REGION_CACHE_ENTRIES = Integer.getInteger("darude.chunkgen.max_region_cache_entries", 8192);
	private static final int MAX_CHUNK_BIOME_CACHE_ENTRIES = Integer.getInteger("darude.chunkgen.max_chunk_biome_cache_entries", 32768);
	private static final Map<String, Map<Long, Boolean>> NEAR_DESERT_REGION_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Map<Long, Boolean>> CHUNK_SANDSTORM_BIOME_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, TickBudgetState> TICK_BUDGETS = new ConcurrentHashMap<>();
	private static final Map<String, QueueState> QUEUES = new ConcurrentHashMap<>();
	private static final int[][][] CIRCLE_OFFSETS_EXCLUDE_ORIGIN = new int[MAX_OFFSET_RADIUS + 1][][];
	private static final int[][][] CIRCLE_OFFSETS_INCLUDE_ORIGIN = new int[MAX_OFFSET_RADIUS + 1][][];
	private static final int[][] QUICK_CHECK_DIRECTIONS = new int[][]{
		{1, 0}, {-1, 0}, {0, 1}, {0, -1},
		{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};
	private static final int[][] PRECHECK_SAMPLE_POINTS = new int[][]{
		{8, 8}, {3, 3}, {12, 3}, {3, 12}, {12, 12}
	};

	static {
		for (int radius = 0; radius <= MAX_OFFSET_RADIUS; radius++) {
			CIRCLE_OFFSETS_EXCLUDE_ORIGIN[radius] = buildCircleOffsets(radius, false);
			CIRCLE_OFFSETS_INCLUDE_ORIGIN[radius] = buildCircleOffsets(radius, true);
		}
	}

	private SandLayerChunkGeneration() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_GENERATE.register(SandLayerChunkGeneration::placeInGeneratedChunk);
		if (!PROCESS_DIRECT_ON_GENERATE) {
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				for (ServerLevel world : server.getAllLevels()) {
					drainQueuedChunks(world);
				}
			});
		}
	}

	private static void placeInGeneratedChunk(ServerLevel world, LevelChunk chunk) {
		if (PROCESS_DIRECT_ON_GENERATE) {
			processGeneratedChunk(world, chunk);
			return;
		}

		enqueueGeneratedChunk(world, chunk.getPos());
	}

	private static void enqueueGeneratedChunk(ServerLevel world, ChunkPos chunkPos) {
		String worldKey = world.dimension().toString();
		QueueState queueState = QUEUES.computeIfAbsent(worldKey, ignored -> new QueueState());
		long packed = columnKey(chunkPos.x(), chunkPos.z());
		if (queueState.enqueued.add(packed)) {
			queueState.queue.addLast(packed);
		}
	}

	private static void drainQueuedChunks(ServerLevel world) {
		if (CHUNKGEN_DISABLED) {
			return;
		}

		String worldKey = world.dimension().toString();
		QueueState queueState = QUEUES.computeIfAbsent(worldKey, ignored -> new QueueState());
		int processedThisTick = 0;
		for (int i = 0; i < MAX_QUEUED_CHUNKS_PER_TICK; i++) {
			Long packed = queueState.queue.pollFirst();
			if (packed == null) {
				break;
			}

			int chunkX = unpackKeyX(packed);
			int chunkZ = unpackKeyZ(packed);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof LevelChunk levelChunk)) {
				int retries = queueState.unavailableRetries.getOrDefault(packed, 0) + 1;
				if (retries <= MAX_UNAVAILABLE_RETRIES) {
					queueState.unavailableRetries.put(packed, retries);
					queueState.queue.addLast(packed);
				} else {
					queueState.unavailableRetries.remove(packed);
					queueState.enqueued.remove(packed);
				}
				continue;
			}

			queueState.unavailableRetries.remove(packed);
			if (processGeneratedChunk(world, levelChunk)) {
				queueState.enqueued.remove(packed);
				processedThisTick++;
			} else {
				queueState.queue.addLast(packed);
			}
		}

		if (processedThisTick > 0) {
			DarudeMod.LOGGER.info("Trace[chunkgen-queue] world={} tick={} processedQueuedChunks={} remainingQueuedChunks={}", worldKey, world.getGameTime(), processedThisTick, queueState.queue.size());
		}
	}

	private static boolean processGeneratedChunk(ServerLevel world, LevelChunk chunk) {
		String chunkPosString = chunk.getPos().toString();
		String worldKey = world.dimension().toString();

		if (CHUNKGEN_DISABLED) {
			if (STARTUP_SKIP_LOGGED_WORLDS.add("disabled:" + worldKey)) {
				DarudeMod.LOGGER.warn("Darude chunk generation disabled via -Ddarude.chunkgen.disable=true for world={}", worldKey);
			}
			return true;
		}

		if (world.getGameTime() < STARTUP_SKIP_TICKS) {
			if (STARTUP_SKIP_LOGGED_WORLDS.add(worldKey)) {
				DarudeMod.LOGGER.info("Darude chunk generation startup skip active for world={} until tick {} (current tick={})", worldKey, STARTUP_SKIP_TICKS, world.getGameTime());
			}
			return false;
		}

		if (NEAR_DESERT_DISABLED && STARTUP_SKIP_LOGGED_WORLDS.add("near-desert-disabled:" + worldKey)) {
			DarudeMod.LOGGER.warn("Darude near-desert chunk generation disabled via -Ddarude.chunkgen.near_desert.disable=true for world={}", worldKey);
		}

		if (CHUNKGEN_ENABLED_LOGGED_WORLDS.add(worldKey)) {
			DarudeMod.LOGGER.info("Darude chunk generation active for world={} at tick={}", worldKey, world.getGameTime());
		}

		TickBudgetState tickBudget = TICK_BUDGETS.computeIfAbsent(worldKey, ignored -> new TickBudgetState());
		long currentTick = world.getGameTime();
		emitSummaryIfDue(worldKey, tickBudget, currentTick);
		if (tickBudget.tick != currentTick) {
			tickBudget.tick = currentTick;
			tickBudget.usedNanos = 0L;
			tickBudget.loggedBudgetExhausted = false;
		}
		tickBudget.callbacks++;
		if (tickBudget.usedNanos >= MAX_TICK_WORK_NANOS) {
			tickBudget.tickBudgetDrops++;
			if (!tickBudget.loggedBudgetExhausted && (DEBUG_HOTSPOTS || PROFILE_CHUNKGEN)) {
				tickBudget.loggedBudgetExhausted = true;
				DarudeMod.LOGGER.warn("Hotspot[chunkgen-tick-budget] world={} tick={} usedMs={} maxMs={}", worldKey, currentTick, tickBudget.usedNanos / 1_000_000L, MAX_TICK_WORK_NANOS / 1_000_000L);
			}
			return false;
		}

		long callbackStartedAtNanos = System.nanoTime();
		try {
			SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
			if (config.baseMaxLayers() <= 0 && config.nearDesertMaxLayers() <= 0) {
				return true;
			}

		ChunkPos chunkPos = chunk.getPos();
		long precheckStartedAtNanos = System.nanoTime();
		boolean fastBiomeSandstorm = isChunkLikelySandstormBiomeFast(world, chunkPos);
		if (USE_FAST_BIOME_SKIP && NEAR_DESERT_DISABLED && !fastBiomeSandstorm) {
			tickBudget.skippedFastBiome++;
			if (PROFILE_CHUNKGEN) {
				DarudeMod.LOGGER.info("Profile[chunkgen-skip-fast-biome] world={} chunk={} elapsedMs={}", worldKey, chunkPos, (System.nanoTime() - precheckStartedAtNanos) / 1_000_000L);
			}
			return true;
		}

		long seed = world.getSeed() ^ chunkPos.pack();
		RandomSource random = RandomSource.create(seed);
		Map<Long, Boolean> chunkAvailabilityCache = new HashMap<>();
		Map<Long, Integer> topYSurfaceCache = new HashMap<>();
		Map<Long, Boolean> biomeInSandstormCache = new HashMap<>();
		Map<Long, Boolean> nearDesertSandCache = new HashMap<>();

		boolean shouldProcess;
		if (NEAR_DESERT_DISABLED) {
			shouldProcess = isChunkInSandstormBiomeCurrentChunk(world, chunk, chunkPos, biomeInSandstormCache);
		} else {
			shouldProcess = shouldProcessChunk(world, worldKey, chunkPos, config.nearDesertDistance(), chunkAvailabilityCache, topYSurfaceCache, biomeInSandstormCache);
		}

		if (!shouldProcess) {
			tickBudget.skippedPrecheck++;
			if (PROFILE_CHUNKGEN) {
				DarudeMod.LOGGER.info("Profile[chunkgen-skip-precheck] world={} chunk={} elapsedMs={}", worldKey, chunkPos, (System.nanoTime() - precheckStartedAtNanos) / 1_000_000L);
			}
			return true;
		}


		long precheckNanos = System.nanoTime() - precheckStartedAtNanos;

		long startedAtNanos = System.nanoTime();
		int placements = 0;
		int nearDesertChecks = 0;
		int biomeChecks = 0;
		int nearDesertProbes = 0;
		boolean placementBudgetExhausted = false;
		boolean timeBudgetExhausted = false;
		long topYNanos = 0L;
		long biomeCheckNanos = 0L;
		long nearDesertProbeNanos = 0L;
		boolean[] visitedColumns = new boolean[16 * 16];
		int columnsToEvaluate = Math.max(1, Math.min(16 * 16, MAX_COLUMNS_PER_CHUNK));
		int evaluatedColumns = 0;

		while (evaluatedColumns < columnsToEvaluate) {
			int localX = random.nextInt(16);
			int localZ = random.nextInt(16);
			int columnIndex = (localX << 4) | localZ;
			if (visitedColumns[columnIndex]) {
				continue;
			}
			visitedColumns[columnIndex] = true;
			evaluatedColumns++;

				if (System.nanoTime() - startedAtNanos >= MAX_CHUNK_WORK_NANOS) {
					timeBudgetExhausted = true;
					break;
				}

				if (placements >= MAX_PLACEMENTS_PER_CHUNK) {
					placementBudgetExhausted = true;
					break;
				}

				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;
				long phaseStartedAtNanos = System.nanoTime();
				int y = getTopYSurfaceFromChunk(chunk, localX, localZ);
				topYNanos += (System.nanoTime() - phaseStartedAtNanos);

				if (y < world.getMinY() || y > world.getMaxY()) {
					continue;
				}

				BlockPos placementPos = new BlockPos(x, y, z);
				boolean blockedColumn = false;
				if (!world.getBlockState(placementPos).isAir()) {
					y += 1;
					if (y > world.getMaxY()) {
						blockedColumn = true;
						y = world.getMaxY();
						placementPos = new BlockPos(x, y, z);
					} else {
						placementPos = new BlockPos(x, y, z);
						if (!world.getBlockState(placementPos).isAir()) {
							blockedColumn = true;
						}
					}
				}

				phaseStartedAtNanos = System.nanoTime();
				boolean inSandstormBiome = isInSandstormBiome(world, placementPos, biomeInSandstormCache);
				biomeCheckNanos += (System.nanoTime() - phaseStartedAtNanos);
				biomeChecks++;
				if (inSandstormBiome) {
					if (DEBUG_DESERT_SAMPLE_SUPPORT_MARKERS) {
						if (blockedColumn) {
							world.setBlockAndUpdate(placementPos, Blocks.ORANGE_STAINED_GLASS.defaultBlockState());
							placements++;
							continue;
						}

						if (!world.canSeeSkyFromBelowWater(placementPos)) {
							world.setBlockAndUpdate(placementPos, Blocks.CYAN_STAINED_GLASS.defaultBlockState());
							placements++;
							continue;
						}

						BlockState supportState = world.getBlockState(placementPos.below());
						BlockState markerState;
						if (isSandLikeSupport(supportState)) {
							markerState = Blocks.LIME_STAINED_GLASS.defaultBlockState();
						} else if (supportState.canBeReplaced() && isSandLikeSupport(world.getBlockState(placementPos.below().below()))) {
							markerState = Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
						} else if (findNearbyAirPlacementInChunk(world, chunk, chunkPos, localX, localZ) != null) {
							markerState = Blocks.BLUE_STAINED_GLASS.defaultBlockState();
						} else {
							continue;
						}
						world.setBlockAndUpdate(placementPos, markerState);
						placements++;
						continue;
					}

					if (blockedColumn) {
						continue;
					}

					if (USE_SKY_VISIBILITY_CHECK && !world.canSeeSkyFromBelowWater(placementPos)) {
						continue;
					}

					BlockPos supportPos = placementPos.below();
					BlockState supportState = world.getBlockState(supportPos);
					if (!isSandLikeSupport(supportState)) {
						BlockPos nearbyPlacementPos = findNearbyAirPlacementInChunk(world, chunk, chunkPos, localX, localZ);
						if (nearbyPlacementPos == null) {
							continue;
						}

						placementPos = nearbyPlacementPos;
						if (!isInSandstormBiome(world, placementPos, biomeInSandstormCache)) {
							continue;
						}
						if (USE_SKY_VISIBILITY_CHECK && !world.canSeeSkyFromBelowWater(placementPos)) {
							continue;
						}

						supportPos = placementPos.below();
						supportState = world.getBlockState(supportPos);
						if (!isSandLikeSupport(supportState)) {
							continue;
						}
					}

					if (config.validSpotChance() <= 0.0f || random.nextFloat() >= config.validSpotChance()) {
						continue;
					}

					int maximumLayers = config.baseMaxLayers();
					int layerCount;
					if (maximumLayers <= 1) {
						layerCount = maximumLayers;
					} else {
						int minimumLayers = 1;
						if (USE_NEIGHBOR_LAYER_BIAS) {
							int surroundingFullBlocks = countHorizontalFullBlocks(world, placementPos, chunkAvailabilityCache);
							minimumLayers = surroundingFullBlocks / 2;
							if (minimumLayers < 1) {
								minimumLayers = 1;
							}
							if (minimumLayers > maximumLayers) {
								minimumLayers = maximumLayers;
							}
						}

						layerCount = random.nextInt(maximumLayers - minimumLayers + 1) + minimumLayers;
					}

					if (layerCount <= 0) {
						continue;
					}

				setSandLayers(world, placementPos, layerCount);
				placements++;
				continue;
			}

				if (blockedColumn) {
					continue;
				}

				if (!(world.canSeeSkyFromBelowWater(placementPos) || isUnderLeaves(world, placementPos))) {
					continue;
				}

				if (NEAR_DESERT_DISABLED) {
					continue;
				}

				if (!shouldSampleNearDesertColumn(chunkPos, localX, localZ, config.nearDesertColumnSampleNumerator(), config.nearDesertColumnSampleDenominator())) {
					continue;
				}

				if (config.nearDesertValidSpotChance() <= 0.0f || random.nextFloat() >= config.nearDesertValidSpotChance()) {
					continue;
				}

				if (nearDesertChecks >= MAX_NEAR_DESERT_CHECKS_PER_CHUNK) {
					continue;
				}
				nearDesertChecks++;

				phaseStartedAtNanos = System.nanoTime();
				boolean nearDesertSand = isNearDesertSand(world, placementPos, config.nearDesertDistance(), chunkAvailabilityCache, topYSurfaceCache, biomeInSandstormCache, nearDesertSandCache);
				nearDesertProbeNanos += (System.nanoTime() - phaseStartedAtNanos);
				nearDesertProbes++;
				if (!nearDesertSand) {
					continue;
				}

				BlockPos supportPos = placementPos.below();
				BlockState supportState = world.getBlockState(supportPos);
				if (!isNearDesertSpawnableSupport(world, supportPos, supportState, config)) {
					continue;
				}

				int layerCount = random.nextInt(config.nearDesertMaxLayers() - config.nearDesertMinLayers() + 1) + config.nearDesertMinLayers();
				if (layerCount <= 0) {
					continue;
				}

				setSandLayers(world, placementPos, layerCount);
				placements++;
			
			if (placementBudgetExhausted || timeBudgetExhausted) {
				break;
			}
		}

		if (timeBudgetExhausted && DEBUG_HOTSPOTS) {
			DarudeMod.LOGGER.warn("Hotspot[chunk-generation-budget] world={} chunk={} exhausted {} ms budget", worldKey, chunkPos, MAX_CHUNK_WORK_NANOS / 1_000_000L);
		}
		if (timeBudgetExhausted) {
			tickBudget.chunkBudgetHits++;
		}

		long chunkElapsedNanos = System.nanoTime() - startedAtNanos;
		if (PROFILE_CHUNKGEN || (DEBUG_HOTSPOTS && chunkElapsedNanos >= PROFILE_MIN_LOG_NANOS)) {
			DarudeMod.LOGGER.info(
				"Profile[chunkgen] world={} chunk={} precheckMs={} chunkMs={} cols={}/{} placements={} biomeChecks={} nearDesertChecks={} nearDesertProbes={} topYMs={} biomeMs={} nearDesertMs={} caches[chunk={},topY={},biome={},nearDesert={}] budgetHit[chunk={},tickUsedMs={},tickMaxMs={}]",
				worldKey,
				chunkPos,
				precheckNanos / 1_000_000L,
				chunkElapsedNanos / 1_000_000L,
				evaluatedColumns,
				columnsToEvaluate,
				placements,
				biomeChecks,
				nearDesertChecks,
				nearDesertProbes,
				topYNanos / 1_000_000L,
				biomeCheckNanos / 1_000_000L,
				nearDesertProbeNanos / 1_000_000L,
				chunkAvailabilityCache.size(),
				topYSurfaceCache.size(),
				biomeInSandstormCache.size(),
				nearDesertSandCache.size(),
				timeBudgetExhausted,
				tickBudget.usedNanos / 1_000_000L,
				MAX_TICK_WORK_NANOS / 1_000_000L
			);
		}

		if (TRACE_DESERT_ENABLED && fastBiomeSandstorm && chunkElapsedNanos >= TRACE_DESERT_MIN_LOG_NANOS) {
			DarudeMod.LOGGER.warn(
				"Trace[chunkgen-desert] world={} chunk={} chunkMs={} cols={}/{} placements={} biomeChecks={} nearDesertChecks={} nearDesertProbes={} topYMs={} biomeMs={} nearDesertMs={} budgetHit[chunk={},tickUsedMs={},tickMaxMs={}]",
				worldKey,
				chunkPos,
				chunkElapsedNanos / 1_000_000L,
				evaluatedColumns,
				columnsToEvaluate,
				placements,
				biomeChecks,
				nearDesertChecks,
				nearDesertProbes,
				topYNanos / 1_000_000L,
				biomeCheckNanos / 1_000_000L,
				nearDesertProbeNanos / 1_000_000L,
				timeBudgetExhausted,
				tickBudget.usedNanos / 1_000_000L,
				MAX_TICK_WORK_NANOS / 1_000_000L
			);
		}

		DarudeDiagnostics.logChunkGeneration(
			world.dimension().toString(),
			chunkPos.toString(),
			placements,
			startedAtNanos
		);
		tickBudget.processedChunks++;
		tickBudget.totalPlacements += placements;
		tickBudget.totalChunkNanos += chunkElapsedNanos;
		if (DEBUG_DESERT_GLASS_LAYER && !DEBUG_DESERT_SAMPLE_SUPPORT_MARKERS && isChunkInSandstormBiomeCurrentChunk(world, chunk, chunkPos, biomeInSandstormCache)) {
			placeDebugDesertGlassLayer(world, chunkPos);
		}
		return true;
		} finally {
			tickBudget.usedNanos += Math.max(0L, System.nanoTime() - callbackStartedAtNanos);
		}
	}

	private static void emitSummaryIfDue(String worldKey, TickBudgetState tickBudget, long currentTick) {
		if (!TRACE_SUMMARY_ENABLED) {
			return;
		}

		if (tickBudget.summaryTick == Long.MIN_VALUE) {
			tickBudget.summaryTick = currentTick;
			return;
		}

		if (currentTick - tickBudget.summaryTick < TRACE_SUMMARY_INTERVAL_TICKS) {
			return;
		}

		DarudeMod.LOGGER.info(
			"Trace[chunkgen-summary] world={} ticks={} callbacks={} processed={} skipFastBiome={} skipPrecheck={} placements={} chunkBudgetHits={} tickBudgetDrops={} chunkMsTotal={} tickUsedMs={} tickMaxMs={}",
			worldKey,
			(currentTick - tickBudget.summaryTick),
			tickBudget.callbacks,
			tickBudget.processedChunks,
			tickBudget.skippedFastBiome,
			tickBudget.skippedPrecheck,
			tickBudget.totalPlacements,
			tickBudget.chunkBudgetHits,
			tickBudget.tickBudgetDrops,
			tickBudget.totalChunkNanos / 1_000_000L,
			tickBudget.usedNanos / 1_000_000L,
			MAX_TICK_WORK_NANOS / 1_000_000L
		);

		tickBudget.summaryTick = currentTick;
		tickBudget.callbacks = 0L;
		tickBudget.processedChunks = 0L;
		tickBudget.skippedFastBiome = 0L;
		tickBudget.skippedPrecheck = 0L;
		tickBudget.totalPlacements = 0L;
		tickBudget.chunkBudgetHits = 0L;
		tickBudget.tickBudgetDrops = 0L;
		tickBudget.totalChunkNanos = 0L;
	}

	private static final class TickBudgetState {
		private long tick = Long.MIN_VALUE;
		private long usedNanos = 0L;
		private boolean loggedBudgetExhausted = false;
		private long summaryTick = Long.MIN_VALUE;
		private long callbacks = 0L;
		private long processedChunks = 0L;
		private long skippedFastBiome = 0L;
		private long skippedPrecheck = 0L;
		private long totalPlacements = 0L;
		private long chunkBudgetHits = 0L;
		private long tickBudgetDrops = 0L;
		private long totalChunkNanos = 0L;
	}

	private static final class QueueState {
		private final Deque<Long> queue = new ArrayDeque<>();
		private final Set<Long> enqueued = new HashSet<>();
		private final Map<Long, Integer> unavailableRetries = new HashMap<>();
	}

	private static boolean isChunkInSandstormBiomeCurrentChunk(
		ServerLevel world,
		LevelChunk chunk,
		ChunkPos chunkPos,
		Map<Long, Boolean> biomeInSandstormCache
	) {
		for (int[] samplePoint : PRECHECK_SAMPLE_POINTS) {
			int localX = samplePoint[0];
			int localZ = samplePoint[1];
			int y = getTopYSurfaceFromChunk(chunk, localX, localZ);
			if (y < world.getMinY() || y > world.getMaxY()) {
				continue;
			}

			int x = chunkPos.getMinBlockX() + localX;
			int z = chunkPos.getMinBlockZ() + localZ;
			if (isInSandstormBiome(world, new BlockPos(x, y, z), biomeInSandstormCache)) {
				return true;
			}
		}

		return false;
	}

	private static boolean shouldProcessChunk(
		ServerLevel world,
		String worldKey,
		ChunkPos chunkPos,
		int nearDesertDistance,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYSurfaceCache,
		Map<Long, Boolean> biomeInSandstormCache
	) {
		if (isChunkInSandstormBiome(world, worldKey, chunkPos, chunkAvailabilityCache, topYSurfaceCache, biomeInSandstormCache)) {
			return true;
		}

		if (nearDesertDistance <= 0 || NEAR_DESERT_DISABLED) {
			return false;
		}

		return isChunkInNearDesertRegion(world, worldKey, chunkPos, nearDesertDistance, chunkAvailabilityCache, topYSurfaceCache, biomeInSandstormCache);
	}

	private static boolean isChunkLikelySandstormBiomeFast(ServerLevel world, ChunkPos chunkPos) {
		int centerX = chunkPos.getMinBlockX() + 8;
		int centerZ = chunkPos.getMinBlockZ() + 8;
		int sampleY = Math.max(world.getMinY() + 1, world.getSeaLevel());
		return world.getBiome(new BlockPos(centerX, sampleY, centerZ)).is(SANDSTORM_BIOMES);
	}

	private static boolean isChunkInNearDesertRegion(
		ServerLevel world,
		String worldKey,
		ChunkPos chunkPos,
		int nearDesertDistance,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYSurfaceCache,
		Map<Long, Boolean> biomeInSandstormCache
	) {
		int chunkX = chunkPos.getMinBlockX() >> 4;
		int chunkZ = chunkPos.getMinBlockZ() >> 4;
		int regionX = chunkX >> REGION_SHIFT;
		int regionZ = chunkZ >> REGION_SHIFT;
		long regionKey = columnKey(regionX, regionZ);

		Map<Long, Boolean> worldRegionCache = NEAR_DESERT_REGION_CACHE.computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>());
		Boolean cached = worldRegionCache.get(regionKey);
		if (cached != null) {
			return cached;
		}

		int chunkRadius = Math.max(1, (nearDesertDistance + 2 + 15) / 16);
		int regionStartX = regionX << REGION_SHIFT;
		int regionStartZ = regionZ << REGION_SHIFT;
		int regionEndX = regionStartX + ((1 << REGION_SHIFT) - 1);
		int regionEndZ = regionStartZ + ((1 << REGION_SHIFT) - 1);

		boolean nearDesert = false;
		for (int cx = regionStartX - chunkRadius; cx <= regionEndX + chunkRadius && !nearDesert; cx++) {
			for (int cz = regionStartZ - chunkRadius; cz <= regionEndZ + chunkRadius; cz++) {
				if (isChunkInSandstormBiome(world, worldKey, new ChunkPos(cx, cz), chunkAvailabilityCache, topYSurfaceCache, biomeInSandstormCache)) {
					nearDesert = true;
					break;
				}
			}
		}

		if (worldRegionCache.size() > MAX_REGION_CACHE_ENTRIES) {
			worldRegionCache.clear();
		}
		worldRegionCache.put(regionKey, nearDesert);
		return nearDesert;
	}

	private static boolean isChunkInSandstormBiome(
		ServerLevel world,
		String worldKey,
		ChunkPos chunkPos,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYSurfaceCache,
		Map<Long, Boolean> biomeInSandstormCache
	) {
		long key = chunkPos.pack();
		Map<Long, Boolean> worldChunkBiomeCache = CHUNK_SANDSTORM_BIOME_CACHE.computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>());
		Boolean cached = worldChunkBiomeCache.get(key);
		if (cached != null) {
			return cached;
		}

		int centerX = chunkPos.getMinBlockX() + 8;
		int centerZ = chunkPos.getMinBlockZ() + 8;
		boolean available = isChunkAvailableForLookup(world, centerX, centerZ, chunkAvailabilityCache);
		boolean inBiome = false;
		if (available) {
			int centerY = getTopYSurface(world, centerX, centerZ, topYSurfaceCache);
			if (centerY >= world.getMinY() && centerY <= world.getMaxY()) {
				inBiome = isInSandstormBiome(world, new BlockPos(centerX, centerY, centerZ), biomeInSandstormCache);
			}
		}

		if (available) {
			if (worldChunkBiomeCache.size() > MAX_CHUNK_BIOME_CACHE_ENTRIES) {
				worldChunkBiomeCache.clear();
			}
			worldChunkBiomeCache.put(key, inBiome);
		}
		return inBiome;
	}

	private static void setSandLayers(ServerLevel world, BlockPos pos, int layerCount) {
		int clampedLayers = Math.max(1, Math.min(15, layerCount));
		world.setBlockAndUpdate(pos, DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, clampedLayers));
	}

	// TODO: Reintroduce tag-based matching once runtime tag resolution is verified stable.
	private static boolean isSandLikeSupport(BlockState state) {
		return state.is(Blocks.SAND)
			|| state.is(Blocks.RED_SAND)
			|| state.is(Blocks.SANDSTONE)
			|| state.is(Blocks.CUT_SANDSTONE)
			|| state.is(Blocks.CHISELED_SANDSTONE)
			|| state.is(Blocks.SMOOTH_SANDSTONE)
			|| state.is(Blocks.RED_SANDSTONE)
			|| state.is(Blocks.CUT_RED_SANDSTONE)
			|| state.is(Blocks.CHISELED_RED_SANDSTONE)
			|| state.is(Blocks.SMOOTH_RED_SANDSTONE)
			|| state.is(Blocks.SUSPICIOUS_SAND);
	}

	private static void placeDebugDesertGlassLayer(ServerLevel world, ChunkPos chunkPos) {
		int y = 128;
		if (y < world.getMinY() || y > world.getMaxY()) {
			return;
		}

		boolean oddChunkParity = ((chunkPos.x() + chunkPos.z()) & 1) != 0;
		BlockState glassState = oddChunkParity ? Blocks.BLACK_STAINED_GLASS.defaultBlockState() : Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				BlockPos pos = new BlockPos(chunkPos.getMinBlockX() + localX, y, chunkPos.getMinBlockZ() + localZ);
				world.setBlockAndUpdate(pos, glassState);
			}
		}
	}

	private static BlockPos findNearbyAirPlacementInChunk(ServerLevel world, LevelChunk chunk, ChunkPos chunkPos, int originLocalX, int originLocalZ) {
		for (int[] offset : QUICK_CHECK_DIRECTIONS) {
			int localX = originLocalX + offset[0];
			int localZ = originLocalZ + offset[1];
			if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
				continue;
			}

			int y = getTopYSurfaceFromChunk(chunk, localX, localZ);
			if (y < world.getMinY() || y > world.getMaxY()) {
				continue;
			}

			BlockPos candidate = new BlockPos(chunkPos.getMinBlockX() + localX, y, chunkPos.getMinBlockZ() + localZ);
			if (!world.getBlockState(candidate).isAir()) {
				y += 1;
				if (y > world.getMaxY()) {
					continue;
				}
				candidate = new BlockPos(chunkPos.getMinBlockX() + localX, y, chunkPos.getMinBlockZ() + localZ);
			}

			if (!world.getBlockState(candidate).isAir()) {
				continue;
			}

			BlockState supportState = world.getBlockState(candidate.below());
			if (supportState.is(SAND_LAYER_DESERT_SUPPORT)) {
				return candidate;
			}
		}

		return null;
	}

	private static int countHorizontalFullBlocks(ServerLevel world, BlockPos center, Map<Long, Boolean> chunkAvailabilityCache) {
		int count = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighborPos = center.relative(direction);
			if (!isChunkAvailableForLookup(world, neighborPos.getX(), neighborPos.getZ(), chunkAvailabilityCache)) {
				continue;
			}

			if (neighborPos.getY() < world.getMinY() || neighborPos.getY() > world.getMaxY()) {
				continue;
			}

			BlockState state = world.getBlockState(neighborPos);
			if (state.isCollisionShapeFullBlock(world, neighborPos)) {
				count++;
			}
		}
		return count;
	}

	private static boolean isUnderLeaves(ServerLevel world, BlockPos pos) {
		int x = pos.getX();
		int z = pos.getZ();

		int topSurfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		if (topSurfaceY <= pos.getY()) {
			return false;
		}

		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos(x, topSurfaceY, z);
		BlockState topSurfaceState = world.getBlockState(mutablePos);
		if (!topSurfaceState.is(BlockTags.LEAVES)) {
			return false;
		}

		int topNonLeavesY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		return topNonLeavesY <= pos.getY();
	}

	private static boolean isNearDesertSpawnableSupport(ServerLevel world, BlockPos pos, BlockState state, SandLayerGenerationConfig.Values config) {
		if ("tag_only".equals(config.nearDesertSpawnableSupportMode())) {
			return state.is(SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS);
		}

		return state.isCollisionShapeFullBlock(world, pos);
	}

	public static boolean isNearDesertSand(
		ServerLevel world,
		BlockPos pos,
		int distance,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYSurfaceCache,
		Map<Long, Boolean> biomeInSandstormCache,
		Map<Long, Boolean> nearDesertSandCache
	) {
		long columnKey = columnKey(pos.getX(), pos.getZ());
		Boolean cachedNearDesert = nearDesertSandCache.get(columnKey);
		if (cachedNearDesert != null) {
			return cachedNearDesert;
		}

		if (isInSandstormBiome(world, pos, biomeInSandstormCache)) {
			nearDesertSandCache.put(columnKey, false);
			return false;
		}

		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
		int centerX = pos.getX();
		int centerY = pos.getY();
		int centerZ = pos.getZ();
		int[][] biomeOffsets = getCircleOffsets(distance, false);
		int[][] sandOffsets = getCircleOffsets(distance + 2, true);
		int quickDistance = Math.max(1, distance);
		int quickSandDistance = quickDistance + 2;

		if (!hasNearbyDesertBiomeQuick(world, centerX, centerY, centerZ, quickDistance, mutablePos, chunkAvailabilityCache, biomeInSandstormCache)) {
			nearDesertSandCache.put(columnKey, false);
			return false;
		}

		if (hasNearbySandQuick(world, centerX, centerZ, quickSandDistance, mutablePos, chunkAvailabilityCache, topYSurfaceCache)) {
			nearDesertSandCache.put(columnKey, true);
			return true;
		}

		boolean nearDesertBiome = false;

		for (int[] offset : biomeOffsets) {
			int checkX = centerX + offset[0];
			int checkZ = centerZ + offset[1];
			if (!isChunkAvailableForLookup(world, checkX, checkZ, chunkAvailabilityCache)) {
				continue;
			}

			mutablePos.set(checkX, centerY, checkZ);
			if (isInSandstormBiome(world, mutablePos, biomeInSandstormCache)) {
				nearDesertBiome = true;
				break;
			}
		}

		if (!nearDesertBiome) {
			nearDesertSandCache.put(columnKey, false);
			return false;
		}

		for (int[] offset : sandOffsets) {
			int checkX = centerX + offset[0];
			int checkZ = centerZ + offset[1];
			if (!isChunkAvailableForLookup(world, checkX, checkZ, chunkAvailabilityCache)) {
				continue;
			}

			int checkY = getTopYSurface(world, checkX, checkZ, topYSurfaceCache) - 1;
			if (checkY < world.getMinY() || checkY > world.getMaxY()) {
				continue;
			}

			mutablePos.set(checkX, checkY, checkZ);
			if (world.getBlockState(mutablePos).is(Blocks.SAND)) {
				nearDesertSandCache.put(columnKey, true);
				return true;
			}
		}

		nearDesertSandCache.put(columnKey, false);
		return false;
	}

	private static boolean hasNearbyDesertBiomeQuick(
		ServerLevel world,
		int centerX,
		int centerY,
		int centerZ,
		int distance,
		BlockPos.MutableBlockPos mutablePos,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Boolean> biomeInSandstormCache
	) {
		for (int[] direction : QUICK_CHECK_DIRECTIONS) {
			int checkX = centerX + direction[0] * distance;
			int checkZ = centerZ + direction[1] * distance;
			if (!isChunkAvailableForLookup(world, checkX, checkZ, chunkAvailabilityCache)) {
				continue;
			}

			mutablePos.set(checkX, centerY, checkZ);
			if (isInSandstormBiome(world, mutablePos, biomeInSandstormCache)) {
				return true;
			}
		}

		return false;
	}

	private static boolean hasNearbySandQuick(
		ServerLevel world,
		int centerX,
		int centerZ,
		int distance,
		BlockPos.MutableBlockPos mutablePos,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYSurfaceCache
	) {
		for (int[] direction : QUICK_CHECK_DIRECTIONS) {
			int checkX = centerX + direction[0] * distance;
			int checkZ = centerZ + direction[1] * distance;
			if (!isChunkAvailableForLookup(world, checkX, checkZ, chunkAvailabilityCache)) {
				continue;
			}

			int checkY = getTopYSurface(world, checkX, checkZ, topYSurfaceCache) - 1;
			if (checkY < world.getMinY() || checkY > world.getMaxY()) {
				continue;
			}

			mutablePos.set(checkX, checkY, checkZ);
			if (world.getBlockState(mutablePos).is(Blocks.SAND)) {
				return true;
			}
		}

		return false;
	}

	private static boolean isChunkAvailableForLookup(ServerLevel world, int blockX, int blockZ, Map<Long, Boolean> chunkAvailabilityCache) {
		int chunkX = blockX >> 4;
		int chunkZ = blockZ >> 4;
		long key = ChunkPos.pack(chunkX, chunkZ);
		Boolean cached = chunkAvailabilityCache.get(key);
		if (cached != null) {
			return cached;
		}

		boolean available = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
		chunkAvailabilityCache.put(key, available);
		return available;
	}

	private static int[][] getCircleOffsets(int radius, boolean includeOrigin) {
		if (radius < 0) {
			return new int[0][];
		}

		int clampedRadius = Math.min(radius, MAX_OFFSET_RADIUS);
		return includeOrigin ? CIRCLE_OFFSETS_INCLUDE_ORIGIN[clampedRadius] : CIRCLE_OFFSETS_EXCLUDE_ORIGIN[clampedRadius];
	}

	private static int[][] buildCircleOffsets(int radius, boolean includeOrigin) {

		int diameter = radius * 2 + 1;
		int maxCount = diameter * diameter;
		int[][] temp = new int[maxCount][];
		int count = 0;
		int radiusSquared = radius * radius;

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (!includeOrigin && dx == 0 && dz == 0) {
					continue;
				}

				if (dx * dx + dz * dz > radiusSquared) {
					continue;
				}

				temp[count++] = new int[]{dx, dz};
			}
		}

		int[][] offsets = new int[count][];
		System.arraycopy(temp, 0, offsets, 0, count);
		return offsets;
	}

	private static int getTopYSurface(ServerLevel world, int x, int z, Map<Long, Integer> topYSurfaceCache) {
		long key = columnKey(x, z);
		Integer cached = topYSurfaceCache.get(key);
		if (cached != null) {
			return cached;
		}

		int topY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		topYSurfaceCache.put(key, topY);
		return topY;
	}

	private static int getTopYSurfaceFromChunk(LevelChunk chunk, int localX, int localZ) {
		return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX & 15, localZ & 15);
	}

	private static boolean isInSandstormBiome(ServerLevel world, BlockPos pos, Map<Long, Boolean> biomeInSandstormCache) {
		long key = columnKey(pos.getX() >> 2, pos.getZ() >> 2);
		Boolean cached = biomeInSandstormCache.get(key);
		if (cached != null) {
			return cached;
		}

		boolean inBiome = world.getBiome(pos).is(SANDSTORM_BIOMES);
		biomeInSandstormCache.put(key, inBiome);
		return inBiome;
	}

	private static long columnKey(int x, int z) {
		return (((long) x) << 32) ^ (z & 0xffffffffL);
	}

	private static int unpackKeyX(long key) {
		return (int) (key >> 32);
	}

	private static int unpackKeyZ(long key) {
		return (int) key;
	}

	private static boolean shouldSampleNearDesertColumn(ChunkPos chunkPos, int localX, int localZ, int numerator, int denominator) {
		if (numerator <= 0) {
			return false;
		}

		if (numerator >= denominator) {
			return true;
		}

		int hash = (int) (chunkPos.pack() ^ (localX * 73428767L) ^ (localZ * 912931L));
		int bucket = Math.floorMod(hash, denominator);
		return bucket < numerator;
	}
}
