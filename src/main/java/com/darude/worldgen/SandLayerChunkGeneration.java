package com.darude.worldgen;

import com.darude.DarudeBlocks;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

	private SandLayerChunkGeneration() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_GENERATE.register(SandLayerChunkGeneration::placeInGeneratedChunk);
	}

	private static void placeInGeneratedChunk(ServerWorld world, WorldChunk chunk) {
		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.baseMaxLayers() <= 0 || config.validSpotChance() <= 0.0f) {
			return;
		}

		ChunkPos chunkPos = chunk.getPos();
		Random random = world.getRandom();

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;
				int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);

				if (y <= world.getBottomY() || y >= world.getTopY()) {
					continue;
				}

				BlockPos placementPos = new BlockPos(x, y, z);
				if (!world.isAir(placementPos) || !world.isSkyVisible(placementPos)) {
					continue;
				}

				if (!world.getBiome(placementPos).isIn(SANDSTORM_BIOMES)) {
					continue;
				}

				BlockPos supportPos = placementPos.down();
				BlockState supportState = world.getBlockState(supportPos);
				if (!supportState.isIn(SAND_LAYER_SUPPORT)) {
					continue;
				}

				if (random.nextFloat() >= config.validSpotChance()) {
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

				if (layerCount >= 16) {
					world.setBlockState(placementPos, Blocks.SAND.getDefaultState(), 3);
				} else {
					world.setBlockState(placementPos, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, layerCount), 3);
				}
			}
		}
	}

	private static int countHorizontalFullBlocks(ServerWorld world, BlockPos center) {
		int count = 0;
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos neighborPos = center.offset(direction);
			BlockState state = world.getBlockState(neighborPos);
			if (state.isOpaqueFullCube(world, neighborPos)) {
				count++;
			}
		}
		return count;
	}
}
