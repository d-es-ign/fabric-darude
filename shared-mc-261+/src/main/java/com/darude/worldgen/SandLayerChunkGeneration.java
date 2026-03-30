package com.darude.worldgen;

import com.darude.DarudeBlocks;
import com.darude.DarudeDiagnostics;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SandLayerChunkGeneration {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<Block> SAND_LAYER_DESERT_SUPPORT = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sand_layer_desert_support"));
	private static final TagKey<Block> SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sand_layer_near_desert_spawnable_blocks"));
	private static final long STARTUP_SKIP_TICKS = Long.getLong("darude.chunkgen.startup_skip_ticks", 200L);
	private static final int MAX_PLACEMENTS_PER_CHUNK = Integer.getInteger("darude.chunkgen.max_placements_per_chunk", 24);
	private static final int MAX_NEAR_DESERT_CHECKS_PER_CHUNK = Integer.getInteger("darude.chunkgen.max_near_desert_checks_per_chunk", 48);
	private static final long MAX_CHUNK_WORK_NANOS = Long.getLong("darude.chunkgen.max_chunk_work_ms", 2L) * 1_000_000L;
	private static final boolean CHUNKGEN_DISABLED = Boolean.getBoolean("darude.chunkgen.disable");
	private static final Set<String> STARTUP_SKIP_LOGGED_WORLDS = ConcurrentHashMap.newKeySet();
	private static final Set<String> CHUNKGEN_ENABLED_LOGGED_WORLDS = ConcurrentHashMap.newKeySet();
	private static final int MAX_OFFSET_RADIUS = 8;
	private static final int REGION_SHIFT = 3; // 8x8 chunk regions
	private static final int MAX_REGION_CACHE_ENTRIES = Integer.getInteger("darude.chunkgen.max_region_cache_entries", 8192);
	private static final int MAX_CHUNK_BIOME_CACHE_ENTRIES = Integer.getInteger("darude.chunkgen.max_chunk_biome_cache_entries", 32768);
	private static final Map<String, Map<Long, Boolean>> NEAR_DESERT_REGION_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Map<Long, Boolean>> CHUNK_SANDSTORM_BIOME_CACHE = new ConcurrentHashMap<>();
	private static final int[][][] CIRCLE_OFFSETS_EXCLUDE_ORIGIN = new int[MAX_OFFSET_RADIUS + 1][][];
	private static final int[][][] CIRCLE_OFFSETS_INCLUDE_ORIGIN = new int[MAX_OFFSET_RADIUS + 1][][];
	private static final int[][] QUICK_CHECK_DIRECTIONS = new int[][]{
		{1, 0}, {-1, 0}, {0, 1}, {0, -1},
		{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
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
	}

	private static void placeInGeneratedChunk(ServerLevel world, LevelChunk chunk) {
		if (CHUNKGEN_DISABLED) {
			String worldKey = world.dimension().toString();
			if (STARTUP_SKIP_LOGGED_WORLDS.add("disabled:" + worldKey)) {
				DarudeMod.LOGGER.warn("Darude chunk generation disabled via -Ddarude.chunkgen.disable=true for world={}", worldKey);
			}
			return;
		}

		if (world.getGameTime() < STARTUP_SKIP_TICKS) {
			String worldKey = world.dimension().toString();
			if (STARTUP_SKIP_LOGGED_WORLDS.add(worldKey)) {
				DarudeMod.LOGGER.info("Darude chunk generation startup skip active for world={} until tick {} (current tick={})", worldKey, STARTUP_SKIP_TICKS, world.getGameTime());
			}
			return;
		}

		String worldKey = world.dimension().toString();
		if (CHUNKGEN_ENABLED_LOGGED_WORLDS.add(worldKey)) {
			DarudeMod.LOGGER.info("Darude chunk generation active for world={} at tick={}", worldKey, world.getGameTime());
		}

		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.baseMaxLayers() <= 0 && config.nearDesertMaxLayers() <= 0) {
			return;
		}

		ChunkPos chunkPos = chunk.getPos();
		long seed = world.getSeed() ^ chunkPos.pack();
		RandomSource random = RandomSource.create(seed);
		Map<Long, Boolean> chunkAvailabilityCache = new HashMap<>();
		Map<Long, Integer> topYNoLeavesCache = new HashMap<>();
		Map<Long, Boolean> biomeInSandstormCache = new HashMap<>();
		Map<Long, Boolean> nearDesertSandCache = new HashMap<>();

		if (!shouldProcessChunk(world, worldKey, chunkPos, config.nearDesertDistance(), chunkAvailabilityCache, topYNoLeavesCache, biomeInSandstormCache)) {
			return;
		}

		long startedAtNanos = System.nanoTime();
		int placements = 0;
		int nearDesertChecks = 0;
		boolean placementBudgetExhausted = false;
		boolean timeBudgetExhausted = false;

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
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
				int y = getTopYNoLeaves(world, x, z, topYNoLeavesCache);

				if (y < world.getMinY() || y > world.getMaxY()) {
					continue;
				}

				BlockPos placementPos = new BlockPos(x, y, z);
				if (!world.getBlockState(placementPos).isAir()) {
					continue;
				}

				if (isInSandstormBiome(world, placementPos, biomeInSandstormCache)) {
					if (!world.canSeeSkyFromBelowWater(placementPos)) {
						continue;
					}

					BlockPos supportPos = placementPos.below();
					BlockState supportState = world.getBlockState(supportPos);
					if (!supportState.is(SAND_LAYER_DESERT_SUPPORT)) {
						continue;
					}

					if (config.validSpotChance() <= 0.0f || random.nextFloat() >= config.validSpotChance()) {
						continue;
					}

					int maximumLayers = config.baseMaxLayers();
					int layerCount;
					if (maximumLayers <= 1) {
						layerCount = maximumLayers;
					} else {
						int surroundingFullBlocks = countHorizontalFullBlocks(world, placementPos, chunkAvailabilityCache);
						int minimumLayers = surroundingFullBlocks / 2;

						if (minimumLayers > maximumLayers) {
							minimumLayers = maximumLayers;
						}

						layerCount = random.nextInt(maximumLayers - minimumLayers + 1) + minimumLayers;
					}

					if (layerCount <= 0) {
						continue;
					}

					setSandLayers(chunk, placementPos, layerCount);
					placements++;
					continue;
				}

				if (!(world.canSeeSkyFromBelowWater(placementPos) || isUnderLeaves(world, placementPos))) {
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

				if (!isNearDesertSand(world, placementPos, config.nearDesertDistance(), chunkAvailabilityCache, topYNoLeavesCache, biomeInSandstormCache, nearDesertSandCache)) {
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

				setSandLayers(chunk, placementPos, layerCount);
				placements++;
			}

			if (placementBudgetExhausted || timeBudgetExhausted) {
				break;
			}
		}

		if (timeBudgetExhausted && Boolean.getBoolean("darude.debug.hotspots")) {
			DarudeMod.LOGGER.warn("Hotspot[chunk-generation-budget] world={} chunk={} exhausted {} ms budget", worldKey, chunkPos, MAX_CHUNK_WORK_NANOS / 1_000_000L);
		}

		DarudeDiagnostics.logChunkGeneration(
			world.dimension().toString(),
			chunkPos.toString(),
			placements,
			startedAtNanos
		);
	}

	private static boolean shouldProcessChunk(
		ServerLevel world,
		String worldKey,
		ChunkPos chunkPos,
		int nearDesertDistance,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYNoLeavesCache,
		Map<Long, Boolean> biomeInSandstormCache
	) {
		if (isChunkInSandstormBiome(world, worldKey, chunkPos, chunkAvailabilityCache, topYNoLeavesCache, biomeInSandstormCache)) {
			return true;
		}

		if (nearDesertDistance <= 0) {
			return false;
		}

		return isChunkInNearDesertRegion(world, worldKey, chunkPos, nearDesertDistance, chunkAvailabilityCache, topYNoLeavesCache, biomeInSandstormCache);
	}

	private static boolean isChunkInNearDesertRegion(
		ServerLevel world,
		String worldKey,
		ChunkPos chunkPos,
		int nearDesertDistance,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYNoLeavesCache,
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
				if (isChunkInSandstormBiome(world, worldKey, new ChunkPos(cx, cz), chunkAvailabilityCache, topYNoLeavesCache, biomeInSandstormCache)) {
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
		Map<Long, Integer> topYNoLeavesCache,
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
			int centerY = getTopYNoLeaves(world, centerX, centerZ, topYNoLeavesCache);
			if (centerY >= world.getMinY() && centerY <= world.getMaxY()) {
				inBiome = isInSandstormBiome(world, new BlockPos(centerX, centerY, centerZ), biomeInSandstormCache);
			}
		}

		if (worldChunkBiomeCache.size() > MAX_CHUNK_BIOME_CACHE_ENTRIES) {
			worldChunkBiomeCache.clear();
		}
		worldChunkBiomeCache.put(key, inBiome);
		return inBiome;
	}

	private static void setSandLayers(LevelChunk chunk, BlockPos pos, int layerCount) {
		int clampedLayers = Math.max(1, Math.min(15, layerCount));
		chunk.setBlockState(pos, DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, clampedLayers), 0);
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
		Map<Long, Integer> topYNoLeavesCache,
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

		if (hasNearbySandQuick(world, centerX, centerZ, quickSandDistance, mutablePos, chunkAvailabilityCache, topYNoLeavesCache)) {
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

			int checkY = getTopYNoLeaves(world, checkX, checkZ, topYNoLeavesCache) - 1;
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
		Map<Long, Integer> topYNoLeavesCache
	) {
		for (int[] direction : QUICK_CHECK_DIRECTIONS) {
			int checkX = centerX + direction[0] * distance;
			int checkZ = centerZ + direction[1] * distance;
			if (!isChunkAvailableForLookup(world, checkX, checkZ, chunkAvailabilityCache)) {
				continue;
			}

			int checkY = getTopYNoLeaves(world, checkX, checkZ, topYNoLeavesCache) - 1;
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

	private static int getTopYNoLeaves(ServerLevel world, int x, int z, Map<Long, Integer> topYNoLeavesCache) {
		long key = columnKey(x, z);
		Integer cached = topYNoLeavesCache.get(key);
		if (cached != null) {
			return cached;
		}

		int topY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		topYNoLeavesCache.put(key, topY);
		return topY;
	}

	private static boolean isInSandstormBiome(ServerLevel world, BlockPos pos, Map<Long, Boolean> biomeInSandstormCache) {
		long key = columnKey(pos.getX(), pos.getZ());
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
