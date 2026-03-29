package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.DarudeMod;
import com.darude.block.SandLayerBlock;
import com.darude.worldgen.SandLayerGenerationConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * V1 sand-layer farming runtime.
 *
 * Kept separate from initial chunk/world generation logic.
 */
public final class SandLayerFarmingService {
	private static final int PLAYER_CHUNK_SCAN_RADIUS = 8;
	private static final int MIN_VERTICAL_CHECKS_PER_TICK = 2048;
	private static final TagKey<Biome> SANDSTORM_BIOMES = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "sandstorm_biomes"));
	private static final TagKey<Block> FARMING_EMITTERS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, "farming_emitters"));
	private static boolean registered;

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
		registered = true;
	}

	private static void onEndWorldTick(ServerLevel world) {
		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.maxFarmingOperationsPerTick() <= 0) {
			return;
		}

		long gameTime = world.getGameTime();
		if (gameTime % config.farmingTickIntervalTicks() != 0L) {
			return;
		}

		if (!world.isRaining()) {
			return;
		}

		Direction windDirection = SandstormWindService.getWindDirection(world);
		RandomSource random = world.getRandom();
		Set<Long> scannedChunks = collectCandidateChunks(world);
		Map<Long, Boolean> biomeCache = new HashMap<>();
		int[] operationsUsed = new int[]{0};
		int[] verticalChecksUsed = new int[]{0};
		int maxVerticalChecks = Math.max(MIN_VERTICAL_CHECKS_PER_TICK, config.maxFarmingOperationsPerTick() * 32);

		for (long packedChunkPos : scannedChunks) {
			if (operationsUsed[0] >= config.maxFarmingOperationsPerTick()) {
				return;
			}

			int chunkX = ChunkPos.getX(packedChunkPos);
			int chunkZ = ChunkPos.getZ(packedChunkPos);
			var chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (!(chunk instanceof LevelChunk levelChunk)) {
				continue;
			}

			scanChunk(world, levelChunk, config, windDirection, random, biomeCache, operationsUsed, verticalChecksUsed, maxVerticalChecks);
		}
	}

	private static Set<Long> collectCandidateChunks(ServerLevel world) {
		Set<Long> chunks = new TreeSet<>();
		for (ServerPlayer player : world.players()) {
			ChunkPos center = player.chunkPosition();
			for (int dz = -PLAYER_CHUNK_SCAN_RADIUS; dz <= PLAYER_CHUNK_SCAN_RADIUS; dz++) {
				for (int dx = -PLAYER_CHUNK_SCAN_RADIUS; dx <= PLAYER_CHUNK_SCAN_RADIUS; dx++) {
					chunks.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
				}
			}
		}
		return chunks;
	}

	private static void scanChunk(
		ServerLevel world,
		LevelChunk chunk,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		RandomSource random,
		Map<Long, Boolean> biomeCache,
		int[] operationsUsed,
		int[] verticalChecksUsed,
		int maxVerticalChecks
	) {
		ChunkPos chunkPos = chunk.getPos();
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				if (operationsUsed[0] >= config.maxFarmingOperationsPerTick()) {
					return;
				}

				int x = chunkPos.getMinBlockX() + localX;
				int z = chunkPos.getMinBlockZ() + localZ;
				if (!isInSandstormBiomeColumn(world, x, z, biomeCache)) {
					continue;
				}

				int seaLevel = world.getSeaLevel();
				int minY = Math.max(world.getMinY(), seaLevel);
				int maxBuildY = world.getMaxY() - 1;
				int maxY = Math.min(maxBuildY, world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
				if (maxY < minY) {
					continue;
				}

				for (int y = minY; y <= maxY; y++) {
					if (verticalChecksUsed[0]++ >= maxVerticalChecks) {
						return;
					}

					if (operationsUsed[0] >= config.maxFarmingOperationsPerTick()) {
						return;
					}

					BlockPos emitterPos = new BlockPos(x, y, z);
					BlockState emitterState = world.getBlockState(emitterPos);
					if (!emitterState.is(FARMING_EMITTERS)) {
						continue;
					}

					processEmitterAt(world, emitterPos, config, windDirection, random, biomeCache, operationsUsed, 0);
				}
			}
		}
	}

	private static boolean processEmitterAt(
		ServerLevel world,
		BlockPos emitterPos,
		SandLayerGenerationConfig.Values config,
		Direction windDirection,
		RandomSource random,
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

		BlockPos supportPos = emitterPos.below();
		BlockState supportState = world.getBlockState(supportPos);
		boolean pyramidSupport = supportState.is(DarudeBlocks.PYRAMID) || supportState.is(DarudeBlocks.FULL_PYRAMID);

		if (!pyramidSupport) {
			if (random.nextFloat() >= config.baseUnderGrateChance()) {
				return false;
			}
			return attemptPlacementWithFallthrough(world, emitterPos.below(), config, windDirection, random, biomeCache, operationsUsed, depth);
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
			if (attemptPlacementWithFallthrough(world, sideTarget, config, windDirection, random, biomeCache, operationsUsed, depth)) {
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
		int depth
	) {
		if (depth > config.maxFallthroughDepth()) {
			return false;
		}

		BlockState state = world.getBlockState(targetPos);
		if (state.is(FARMING_EMITTERS)) {
			return processEmitterAt(world, targetPos, config, windDirection, random, biomeCache, operationsUsed, depth + 1);
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
		if (!world.isRaining()) {
			return false;
		}

		if (!world.canSeeSkyFromBelowWater(pos.above())) {
			return false;
		}

		if (!isInSandstormBiomeColumn(world, pos.getX(), pos.getZ(), biomeCache)) {
			return false;
		}

		if (!areHorizontalAndAboveAir(world, pos)) {
			return false;
		}

		BlockState below = world.getBlockState(pos.below());
		return below.isAir() || below.is(DarudeBlocks.SAND_LAYER) || below.is(DarudeBlocks.PYRAMID) || below.is(DarudeBlocks.FULL_PYRAMID);
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
}
