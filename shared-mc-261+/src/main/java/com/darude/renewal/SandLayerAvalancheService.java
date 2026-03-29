package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.block.SandLayerBlock;
import com.darude.worldgen.SandLayerGenerationConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Runtime avalanche service for generated/renewed sand-layer updates.
 *
 * Kept separate from chunk/world generation logic.
 */
public final class SandLayerAvalancheService {
	private static final int MAX_QUEUED_CELLS_PER_TICK = 64;
	private static final Map<String, ArrayDeque<BlockPos>> QUEUES = new HashMap<>();
	private static final Map<String, Set<Long>> QUEUED_KEYS = new HashMap<>();
	private static boolean registered;

	private SandLayerAvalancheService() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}

		ServerTickEvents.END_WORLD_TICK.register(SandLayerAvalancheService::onEndWorldTick);
		registered = true;
	}

	public static void enqueue(ServerLevel world, BlockPos pos) {
		String key = world.dimension().location().toString();
		ArrayDeque<BlockPos> queue = QUEUES.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		Set<Long> queued = QUEUED_KEYS.computeIfAbsent(key, ignored -> new HashSet<>());

		long packed = pos.asLong();
		if (!queued.add(packed)) {
			return;
		}

		queue.add(pos.immutable());
	}

	private static void onEndWorldTick(ServerLevel world) {
		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		if (config.avalancheMaxTopplesPerIncrement() <= 0) {
			return;
		}

		String key = world.dimension().location().toString();
		ArrayDeque<BlockPos> queue = QUEUES.get(key);
		if (queue == null || queue.isEmpty()) {
			return;
		}

		Set<Long> queued = QUEUED_KEYS.get(key);
		int processed = 0;
		while (processed < MAX_QUEUED_CELLS_PER_TICK && !queue.isEmpty()) {
			BlockPos center = queue.poll();
			if (queued != null) {
				queued.remove(center.asLong());
			}

			LevelChunk chunk = world.getChunk(center.getX() >> 4, center.getZ() >> 4, ChunkStatus.FULL, false);
			if (chunk == null) {
				processed++;
				continue;
			}

			AvalancheRedistributor redistributor = new AvalancheRedistributor(config.avalancheSlopeThreshold());
			ChunkPlaneGrid grid = new ChunkPlaneGrid(world, chunk, center.getY());
			redistributor.redistributeBudget(grid, config.avalancheMaxTopplesPerIncrement());
			processed++;
		}
	}

	private static final class ChunkPlaneGrid implements AvalancheRedistributor.Grid {
		private final ServerLevel world;
		private final LevelChunk chunk;
		private final int minX;
		private final int minZ;
		private final int y;
		private final int[] heights = new int[16 * 16];

		private ChunkPlaneGrid(ServerLevel world, LevelChunk chunk, int y) {
			this.world = world;
			this.chunk = chunk;
			this.minX = chunk.getPos().getMinBlockX();
			this.minZ = chunk.getPos().getMinBlockZ();
			this.y = y;
			loadHeights();
		}

		@Override
		public int width() {
			return 16;
		}

		@Override
		public int height() {
			return 16;
		}

		@Override
		public int getHeight(int x, int z) {
			return heights[indexOf(x, z)];
		}

		@Override
		public void setHeight(int x, int z, int newHeight) {
			applyHeight(x, z, newHeight);
		}

		@Override
		public void resolveNeighbor(int sourceX, int sourceZ, int neighborX, int neighborZ, AvalancheRedistributor.NeighborInfo out) {
			if (!inBounds(neighborX, neighborZ)) {
				out.set(AvalancheRedistributor.NeighborState.BLOCKED, 0, neighborX, neighborZ);
				return;
			}

			BlockPos pos = worldPos(neighborX, neighborZ);
			BlockState state = chunk.getBlockState(pos);
			if (isSandMass(state)) {
				out.set(AvalancheRedistributor.NeighborState.VALID, heights[indexOf(neighborX, neighborZ)], neighborX, neighborZ);
				return;
			}

			if (state.isAir()) {
				boolean canPlace = DarudeBlocks.SAND_LAYER.defaultBlockState().canSurvive(world, pos);
				out.set(canPlace ? AvalancheRedistributor.NeighborState.VALID : AvalancheRedistributor.NeighborState.UNPLACEABLE, 0, neighborX, neighborZ);
				return;
			}

			out.set(AvalancheRedistributor.NeighborState.BLOCKED, 0, neighborX, neighborZ);
		}

		@Override
		public void addTransferredLayers(int x, int z, int layers) {
			if (!inBounds(x, z) || layers <= 0) {
				return;
			}

			int current = heights[indexOf(x, z)];
			applyHeight(x, z, Math.min(16, current + layers));
		}

		private void loadHeights() {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = chunk.getBlockState(worldPos(x, z));
					heights[indexOf(x, z)] = sandHeightFor(state);
				}
			}
		}

		private void applyHeight(int x, int z, int newHeight) {
			int idx = indexOf(x, z);
			int clamped = Math.max(0, newHeight);
			heights[idx] = clamped;

			BlockPos pos = worldPos(x, z);
			BlockState newState;
			if (clamped <= 0) {
				newState = Blocks.AIR.defaultBlockState();
			} else if (clamped >= 16) {
				newState = Blocks.SAND.defaultBlockState();
			} else {
				newState = DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, clamped);
			}

			world.setBlock(pos, newState, 3);
		}

		private BlockPos worldPos(int x, int z) {
			return new BlockPos(minX + x, y, minZ + z);
		}

		private static int sandHeightFor(BlockState state) {
			if (state.is(DarudeBlocks.SAND_LAYER)) {
				return state.getValue(SandLayerBlock.LAYERS);
			}

			return state.is(Blocks.SAND) ? 16 : 0;
		}

		private static boolean isSandMass(BlockState state) {
			return state.is(DarudeBlocks.SAND_LAYER) || state.is(Blocks.SAND);
		}

		private static boolean inBounds(int x, int z) {
			return x >= 0 && z >= 0 && x < 16 && z < 16;
		}

		private static int indexOf(int x, int z) {
			return z * 16 + x;
		}
	}
}
