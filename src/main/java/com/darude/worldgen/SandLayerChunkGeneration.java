package com.darude.worldgen;

import com.darude.DarudeBlocks;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SandLayerChunkGeneration {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<net.minecraft.block.Block> SAND_LAYER_SUPPORT = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "sand_layer_support"));
	private static final TagKey<net.minecraft.block.Block> SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "sand_layer_near_desert_spawnable_blocks"));
	private static final ConcurrentHashMap<Integer, int[][]> CIRCLE_OFFSETS_CACHE = new ConcurrentHashMap<>();
	private static final int[][] QUICK_CHECK_DIRECTIONS = new int[][]{
		{1, 0}, {-1, 0}, {0, 1}, {0, -1},
		{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private SandLayerChunkGeneration() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_GENERATE.register(SandLayerChunkGeneration::placeInGeneratedChunk);
	}

	private static void placeInGeneratedChunk(ServerWorld world, WorldChunk chunk) {
		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.baseMaxLayers() <= 0 && config.nearDesertMaxLayers() <= 0) {
			return;
		}

		ChunkPos chunkPos = chunk.getPos();
		long seed = world.getSeed() ^ chunkPos.toLong();
		Random random = Random.create(seed);
		Map<Long, Boolean> chunkAvailabilityCache = new HashMap<>();
		Map<Long, Integer> topYNoLeavesCache = new HashMap<>();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;
				int y = getTopYNoLeaves(world, x, z, topYNoLeavesCache);

				if (y < world.getBottomY() || y > world.getTopYInclusive()) {
					continue;
				}

				BlockPos placementPos = new BlockPos(x, y, z);
				if (!world.isAir(placementPos)) {
					continue;
				}

				if (world.getBiome(placementPos).isIn(SANDSTORM_BIOMES)) {
					if (!world.isSkyVisible(placementPos)) {
						continue;
					}

					BlockPos supportPos = placementPos.down();
					BlockState supportState = world.getBlockState(supportPos);
					if (!supportState.isIn(SAND_LAYER_SUPPORT)) {
						continue;
					}

					if (config.validSpotChance() <= 0.0f || random.nextFloat() >= config.validSpotChance()) {
						continue;
					}

					int surroundingFullBlocks = countHorizontalFullBlocks(world, placementPos, chunkAvailabilityCache);
					int minimumLayers = surroundingFullBlocks / 2;
					int maximumLayers = config.baseMaxLayers();

					if (minimumLayers > maximumLayers) {
						minimumLayers = maximumLayers;
					}

					int layerCount = random.nextInt(maximumLayers - minimumLayers + 1) + minimumLayers;
					if (layerCount <= 0) {
						continue;
					}

					setSandLayers(chunk, placementPos, layerCount);
					continue;
				}

				if (!(world.isSkyVisible(placementPos) || isUnderLeaves(world, placementPos))) {
					continue;
				}

				if (!shouldSampleNearDesertColumn(chunkPos, localX, localZ, config.nearDesertColumnSampleNumerator(), config.nearDesertColumnSampleDenominator())) {
					continue;
				}

				if (config.nearDesertValidSpotChance() <= 0.0f || random.nextFloat() >= config.nearDesertValidSpotChance()) {
					continue;
				}

				if (!isNearDesertSand(world, placementPos, config.nearDesertDistance(), chunkAvailabilityCache, topYNoLeavesCache)) {
					continue;
				}

				BlockPos supportPos = placementPos.down();
				BlockState supportState = world.getBlockState(supportPos);
				if (!isNearDesertSpawnableSupport(supportState, config)) {
					continue;
				}

				int layerCount = random.nextInt(config.nearDesertMaxLayers() - config.nearDesertMinLayers() + 1) + config.nearDesertMinLayers();
				if (layerCount <= 0) {
					continue;
				}

				setSandLayers(chunk, placementPos, layerCount);
			}
		}
	}

	private static void setSandLayers(WorldChunk chunk, BlockPos pos, int layerCount) {
		int clampedLayers = Math.max(1, Math.min(15, layerCount));
		chunk.setBlockState(pos, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, clampedLayers), 0);
	}

	private static int countHorizontalFullBlocks(ServerWorld world, BlockPos center, Map<Long, Boolean> chunkAvailabilityCache) {
		int count = 0;
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos neighborPos = center.offset(direction);
			if (!isChunkAvailableForLookup(world, neighborPos.getX(), neighborPos.getZ(), chunkAvailabilityCache)) {
				continue;
			}

			if (neighborPos.getY() < world.getBottomY() || neighborPos.getY() > world.getTopYInclusive()) {
				continue;
			}

			BlockState state = world.getBlockState(neighborPos);
			if (state.isOpaqueFullCube()) {
				count++;
			}
		}
		return count;
	}

	private static boolean isUnderLeaves(ServerWorld world, BlockPos pos) {
		int x = pos.getX();
		int z = pos.getZ();

		int topSurfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
		if (topSurfaceY <= pos.getY()) {
			return false;
		}

		BlockPos.Mutable mutablePos = new BlockPos.Mutable(x, topSurfaceY, z);
		BlockState topSurfaceState = world.getBlockState(mutablePos);
		if (!topSurfaceState.isIn(BlockTags.LEAVES)) {
			return false;
		}

		int topNonLeavesY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		return topNonLeavesY <= pos.getY();
	}

	private static boolean isNearDesertSpawnableSupport(BlockState state, SandLayerGenerationConfig.Values config) {
		if ("tag_only".equals(config.nearDesertSpawnableSupportMode())) {
			return state.isIn(SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS);
		}

		return state.isOpaqueFullCube();
	}

	public static boolean isNearDesertSand(
		ServerWorld world,
		BlockPos pos,
		int distance,
		Map<Long, Boolean> chunkAvailabilityCache,
		Map<Long, Integer> topYNoLeavesCache
	) {
		if (world.getBiome(pos).isIn(SANDSTORM_BIOMES)) {
			return false;
		}

		BlockPos.Mutable mutablePos = new BlockPos.Mutable();
		int centerX = pos.getX();
		int centerY = pos.getY();
		int centerZ = pos.getZ();
		int[][] biomeOffsets = getCircleOffsets(distance, false);
		int[][] sandOffsets = getCircleOffsets(distance + 2, true);
		int quickDistance = Math.max(1, distance);
		int quickSandDistance = quickDistance + 2;

		if (!hasNearbyDesertBiomeQuick(world, centerX, centerY, centerZ, quickDistance, mutablePos, chunkAvailabilityCache)) {
			return false;
		}

		if (hasNearbySandQuick(world, centerX, centerZ, quickSandDistance, mutablePos, chunkAvailabilityCache, topYNoLeavesCache)) {
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
			if (world.getBiome(mutablePos).isIn(SANDSTORM_BIOMES)) {
				nearDesertBiome = true;
				break;
			}
		}

		if (!nearDesertBiome) {
			return false;
		}

		for (int[] offset : sandOffsets) {
			int checkX = centerX + offset[0];
			int checkZ = centerZ + offset[1];
			if (!isChunkAvailableForLookup(world, checkX, checkZ, chunkAvailabilityCache)) {
				continue;
			}

			int checkY = getTopYNoLeaves(world, checkX, checkZ, topYNoLeavesCache) - 1;
			if (checkY < world.getBottomY() || checkY > world.getTopYInclusive()) {
				continue;
			}

			mutablePos.set(checkX, checkY, checkZ);
			if (world.getBlockState(mutablePos).isOf(Blocks.SAND)) {
				return true;
			}
		}

		return false;
	}

	private static boolean hasNearbyDesertBiomeQuick(
		ServerWorld world,
		int centerX,
		int centerY,
		int centerZ,
		int distance,
		BlockPos.Mutable mutablePos,
		Map<Long, Boolean> chunkAvailabilityCache
	) {
		for (int[] direction : QUICK_CHECK_DIRECTIONS) {
			int checkX = centerX + direction[0] * distance;
			int checkZ = centerZ + direction[1] * distance;
			if (!isChunkAvailableForLookup(world, checkX, checkZ, chunkAvailabilityCache)) {
				continue;
			}

			mutablePos.set(checkX, centerY, checkZ);
			if (world.getBiome(mutablePos).isIn(SANDSTORM_BIOMES)) {
				return true;
			}
		}

		return false;
	}

	private static boolean hasNearbySandQuick(
		ServerWorld world,
		int centerX,
		int centerZ,
		int distance,
		BlockPos.Mutable mutablePos,
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
			if (checkY < world.getBottomY() || checkY > world.getTopYInclusive()) {
				continue;
			}

			mutablePos.set(checkX, checkY, checkZ);
			if (world.getBlockState(mutablePos).isOf(Blocks.SAND)) {
				return true;
			}
		}

		return false;
	}

	private static boolean isChunkAvailableForLookup(ServerWorld world, int blockX, int blockZ, Map<Long, Boolean> chunkAvailabilityCache) {
		int chunkX = blockX >> 4;
		int chunkZ = blockZ >> 4;
		long key = ChunkPos.toLong(chunkX, chunkZ);
		Boolean cached = chunkAvailabilityCache.get(key);
		if (cached != null) {
			return cached;
		}

		boolean available = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
		chunkAvailabilityCache.put(key, available);
		return available;
	}

	private static int[][] getCircleOffsets(int radius, boolean includeOrigin) {
		if (radius <= 0) {
			return includeOrigin ? new int[][]{{0, 0}} : new int[0][];
		}

		int key = includeOrigin ? radius : -radius;
		return CIRCLE_OFFSETS_CACHE.computeIfAbsent(key, ignored -> buildCircleOffsets(radius, includeOrigin));
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

	private static int getTopYNoLeaves(ServerWorld world, int x, int z, Map<Long, Integer> topYNoLeavesCache) {
		long key = (((long) x) << 32) ^ (z & 0xffffffffL);
		Integer cached = topYNoLeavesCache.get(key);
		if (cached != null) {
			return cached;
		}

		int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		topYNoLeavesCache.put(key, topY);
		return topY;
	}

	private static boolean shouldSampleNearDesertColumn(ChunkPos chunkPos, int localX, int localZ, int numerator, int denominator) {
		if (numerator <= 0) {
			return false;
		}

		if (numerator >= denominator) {
			return true;
		}

		int hash = (int) (chunkPos.toLong() ^ (localX * 73428767L) ^ (localZ * 912931L));
		int bucket = Math.floorMod(hash, denominator);
		return bucket < numerator;
	}
}
