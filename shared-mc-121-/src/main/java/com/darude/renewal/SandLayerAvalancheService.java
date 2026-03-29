package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.block.SandLayerBlock;
import com.darude.worldgen.SandLayerGenerationConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

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
	private static final int MAX_QUEUED_CELLS_PER_TICK = 128;
	private static final int CHUNK_WINDOW_RADIUS = 1;
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

	public static void enqueue(ServerWorld world, BlockPos pos) {
		String key = world.getRegistryKey().getValue().toString();
		ArrayDeque<BlockPos> queue = QUEUES.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		Set<Long> queued = QUEUED_KEYS.computeIfAbsent(key, ignored -> new HashSet<>());

		long packed = pos.asLong();
		if (!queued.add(packed)) {
			return;
		}

		queue.add(pos.toImmutable());
	}

	private static void onEndWorldTick(ServerWorld world) {
		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		int remainingBudget = config.maxTopplesPerTick();
		if (remainingBudget <= 0) {
			return;
		}

		String key = world.getRegistryKey().getValue().toString();
		ArrayDeque<BlockPos> queue = QUEUES.get(key);
		if (queue == null || queue.isEmpty()) {
			return;
		}

		Set<Long> queued = QUEUED_KEYS.get(key);
		int processedCenters = 0;
		while (remainingBudget > 0 && processedCenters < MAX_QUEUED_CELLS_PER_TICK && !queue.isEmpty()) {
			BlockPos center = queue.poll();
			if (queued != null) {
				queued.remove(center.asLong());
			}

			WindowGrid grid = WindowGrid.create(world, center, CHUNK_WINDOW_RADIUS);
			if (grid == null) {
				processedCenters++;
				continue;
			}

			AvalancheRedistributor redistributor = new AvalancheRedistributor(config.avalancheSlopeThreshold());
			int processedTopples = redistributor.redistributeBudget(grid, remainingBudget);
			remainingBudget -= processedTopples;
			processedCenters++;
		}
	}

	private static final class WindowGrid implements AvalancheRedistributor.Grid {
		private final ServerWorld world;
		private final int minX;
		private final int minZ;
		private final int y;
		private final int width;
		private final int height;
		private final int[] heights;

		private WindowGrid(ServerWorld world, int minX, int minZ, int y, int width, int height) {
			this.world = world;
			this.minX = minX;
			this.minZ = minZ;
			this.y = y;
			this.width = width;
			this.height = height;
			this.heights = new int[width * height];
		}

		static WindowGrid create(ServerWorld world, BlockPos center, int chunkWindowRadius) {
			int centerChunkX = center.getX() >> 4;
			int centerChunkZ = center.getZ() >> 4;
			WorldChunk centerChunk = world.getChunk(centerChunkX, centerChunkZ, ChunkStatus.FULL, false);
			if (centerChunk == null) {
				return null;
			}

			int chunksAcross = chunkWindowRadius * 2 + 1;
			int width = chunksAcross * 16;
			int height = chunksAcross * 16;
			int minX = (centerChunkX - chunkWindowRadius) << 4;
			int minZ = (centerChunkZ - chunkWindowRadius) << 4;
			WindowGrid grid = new WindowGrid(world, minX, minZ, center.getY(), width, height);
			grid.loadHeights();
			return grid;
		}

		@Override
		public int width() {
			return width;
		}

		@Override
		public int height() {
			return height;
		}

		@Override
		public int getHeight(int x, int z) {
			return heights[indexOf(x, z)];
		}

		@Override
		public void setHeight(int x, int z, int newHeight) {
			if (!inBounds(x, z)) {
				return;
			}

			int idx = indexOf(x, z);
			heights[idx] = Math.max(0, newHeight);
			BlockPos pos = worldPos(x, z, y);
			setColumnHeightAt(pos, heights[idx]);
			heights[idx] = sandHeightFor(world.getBlockState(pos));
		}

		@Override
		public void resolveNeighbor(int sourceX, int sourceZ, int neighborX, int neighborZ, AvalancheRedistributor.NeighborInfo out) {
			if (!inBounds(neighborX, neighborZ)) {
				out.set(AvalancheRedistributor.NeighborState.BLOCKED, 0, neighborX, neighborZ);
				return;
			}

			BlockPos neighborPos = worldPos(neighborX, neighborZ, y);
			BlockState neighborState = world.getBlockState(neighborPos);
			if (isSandMass(neighborState)) {
				out.set(AvalancheRedistributor.NeighborState.VALID, heights[indexOf(neighborX, neighborZ)], neighborX, neighborZ);
				return;
			}

			if (!neighborState.isAir()) {
				out.set(AvalancheRedistributor.NeighborState.BLOCKED, 0, neighborX, neighborZ);
				return;
			}

			BlockPos belowPos = neighborPos.down();
			BlockState belowState = world.getBlockState(belowPos);
			boolean settleBelow = belowState.isAir() || belowState.isOf(DarudeBlocks.SAND_LAYER);
			if (settleBelow) {
				if (canAcceptSandLayerAt(belowPos)) {
					out.set(AvalancheRedistributor.NeighborState.VALID, 0, neighborX + width, neighborZ);
				} else {
					out.set(AvalancheRedistributor.NeighborState.UNPLACEABLE, 0, neighborX + width, neighborZ);
				}
				return;
			}

			if (canAcceptSandLayerAt(neighborPos)) {
				out.set(AvalancheRedistributor.NeighborState.VALID, 0, neighborX, neighborZ);
			} else {
				out.set(AvalancheRedistributor.NeighborState.UNPLACEABLE, 0, neighborX, neighborZ);
			}
		}

		@Override
		public void addTransferredLayers(int x, int z, int layers) {
			if (layers <= 0) {
				return;
			}

			boolean verticalTarget = x >= width && x < width * 2;
			int localX = verticalTarget ? x - width : x;
			if (!inBounds(localX, z)) {
				return;
			}

			BlockPos targetPos = worldPos(localX, z, verticalTarget ? y - 1 : y);
			addLayersConservatively(targetPos, layers);

			if (!verticalTarget) {
				heights[indexOf(localX, z)] = sandHeightFor(world.getBlockState(worldPos(localX, z, y)));
			}
		}

		private void loadHeights() {
			for (int z = 0; z < height; z++) {
				for (int x = 0; x < width; x++) {
					heights[indexOf(x, z)] = sandHeightFor(world.getBlockState(worldPos(x, z, y)));
				}
			}
		}

		private boolean canAcceptSandLayerAt(BlockPos pos) {
			BlockState state = world.getBlockState(pos);
			if (state.isOf(DarudeBlocks.SAND_LAYER)) {
				return true;
			}

			if (!state.isAir()) {
				return false;
			}

			return DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, 1).canPlaceAt(world, pos);
		}

		private void setColumnHeightAt(BlockPos pos, int desiredHeight) {
			int remaining = Math.max(0, desiredHeight);
			BlockPos.Mutable cursor = pos.mutableCopy();

			while (remaining > 0) {
				if (remaining >= 16) {
					world.setBlockState(cursor, Blocks.SAND.getDefaultState(), 3);
					remaining -= 16;
					cursor.move(0, 1, 0);
					continue;
				}

				world.setBlockState(cursor, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, remaining), 3);
				remaining = 0;
			}

			if (desiredHeight == 0) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
			}
		}

		private void addLayersConservatively(BlockPos startPos, int layers) {
			BlockPos.Mutable cursor = startPos.mutableCopy();
			int remaining = layers;

			while (remaining > 0) {
				BlockState state = world.getBlockState(cursor);
				if (state.isOf(Blocks.SAND)) {
					cursor.move(0, 1, 0);
					continue;
				}

				if (state.isOf(DarudeBlocks.SAND_LAYER)) {
					int current = state.get(SandLayerBlock.LAYERS);
					int total = current + remaining;
					if (total <= 15) {
						world.setBlockState(cursor, state.with(SandLayerBlock.LAYERS, total), 3);
						return;
					}

					world.setBlockState(cursor, Blocks.SAND.getDefaultState(), 3);
					remaining = total - 16;
					cursor.move(0, 1, 0);
					continue;
				}

				if (!state.isAir()) {
					return;
				}

				if (!DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, 1).canPlaceAt(world, cursor)) {
					return;
				}

				if (remaining >= 16) {
					world.setBlockState(cursor, Blocks.SAND.getDefaultState(), 3);
					remaining -= 16;
					cursor.move(0, 1, 0);
				} else {
					world.setBlockState(cursor, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, remaining), 3);
					return;
				}
			}
		}

		private BlockPos worldPos(int x, int z, int yLevel) {
			return new BlockPos(minX + x, yLevel, minZ + z);
		}

		private static int sandHeightFor(BlockState state) {
			if (state.isOf(DarudeBlocks.SAND_LAYER)) {
				return state.get(SandLayerBlock.LAYERS);
			}

			return state.isOf(Blocks.SAND) ? 16 : 0;
		}

		private static boolean isSandMass(BlockState state) {
			return state.isOf(DarudeBlocks.SAND_LAYER) || state.isOf(Blocks.SAND);
		}

		private boolean inBounds(int x, int z) {
			return x >= 0 && z >= 0 && x < width && z < height;
		}

		private int indexOf(int x, int z) {
			return z * width + x;
		}
	}
}
