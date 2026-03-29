package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import com.darude.worldgen.SandLayerGenerationConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * V1 sand-layer farming runtime.
 *
 * Kept separate from initial chunk/world generation logic.
 */
public final class SandLayerFarmingService {
	private static final int PLAYER_CHUNK_SCAN_RADIUS = 8;
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<net.minecraft.block.Block> FARMING_EMITTERS = TagKey.of(RegistryKeys.BLOCK, Identifier.of(DarudeMod.MOD_ID, "farming_emitters"));
	private static boolean registered;

	private SandLayerFarmingService() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}

		ServerTickEvents.END_WORLD_TICK.register(SandLayerFarmingService::onEndWorldTick);
		registered = true;
	}

	private static void onEndWorldTick(ServerWorld world) {
		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.maxFarmingOperationsPerTick() <= 0) {
			return;
		}

		long gameTime = world.getTime();
		if (gameTime % config.farmingTickIntervalTicks() != 0L) {
			return;
		}

		if (!world.isRaining()) {
			return;
		}

		Direction windDirection = SandstormWindService.getWindDirection(world);
		Random random = world.getRandom();
		Set<Long> scannedChunks = collectCandidateChunks(world);
		Map<Long, Boolean> biomeCache = new HashMap<>();
		int[] operationsUsed = new int[]{0};

		for (long packedChunkPos : scannedChunks) {
			if (operationsUsed[0] >= config.maxFarmingOperationsPerTick()) {
				return;
			}

			int chunkX = ChunkPos.getPackedX(packedChunkPos);
			int chunkZ = ChunkPos.getPackedZ(packedChunkPos);
			WorldChunk chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (chunk == null) {
				continue;
			}

			scanChunk(world, chunk, config, windDirection, random, biomeCache, operationsUsed);
		}
	}

	private static Set<Long> collectCandidateChunks(ServerWorld world) {
		Set<Long> chunks = new HashSet<>();
		for (ServerPlayerEntity player : world.getPlayers()) {
			ChunkPos center = player.getChunkPos();
			for (int dz = -PLAYER_CHUNK_SCAN_RADIUS; dz <= PLAYER_CHUNK_SCAN_RADIUS; dz++) {
				for (int dx = -PLAYER_CHUNK_SCAN_RADIUS; dx <= PLAYER_CHUNK_SCAN_RADIUS; dx++) {
					chunks.add(ChunkPos.toLong(center.x + dx, center.z + dz));
				}
			}
		}
		return chunks;
	}

	private static void scanChunk(
		ServerWorld world,
		WorldChunk chunk,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		Random random,
		Map<Long, Boolean> biomeCache,
		int[] operationsUsed
	) {
		ChunkPos chunkPos = chunk.getPos();
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				if (operationsUsed[0] >= config.maxFarmingOperationsPerTick()) {
					return;
				}

				int x = chunkPos.getStartX() + localX;
				int z = chunkPos.getStartZ() + localZ;
				if (!isInSandstormBiomeColumn(world, x, z, biomeCache)) {
					continue;
				}

				int seaLevel = world.getSeaLevel();
				int minY = Math.max(world.getBottomY(), seaLevel);
				int maxY = Math.min(world.getTopYInclusive(), world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1);
				if (maxY < minY) {
					continue;
				}

				for (int y = minY; y <= maxY; y++) {
					if (operationsUsed[0] >= config.maxFarmingOperationsPerTick()) {
						return;
					}

					BlockPos emitterPos = new BlockPos(x, y, z);
					BlockState emitterState = world.getBlockState(emitterPos);
					if (!emitterState.isIn(FARMING_EMITTERS)) {
						continue;
					}

					processEmitterAt(world, emitterPos, config, windDirection, random, biomeCache, operationsUsed, 0);
				}
			}
		}
	}

	private static boolean processEmitterAt(
		ServerWorld world,
		BlockPos emitterPos,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		Random random,
		Map<Long, Boolean> biomeCache,
		int[] operationsUsed,
		int depth
	) {
		if (depth > config.maxFallthroughDepth()) {
			return false;
		}

		if (operationsUsed[0] >= config.maxFarmingOperationsPerTick()) {
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
			return attemptPlacementWithFallthrough(world, emitterPos.down(), config, windDirection, random, biomeCache, operationsUsed, depth + 1);
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
			if (attemptPlacementWithFallthrough(world, sideTarget, config, windDirection, random, biomeCache, operationsUsed, depth + 1)) {
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
		int depth
	) {
		if (depth > config.maxFallthroughDepth()) {
			return false;
		}

		BlockState state = world.getBlockState(targetPos);
		if (state.isIn(FARMING_EMITTERS)) {
			return processEmitterAt(world, targetPos, config, windDirection, random, biomeCache, operationsUsed, depth + 1);
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
		if (!world.isRaining()) {
			return false;
		}

		if (!world.isSkyVisible(pos.up())) {
			return false;
		}

		if (!isInSandstormBiomeColumn(world, pos.getX(), pos.getZ(), biomeCache)) {
			return false;
		}

		if (!areHorizontalAndAboveAir(world, pos)) {
			return false;
		}

		BlockState below = world.getBlockState(pos.down());
		return below.isAir() || below.isOf(DarudeBlocks.SAND_LAYER) || below.isOf(DarudeBlocks.PYRAMID) || below.isOf(DarudeBlocks.FULL_PYRAMID);
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
}
