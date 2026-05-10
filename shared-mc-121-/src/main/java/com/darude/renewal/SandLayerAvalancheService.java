package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.DarudeDiagnostics;
import com.darude.block.SandLayerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
	private static final long MAX_AVALANCHE_WORK_NANOS = Long.getLong("darude.avalanche.max_work_ms", 2L) * 1_000_000L;
	private static final boolean AVALANCHE_DISABLED = Boolean.parseBoolean(System.getProperty("darude.avalanche.disable", "true"));
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
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			QUEUES.clear();
			QUEUED_KEYS.clear();
		});
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
		if (AVALANCHE_DISABLED) {
			return;
		}

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
		int queuedBefore = queue.size();
		int processedCenters = 0;
		int totalProcessedTopples = 0;
		long startedAtNanos = System.nanoTime();
		long deadlineNanos = startedAtNanos + MAX_AVALANCHE_WORK_NANOS;
		AvalancheRedistributor redistributor = new AvalancheRedistributor(config.avalancheSlopeThreshold());
		while (remainingBudget > 0 && processedCenters < MAX_QUEUED_CELLS_PER_TICK && !queue.isEmpty() && System.nanoTime() < deadlineNanos) {
			BlockPos center = queue.poll();
			if (queued != null) {
				queued.remove(center.asLong());
			}

			WindowGrid grid = WindowGrid.create(world, center, CHUNK_WINDOW_RADIUS);
			if (grid == null) {
				queue.addLast(center);
				if (queued != null) {
					queued.add(center.asLong());
				}
				processedCenters++;
				continue;
			}

			int topplesThisCenter = redistributor.redistributeBudget(grid, remainingBudget);
			remainingBudget -= topplesThisCenter;
			totalProcessedTopples += topplesThisCenter;
			processedCenters++;
		}

		DarudeDiagnostics.logAvalancheTick(
			key,
			queuedBefore,
			processedCenters,
			totalProcessedTopples,
			remainingBudget,
			startedAtNanos
		);
	}

	private static final class WindowGrid implements AvalancheRedistributor.Grid {
		private final ServerWorld world;
		private final int minX;
		private final int minZ;
		private final int y;
		private final int width;
		private final int height;
		private final int[] activeHeights;
		private final int[] stableSandBlocks;

		private WindowGrid(ServerWorld world, int minX, int minZ, int y, int width, int height) {
			this.world = world;
			this.minX = minX;
			this.minZ = minZ;
			this.y = y;
			this.width = width;
			this.height = height;
			this.activeHeights = new int[width * height];
			this.stableSandBlocks = new int[width * height];
		}

		static WindowGrid create(ServerWorld world, BlockPos center, int chunkWindowRadius) {
			int centerChunkX = center.getX() >> 4;
			int centerChunkZ = center.getZ() >> 4;
			for (int cz = centerChunkZ - chunkWindowRadius; cz <= centerChunkZ + chunkWindowRadius; cz++) {
				for (int cx = centerChunkX - chunkWindowRadius; cx <= centerChunkX + chunkWindowRadius; cx++) {
					var chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
					if (!(chunk instanceof WorldChunk)) {
						return null;
					}
				}
			}

			int chunksAcross = chunkWindowRadius * 2 + 1;
			int width = chunksAcross * 16;
			int height = chunksAcross * 16;
			int minX = (centerChunkX - chunkWindowRadius) << 4;
			int minZ = (centerChunkZ - chunkWindowRadius) << 4;
			WindowGrid grid = new WindowGrid(world, minX, minZ, center.getY(), width, height);
			grid.loadColumnStates();
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
			return activeHeights[indexOf(x, z)];
		}

		@Override
		public void setHeight(int x, int z, int newHeight) {
			if (!inBounds(x, z)) {
				return;
			}

			int idx = indexOf(x, z);
			activeHeights[idx] = Math.max(0, newHeight);
			BlockPos pos = worldPos(x, z, y);
			applyColumnStateAt(pos, stableSandBlocks[idx], activeHeights[idx]);
		}

		@Override
		public void settleCell(int x, int z) {
			if (!inBounds(x, z)) {
				return;
			}

			int idx = indexOf(x, z);
			int activeHeight = activeHeights[idx];
			if (activeHeight < 16) {
				return;
			}

			stableSandBlocks[idx] += activeHeight / 16;
			activeHeights[idx] = activeHeight % 16;
			applyColumnStateAt(worldPos(x, z, y), stableSandBlocks[idx], activeHeights[idx]);
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
				out.set(AvalancheRedistributor.NeighborState.VALID, activeHeights[indexOf(neighborX, neighborZ)], neighborX, neighborZ);
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
				activeHeights[indexOf(localX, z)] += layers;
			}
		}

		private void loadColumnStates() {
			BlockPos.Mutable cursor = BlockPos.ORIGIN.mutableCopy();
			for (int z = 0; z < height; z++) {
				for (int x = 0; x < width; x++) {
					cursor.set(minX + x, y, minZ + z);
					ColumnState state = readColumnStateAt(cursor);
					int idx = indexOf(x, z);
					stableSandBlocks[idx] = state.stableSandBlocks();
					activeHeights[idx] = state.activeHeight();
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

		private void applyColumnStateAt(BlockPos pos, int stableBlocks, int activeHeight) {
			if (!isWithinBuildHeight(pos.getY())) {
				return;
			}

			clearSandMassAbove(pos);

			int remaining = Math.max(0, stableBlocks * 16 + activeHeight);
			BlockPos.Mutable cursor = pos.mutableCopy();

			while (remaining > 0) {
				if (!isWithinBuildHeight(cursor.getY())) {
					return;
				}

				if (remaining >= 16) {
					if (!world.setBlockState(cursor, Blocks.SAND.getDefaultState(), 3)) {
						return;
					}
					remaining -= 16;
					cursor.move(0, 1, 0);
					continue;
				}

				if (!world.setBlockState(cursor, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, remaining), 3)) {
					return;
				}
				remaining = 0;
			}

			if (remaining == 0 && stableBlocks == 0 && activeHeight == 0) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
			}
		}

		private ColumnState readColumnStateAt(BlockPos pos) {
			int stableBlocks = 0;
			int activeHeight = 0;
			boolean sawTopLayers = false;
			BlockPos.Mutable cursor = pos.mutableCopy();
			while (true) {
				BlockState state = world.getBlockState(cursor);
				if (state.isOf(Blocks.SAND)) {
					if (sawTopLayers) {
						activeHeight += 16;
					} else {
						stableBlocks++;
					}
					cursor.move(0, 1, 0);
					continue;
				}

				if (state.isOf(DarudeBlocks.SAND_LAYER)) {
					sawTopLayers = true;
					activeHeight += state.get(SandLayerBlock.LAYERS);
					cursor.move(0, 1, 0);
					continue;
				}

				return new ColumnState(stableBlocks, activeHeight);
			}
		}

		private void clearSandMassAbove(BlockPos pos) {
			BlockPos.Mutable cursor = pos.mutableCopy();
			while (true) {
				if (!isWithinBuildHeight(cursor.getY())) {
					return;
				}

				BlockState state = world.getBlockState(cursor);
				if (!isSandMass(state)) {
					return;
				}

				if (!world.setBlockState(cursor, Blocks.AIR.getDefaultState(), 3)) {
					return;
				}
				cursor.move(0, 1, 0);
			}
		}

		private void addLayersConservatively(BlockPos startPos, int layers) {
			BlockPos.Mutable cursor = startPos.mutableCopy();
			int remaining = layers;

			while (remaining > 0) {
				if (!isWithinBuildHeight(cursor.getY())) {
					return;
				}

				BlockState state = world.getBlockState(cursor);
				if (state.isOf(Blocks.SAND)) {
					cursor.move(0, 1, 0);
					continue;
				}

				if (state.isOf(DarudeBlocks.SAND_LAYER)) {
					int current = state.get(SandLayerBlock.LAYERS);
					int total = current + remaining;
					if (total <= 15) {
						if (!world.setBlockState(cursor, state.with(SandLayerBlock.LAYERS, total), 3)) {
							return;
						}
						return;
					}

					if (!world.setBlockState(cursor, Blocks.SAND.getDefaultState(), 3)) {
						return;
					}
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
					if (!world.setBlockState(cursor, Blocks.SAND.getDefaultState(), 3)) {
						return;
					}
					remaining -= 16;
					cursor.move(0, 1, 0);
				} else {
					if (!world.setBlockState(cursor, DarudeBlocks.SAND_LAYER.getDefaultState().with(SandLayerBlock.LAYERS, remaining), 3)) {
						return;
					}
					return;
				}
			}
		}

		private boolean isWithinBuildHeight(int yLevel) {
			return yLevel >= world.getBottomY() && yLevel <= world.getTopYInclusive();
		}

		private BlockPos worldPos(int x, int z, int yLevel) {
			return new BlockPos(minX + x, yLevel, minZ + z);
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

	private record ColumnState(int stableSandBlocks, int activeHeight) {
	}
}
