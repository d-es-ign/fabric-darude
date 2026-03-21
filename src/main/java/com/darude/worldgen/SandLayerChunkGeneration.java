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

public final class SandLayerChunkGeneration {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<net.minecraft.block.Block> SAND_LAYER_SUPPORT = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "sand_layer_support"));
	private static final TagKey<net.minecraft.block.Block> SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "sand_layer_near_desert_spawnable_blocks"));
	private static final Map<Integer, int[][]> SPHERE_OFFSETS_CACHE = new HashMap<>();

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

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;
				int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);

				if (y <= world.getBottomY() || y >= world.getTopYInclusive()) {
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

					int surroundingFullBlocks = countHorizontalFullBlocks(world, placementPos);
					int minimumLayers = surroundingFullBlocks / 2;
					int maximumLayers = config.baseMaxLayers();

					if (minimumLayers > maximumLayers) {
						minimumLayers = maximumLayers;
					}

					int layerCount = random.nextInt(maximumLayers - minimumLayers + 1) + minimumLayers;
					if (layerCount <= 0) {
						continue;
					}

					setSandLayers(world, placementPos, layerCount);
					continue;
				}

				if (!(world.isSkyVisible(placementPos) || isUnderLeaves(world, placementPos))) {
					continue;
				}

				if (!isNearDesertSand(world, placementPos, config.nearDesertDistance())) {
					continue;
				}

				BlockPos supportPos = placementPos.down();
				BlockState supportState = world.getBlockState(supportPos);
				if (!isNearDesertSpawnableSupport(supportState, config)) {
					continue;
				}

				if (config.nearDesertValidSpotChance() <= 0.0f || random.nextFloat() >= config.nearDesertValidSpotChance()) {
					continue;
				}

				int layerCount = random.nextInt(config.nearDesertMaxLayers() - config.nearDesertMinLayers() + 1) + config.nearDesertMinLayers();
				if (layerCount <= 0) {
					continue;
				}

				setSandLayers(world, placementPos, layerCount);
			}
		}
	}

	private static void setSandLayers(ServerWorld world, BlockPos pos, int layerCount) {
		int clampedLayers = Math.max(1, Math.min(15, layerCount));
		world.setBlockState(pos, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, clampedLayers), 3);
	}

	private static int countHorizontalFullBlocks(ServerWorld world, BlockPos center) {
		int count = 0;
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos neighborPos = center.offset(direction);
			if (!isChunkAvailableForLookup(world, neighborPos.getX(), neighborPos.getZ())) {
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
		BlockPos.Mutable mutablePos = new BlockPos.Mutable(pos.getX(), pos.getY() + 1, pos.getZ());
		for (int y = pos.getY() + 1; y <= world.getTopYInclusive(); y++) {
			mutablePos.setY(y);
			BlockState checkState = world.getBlockState(mutablePos);
			if (checkState.isAir()) {
				continue;
			}

			return checkState.isIn(BlockTags.LEAVES);
		}

		return false;
	}

	private static boolean isNearDesertSpawnableSupport(BlockState state, SandLayerGenerationConfig.Values config) {
		if ("tag_only".equals(config.nearDesertSpawnableSupportMode())) {
			return state.isIn(SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS);
		}

		return state.isOpaqueFullCube();
	}

	public static boolean isNearDesertSand(ServerWorld world, BlockPos pos, int distance) {
		if (world.getBiome(pos).isIn(SANDSTORM_BIOMES)) {
			return false;
		}

		int minY = world.getBottomY();
		int maxY = world.getTopYInclusive();
		BlockPos.Mutable mutablePos = new BlockPos.Mutable();
		int centerX = pos.getX();
		int centerY = pos.getY();
		int centerZ = pos.getZ();
		int[][] biomeOffsets = getSphereOffsets(distance, false);
		int[][] sandOffsets = getSphereOffsets(distance + 2, true);

		boolean nearDesertBiome = false;

		for (int[] offset : biomeOffsets) {
			int checkX = centerX + offset[0];
			int checkY = centerY + offset[1];
			int checkZ = centerZ + offset[2];
			if (checkY < minY || checkY > maxY || !isChunkAvailableForLookup(world, checkX, checkZ)) {
				continue;
			}

			mutablePos.set(checkX, checkY, checkZ);
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
			int checkY = centerY + offset[1];
			int checkZ = centerZ + offset[2];
			if (checkY < minY || checkY > maxY || !isChunkAvailableForLookup(world, checkX, checkZ)) {
				continue;
			}

			mutablePos.set(checkX, checkY, checkZ);
			if (world.getBlockState(mutablePos).isOf(Blocks.SAND)) {
				return true;
			}
		}

		return false;
	}

	private static boolean isChunkAvailableForLookup(ServerWorld world, int blockX, int blockZ) {
		return world.getChunk(blockX >> 4, blockZ >> 4, ChunkStatus.FULL, false) != null;
	}

	private static int[][] getSphereOffsets(int radius, boolean includeOrigin) {
		if (radius <= 0) {
			return includeOrigin ? new int[][]{{0, 0, 0}} : new int[0][];
		}

		int key = includeOrigin ? radius : -radius;
		int[][] cached = SPHERE_OFFSETS_CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		int diameter = radius * 2 + 1;
		int maxCount = diameter * diameter * diameter;
		int[][] temp = new int[maxCount][];
		int count = 0;
		int radiusSquared = radius * radius;

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (!includeOrigin && dx == 0 && dy == 0 && dz == 0) {
						continue;
					}

					if (dx * dx + dy * dy + dz * dz > radiusSquared) {
						continue;
					}

					temp[count++] = new int[]{dx, dy, dz};
				}
			}
		}

		int[][] offsets = new int[count][];
		System.arraycopy(temp, 0, offsets, 0, count);
		SPHERE_OFFSETS_CACHE.put(key, offsets);
		return offsets;
	}
}
