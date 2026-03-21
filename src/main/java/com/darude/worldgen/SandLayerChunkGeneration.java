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
import net.minecraft.world.chunk.WorldChunk;

public final class SandLayerChunkGeneration {
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<net.minecraft.block.Block> SAND_LAYER_SUPPORT = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "sand_layer_support"));
	private static final TagKey<net.minecraft.block.Block> SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "sand_layer_near_desert_spawnable_blocks"));

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
				if (!isNearDesertSpawnableSupport(world, supportPos, supportState, config)) {
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
		if (layerCount >= 16) {
			world.setBlockState(pos, Blocks.SAND.getDefaultState(), 3);
		} else {
			world.setBlockState(pos, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, layerCount), 3);
		}
	}

	private static int countHorizontalFullBlocks(ServerWorld world, BlockPos center) {
		int count = 0;
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos neighborPos = center.offset(direction);
			BlockState state = world.getBlockState(neighborPos);
			if (state.isOpaqueFullCube()) {
				count++;
			}
		}
		return count;
	}

	private static boolean isUnderLeaves(ServerWorld world, BlockPos pos) {
		for (int y = pos.getY() + 1; y <= world.getTopYInclusive(); y++) {
			BlockPos checkPos = new BlockPos(pos.getX(), y, pos.getZ());
			BlockState checkState = world.getBlockState(checkPos);
			if (checkState.isAir()) {
				continue;
			}

			return checkState.isIn(BlockTags.LEAVES);
		}

		return false;
	}

	private static boolean isNearDesertSpawnableSupport(ServerWorld world, BlockPos pos, BlockState state, SandLayerGenerationConfig.Values config) {
		if ("tag_only".equals(config.nearDesertSpawnableSupportMode())) {
			return state.isIn(SAND_LAYER_NEAR_DESERT_SPAWNABLE_BLOCKS);
		}

		return state.isOpaqueFullCube();
	}

	public static boolean isNearDesertSand(ServerWorld world, BlockPos pos, int distance) {
		if (world.getBiome(pos).isIn(SANDSTORM_BIOMES)) {
			return false;
		}

		boolean nearDesertBiome = false;

		for (int dx = -distance; dx <= distance; dx++) {
			for (int dy = -distance; dy <= distance; dy++) {
				for (int dz = -distance; dz <= distance; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}

					if (dx * dx + dy * dy + dz * dz > distance * distance) {
						continue;
					}

					BlockPos nearbyPos = pos.add(dx, dy, dz);
					if (world.getBiome(nearbyPos).isIn(SANDSTORM_BIOMES)) {
						nearDesertBiome = true;
						break;
					}
				}

				if (nearDesertBiome) {
					break;
				}
			}

			if (nearDesertBiome) {
				break;
			}
		}

		if (!nearDesertBiome) {
			return false;
		}

		int sandDistance = distance + 2;
		for (int dx = -sandDistance; dx <= sandDistance; dx++) {
			for (int dy = -sandDistance; dy <= sandDistance; dy++) {
				for (int dz = -sandDistance; dz <= sandDistance; dz++) {
					if (dx * dx + dy * dy + dz * dz > sandDistance * sandDistance) {
						continue;
					}

					if (world.getBlockState(pos.add(dx, dy, dz)).isOf(Blocks.SAND)) {
						return true;
					}
				}
			}
		}

		return false;
	}
}
