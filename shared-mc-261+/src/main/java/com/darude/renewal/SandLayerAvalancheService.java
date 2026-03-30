package com.darude.renewal;

import com.darude.DarudeBlocks;
import com.darude.DarudeDiagnostics;
import com.darude.block.SandLayerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import com.darude.worldgen.SandLayerGenerationConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
	private static final int MAX_QUEUED_CELLS_PER_TICK = 128;
	private static final int CHUNK_WINDOW_RADIUS = 1;
	private static final long MAX_AVALANCHE_WORK_NANOS = Long.getLong("darude.avalanche.max_work_ms", 2L) * 1_000_000L;
	private static final boolean AVALANCHE_DISABLED = Boolean.parseBoolean(System.getProperty("darude.avalanche.disable", "true"));
	private static final Map<ServerLevel, ArrayDeque<BlockPos>> QUEUES = new HashMap<>();
	private static final Map<ServerLevel, Set<Long>> QUEUED_KEYS = new HashMap<>();
	private static boolean registered;

	private SandLayerAvalancheService() {
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
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			QUEUES.clear();
			QUEUED_KEYS.clear();
		});
		registered = true;
	}

	public static void enqueue(ServerLevel world, BlockPos pos) {
		ArrayDeque<BlockPos> queue = QUEUES.computeIfAbsent(world, ignored -> new ArrayDeque<>());
		Set<Long> queued = QUEUED_KEYS.computeIfAbsent(world, ignored -> new HashSet<>());

		long packed = pos.asLong();
		if (!queued.add(packed)) {
			return;
		}

		queue.add(pos.immutable());
	}

	private static void onEndWorldTick(ServerLevel world) {
		if (AVALANCHE_DISABLED) {
			return;
		}

		SandLayerGenerationConfig.Values config = SandLayerGenerationConfig.get();
		int remainingBudget = config.maxTopplesPerTick();
		if (remainingBudget <= 0) {
			return;
		}

		ArrayDeque<BlockPos> queue = QUEUES.get(world);
		if (queue == null || queue.isEmpty()) {
			return;
		}

		Set<Long> queued = QUEUED_KEYS.get(world);
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
				processedCenters++;
				continue;
			}

			int topplesThisCenter = redistributor.redistributeBudget(grid, remainingBudget);
			remainingBudget -= topplesThisCenter;
			totalProcessedTopples += topplesThisCenter;
			processedCenters++;
		}

		DarudeDiagnostics.logAvalancheTick(
			world.dimension().toString(),
			queuedBefore,
			processedCenters,
			totalProcessedTopples,
			remainingBudget,
			startedAtNanos
		);
	}

	private static final class WindowGrid implements AvalancheRedistributor.Grid {
		private final ServerLevel world;
		private final int minX;
		private final int minZ;
		private final int y;
		private final int width;
		private final int height;
		private final int[] heights;

		private WindowGrid(ServerLevel world, int minX, int minZ, int y, int width, int height) {
			this.world = world;
			this.minX = minX;
			this.minZ = minZ;
			this.y = y;
			this.width = width;
			this.height = height;
			this.heights = new int[width * height];
		}

		static WindowGrid create(ServerLevel world, BlockPos center, int chunkWindowRadius) {
			int centerChunkX = center.getX() >> 4;
			int centerChunkZ = center.getZ() >> 4;
			for (int cz = centerChunkZ - chunkWindowRadius; cz <= centerChunkZ + chunkWindowRadius; cz++) {
				for (int cx = centerChunkX - chunkWindowRadius; cx <= centerChunkX + chunkWindowRadius; cx++) {
					var chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
					if (!(chunk instanceof LevelChunk)) {
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
			heights[idx] = readColumnHeightAt(pos);
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

			BlockPos belowPos = neighborPos.below();
			BlockState belowState = world.getBlockState(belowPos);
			boolean settleBelow = belowState.isAir() || belowState.is(DarudeBlocks.SAND_LAYER);
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
				heights[indexOf(localX, z)] = readColumnHeightAt(worldPos(localX, z, y));
			}
		}

		private void loadHeights() {
			BlockPos.MutableBlockPos cursor = BlockPos.ZERO.mutable();
			for (int z = 0; z < height; z++) {
				for (int x = 0; x < width; x++) {
					cursor.set(minX + x, y, minZ + z);
					heights[indexOf(x, z)] = readColumnHeightAt(cursor);
				}
			}
		}

		private boolean canAcceptSandLayerAt(BlockPos pos) {
			BlockState state = world.getBlockState(pos);
			if (state.is(DarudeBlocks.SAND_LAYER)) {
				return true;
			}

			if (!state.isAir()) {
				return false;
			}

			return DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, 1).canSurvive(world, pos);
		}

		private void setColumnHeightAt(BlockPos pos, int desiredHeight) {
			if (!isWithinBuildHeight(pos.getY())) {
				return;
			}

			clearSandMassAbove(pos);

			int remaining = Math.max(0, desiredHeight);
			BlockPos.MutableBlockPos cursor = pos.mutable();

			while (remaining > 0) {
				if (!isWithinBuildHeight(cursor.getY())) {
					return;
				}

				if (remaining >= 16) {
					if (!world.setBlock(cursor, Blocks.SAND.defaultBlockState(), 3)) {
						return;
					}
					remaining -= 16;
					cursor.move(0, 1, 0);
					continue;
				}

				if (!world.setBlock(cursor, DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, remaining), 3)) {
					return;
				}
				remaining = 0;
			}

			if (desiredHeight == 0) {
				world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			}
		}

		private int readColumnHeightAt(BlockPos pos) {
			int total = 0;
			BlockPos.MutableBlockPos cursor = pos.mutable();
			while (true) {
				BlockState state = world.getBlockState(cursor);
				if (state.is(Blocks.SAND)) {
					total += 16;
					cursor.move(0, 1, 0);
					continue;
				}

				if (state.is(DarudeBlocks.SAND_LAYER)) {
					total += state.getValue(SandLayerBlock.LAYERS);
					cursor.move(0, 1, 0);
					continue;
				}

				return total;
			}
		}

		private void clearSandMassAbove(BlockPos pos) {
			BlockPos.MutableBlockPos cursor = pos.mutable();
			while (true) {
				if (!isWithinBuildHeight(cursor.getY())) {
					return;
				}

				BlockState state = world.getBlockState(cursor);
				if (!isSandMass(state)) {
					return;
				}

				if (!world.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3)) {
					return;
				}
				cursor.move(0, 1, 0);
			}
		}

		private void addLayersConservatively(BlockPos startPos, int layers) {
			BlockPos.MutableBlockPos cursor = startPos.mutable();
			int remaining = layers;

			while (remaining > 0) {
				if (!isWithinBuildHeight(cursor.getY())) {
					return;
				}

				BlockState state = world.getBlockState(cursor);
				if (state.is(Blocks.SAND)) {
					cursor.move(0, 1, 0);
					continue;
				}

				if (state.is(DarudeBlocks.SAND_LAYER)) {
					int current = state.getValue(SandLayerBlock.LAYERS);
					int total = current + remaining;
					if (total <= 15) {
						if (!world.setBlock(cursor, state.setValue(SandLayerBlock.LAYERS, total), 3)) {
							return;
						}
						return;
					}

					if (!world.setBlock(cursor, Blocks.SAND.defaultBlockState(), 3)) {
						return;
					}
					remaining = total - 16;
					cursor.move(0, 1, 0);
					continue;
				}

				if (!state.isAir()) {
					return;
				}

				if (!DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, 1).canSurvive(world, cursor)) {
					return;
				}

				if (remaining >= 16) {
					if (!world.setBlock(cursor, Blocks.SAND.defaultBlockState(), 3)) {
						return;
					}
					remaining -= 16;
					cursor.move(0, 1, 0);
				} else {
					if (!world.setBlock(cursor, DarudeBlocks.SAND_LAYER.defaultBlockState().setValue(SandLayerBlock.LAYERS, remaining), 3)) {
						return;
					}
					return;
				}
			}
		}

		private boolean isWithinBuildHeight(int yLevel) {
			return yLevel >= world.getMinY() && yLevel <= world.getMaxY() - 1;
		}

		private BlockPos worldPos(int x, int z, int yLevel) {
			return new BlockPos(minX + x, yLevel, minZ + z);
		}

		private static boolean isSandMass(BlockState state) {
			return state.is(DarudeBlocks.SAND_LAYER) || state.is(Blocks.SAND);
		}

		private boolean inBounds(int x, int z) {
			return x >= 0 && z >= 0 && x < width && z < height;
		}

		private int indexOf(int x, int z) {
			return z * width + x;
		}
	}
}
