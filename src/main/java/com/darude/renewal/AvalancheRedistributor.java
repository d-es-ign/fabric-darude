package com.darude.renewal;

import java.util.Arrays;

/**
 * Redistributes unstable piles using:
 * - configurable slope threshold
 * - hybrid topple modes (horizontal/mixed relax-to-cap, vertical full-dump)
 * - deterministic share splitting (vertical first, then cardinal order)
 * - blocked vs unplaceable handling
 */
public final class AvalancheRedistributor {
	private static final int[][] CARDINALS = {
		{0, -1}, // N
		{1, 0},  // E
		{0, 1},  // S
		{-1, 0}  // W
	};

	private final int slopeThreshold;

	private int[] queue = new int[0];
	private int[] inQueueMark = new int[0];
	private int queueHead;
	private int queueTail;
	private int queueCount;
	private int markToken = 1;

	private final NeighborInfo neighborInfo = new NeighborInfo();

	private final NeighborState[] scannedState = new NeighborState[4];
	private final int[] scannedDelta = new int[4];
	private final int[] scannedDirection = new int[4];
	private final int[] scannedTargetX = new int[4];
	private final int[] scannedTargetY = new int[4];
	private final DestinationType[] scannedType = new DestinationType[4];

	private final NeighborState[] candidateState = new NeighborState[4];
	private final int[] candidateDirection = new int[4];
	private final int[] candidateTargetX = new int[4];
	private final int[] candidateTargetY = new int[4];
	private final DestinationType[] candidateType = new DestinationType[4];
	private final int[] candidateShare = new int[4];

	public AvalancheRedistributor(int slopeThreshold) {
		if (slopeThreshold < 1) {
			throw new IllegalArgumentException("slopeThreshold must be >= 1");
		}
		this.slopeThreshold = slopeThreshold;
	}

	public int redistributeBudget(Grid grid, int maxTopples) {
		if (maxTopples <= 0) return 0;

		int width = grid.width();
		int height = grid.height();
		if (width <= 0 || height <= 0) return 0;

		int size;
		try {
			size = Math.multiplyExact(width, height);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Grid size overflow: " + width + "x" + height, e);
		}

		ensureCapacity(size);
		resetQueue();
		seedQueue(grid, width, height);

		int processed = 0;
		while (processed < maxTopples && queueCount > 0) {
			int idx = dequeue();
			int sourceX = idx % width;
			int sourceY = idx / width;

			int candidateCount = buildToppleCandidates(grid, width, height, sourceX, sourceY);
			if (candidateCount == 0) continue;

			boolean changed = executeTopple(grid, sourceX, sourceY, candidateCount);
			if (!changed) continue;

			enqueueCellAndCardinals(sourceX, sourceY, width, height);
			for (int i = 0; i < candidateCount; i++) {
				if (candidateState[i] != NeighborState.VALID) continue;
				if (candidateShare[i] <= 0) continue;
				enqueueCellAndCardinals(candidateTargetX[i], candidateTargetY[i], width, height);
			}

			processed++;
		}

		return processed;
	}

	public interface Grid {
		int width();

		int height();

		int getHeight(int x, int y);

		void setHeight(int x, int y, int newHeight);

		void resolveNeighbor(int sourceX, int sourceY, int neighborX, int neighborY, NeighborInfo out);

		void addTransferredLayers(int x, int y, int layers);
	}

	public enum NeighborState {
		VALID,
		BLOCKED,
		UNPLACEABLE
	}

	public static final class NeighborInfo {
		private NeighborState state;
		private int deltaHeight;
		private int targetX;
		private int targetY;

		public NeighborState state() {
			return state;
		}

		public int deltaHeight() {
			return deltaHeight;
		}

		public int targetX() {
			return targetX;
		}

		public int targetY() {
			return targetY;
		}

		public void set(NeighborState state, int deltaHeight, int targetX, int targetY) {
			this.state = state;
			this.deltaHeight = deltaHeight;
			this.targetX = targetX;
			this.targetY = targetY;
		}
	}

	private void ensureCapacity(int size) {
		if (queue.length < size) queue = new int[size];
		if (inQueueMark.length < size) inQueueMark = new int[size];
	}

	private void resetQueue() {
		queueHead = 0;
		queueTail = 0;
		queueCount = 0;
		markToken++;
		if (markToken == 0) {
			Arrays.fill(inQueueMark, 0);
			markToken = 1;
		}
	}

	private void seedQueue(Grid grid, int width, int height) {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (grid.getHeight(x, y) > slopeThreshold) {
					enqueue(indexOf(x, y, width));
				}
			}
		}
	}

	private int buildToppleCandidates(Grid grid, int width, int height, int sourceX, int sourceY) {
		int sourceHeight = grid.getHeight(sourceX, sourceY);
		if (sourceHeight <= 0) return 0;

		int scannedCount = 0;
		boolean hasThresholdPassingCandidate = false;

		for (int dir = 0; dir < CARDINALS.length; dir++) {
			int nx = sourceX + CARDINALS[dir][0];
			int ny = sourceY + CARDINALS[dir][1];
			if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;

			grid.resolveNeighbor(sourceX, sourceY, nx, ny, neighborInfo);
			NeighborState state = neighborInfo.state();
			if (state == null) {
				throw new IllegalStateException("resolveNeighbor returned null state at x=" + nx + ", y=" + ny);
			}
			if (state == NeighborState.BLOCKED) continue;

			int deltaHeight = state == NeighborState.UNPLACEABLE ? 0 : neighborInfo.deltaHeight();
			int delta = sourceHeight - deltaHeight;
			if (delta > slopeThreshold) hasThresholdPassingCandidate = true;

			scannedState[scannedCount] = state;
			scannedDelta[scannedCount] = delta;
			scannedDirection[scannedCount] = dir;
			scannedTargetX[scannedCount] = neighborInfo.targetX();
			scannedTargetY[scannedCount] = neighborInfo.targetY();
			scannedType[scannedCount] = destinationTypeFor(nx, ny, neighborInfo.targetX(), neighborInfo.targetY());
			scannedCount++;
		}

		int candidateCount = 0;
		for (int i = 0; i < scannedCount; i++) {
			boolean include = scannedDelta[i] > slopeThreshold;
			if (!include && scannedState[i] == NeighborState.UNPLACEABLE && hasThresholdPassingCandidate) {
				include = true;
			}
			if (!include) continue;

			candidateState[candidateCount] = scannedState[i];
			candidateDirection[candidateCount] = scannedDirection[i];
			candidateTargetX[candidateCount] = scannedTargetX[i];
			candidateTargetY[candidateCount] = scannedTargetY[i];
			candidateType[candidateCount] = scannedType[i];
			candidateShare[candidateCount] = 0;
			candidateCount++;
		}

		return candidateCount;
	}

	private boolean executeTopple(Grid grid, int sourceX, int sourceY, int candidateCount) {
		int sourceHeight = grid.getHeight(sourceX, sourceY);
		if (sourceHeight <= 0) return false;

		ToppleMode mode = determineToppleMode(candidateCount);
		int transferLayers = mode == ToppleMode.PURE_VERTICAL
			? sourceHeight
			: Math.max(0, sourceHeight - slopeThreshold);
		if (transferLayers <= 0) return false;

		int baseShare = transferLayers / candidateCount;
		int remainder = transferLayers % candidateCount;
		for (int i = 0; i < candidateCount; i++) {
			candidateShare[i] = baseShare;
		}

		remainder = assignRemainder(candidateCount, remainder, DestinationType.VERTICAL);
		remainder = assignRemainder(candidateCount, remainder, DestinationType.HORIZONTAL);

		int validCount = 0;
		boolean hasUnplaceable = false;
		for (int i = 0; i < candidateCount; i++) {
			if (candidateState[i] == NeighborState.VALID) validCount++;
			if (candidateState[i] == NeighborState.UNPLACEABLE) hasUnplaceable = true;
		}

		int newSourceHeight = sourceHeight - transferLayers;
		if (validCount == 0) {
			if (hasUnplaceable) {
				grid.setHeight(sourceX, sourceY, newSourceHeight);
				return true;
			}
			return false;
		}

		grid.setHeight(sourceX, sourceY, newSourceHeight);
		for (int i = 0; i < candidateCount; i++) {
			if (candidateState[i] != NeighborState.VALID) continue;
			if (candidateShare[i] <= 0) continue;
			grid.addTransferredLayers(candidateTargetX[i], candidateTargetY[i], candidateShare[i]);
		}

		return true;
	}

	private int assignRemainder(int candidateCount, int remainder, DestinationType passType) {
		if (remainder <= 0) return 0;
		for (int dir = 0; dir < CARDINALS.length && remainder > 0; dir++) {
			for (int i = 0; i < candidateCount && remainder > 0; i++) {
				if (candidateDirection[i] != dir) continue;
				if (candidateType[i] != passType) continue;
				candidateShare[i] = addExactOrThrow(candidateShare[i], 1);
				remainder--;
			}
		}
		return remainder;
	}

	private ToppleMode determineToppleMode(int candidateCount) {
		boolean seenHorizontal = false;
		boolean seenVertical = false;

		for (int i = 0; i < candidateCount; i++) {
			if (candidateState[i] == NeighborState.UNPLACEABLE) continue;
			if (candidateType[i] == DestinationType.HORIZONTAL) {
				seenHorizontal = true;
			} else {
				seenVertical = true;
			}
		}

		if (seenVertical && !seenHorizontal) return ToppleMode.PURE_VERTICAL;
		if (seenHorizontal && !seenVertical) return ToppleMode.PURE_HORIZONTAL;
		return ToppleMode.MIXED;
	}

	private void enqueueCellAndCardinals(int x, int y, int width, int height) {
		enqueueIfInBounds(x, y, width, height);
		for (int[] d : CARDINALS) {
			enqueueIfInBounds(x + d[0], y + d[1], width, height);
		}
	}

	private void enqueueIfInBounds(int x, int y, int width, int height) {
		if (x < 0 || y < 0 || x >= width || y >= height) return;
		enqueue(indexOf(x, y, width));
	}

	private void enqueue(int idx) {
		if (inQueueMark[idx] == markToken) return;
		inQueueMark[idx] = markToken;
		queue[queueTail] = idx;
		queueTail++;
		if (queueTail == queue.length) queueTail = 0;
		queueCount++;
	}

	private int dequeue() {
		int idx = queue[queueHead];
		queueHead++;
		if (queueHead == queue.length) queueHead = 0;
		queueCount--;
		inQueueMark[idx] = 0;
		return idx;
	}

	private static int indexOf(int x, int y, int width) {
		return y * width + x;
	}

	private static int addExactOrThrow(int left, int right) {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException e) {
			throw new IllegalStateException("Avalanche layer arithmetic overflow", e);
		}
	}

	private static DestinationType destinationTypeFor(int neighborX, int neighborY, int targetX, int targetY) {
		if (targetX == neighborX && targetY == neighborY) {
			return DestinationType.HORIZONTAL;
		}
		if (targetX == neighborX && targetY == neighborY + 1) {
			return DestinationType.VERTICAL;
		}
		return DestinationType.VERTICAL;
	}

	private enum DestinationType {
		HORIZONTAL,
		VERTICAL
	}

	private enum ToppleMode {
		PURE_HORIZONTAL,
		PURE_VERTICAL,
		MIXED
	}
}
