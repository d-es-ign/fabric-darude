package com.darude.renewal;

import java.util.HashMap;
import java.util.Map;

public final class AvalancheRedistributorHarness {
	private static final int[][] CARDINALS = {
		{0, -1},
		{1, 0},
		{0, 1},
		{-1, 0}
	};

	public static void main(String[] args) {
		testAllBlockedDoesNotTopple();
		testUnplaceableDispersesPlannedShare();
		testPureVerticalUsesFullDump();
		testRemainderPrefersVerticalThenCardinal();
		System.out.println("AvalancheRedistributor harness: OK");
	}

	private static void testAllBlockedDoesNotTopple() {
		TestGrid grid = new TestGrid(3, 3);
		grid.setInitialHeight(1, 1, 6);
		grid.setAllNeighborsBlocked(1, 1);

		AvalancheRedistributor redistributor = new AvalancheRedistributor(3);
		int processed = redistributor.redistributeBudget(grid, 8);

		check(processed == 0, "all-blocked should not consume topple budget");
		check(grid.getHeight(1, 1) == 6, "all-blocked should keep source unchanged");
	}

	private static void testUnplaceableDispersesPlannedShare() {
		TestGrid grid = new TestGrid(3, 3);
		grid.setInitialHeight(1, 1, 3);

		// N valid (horizontal), E/W unplaceable, S blocked.
		grid.setRule(1, 1, 1, 0, AvalancheRedistributor.NeighborState.VALID, 0, 1, 0);
		grid.setRule(1, 1, 2, 1, AvalancheRedistributor.NeighborState.UNPLACEABLE, 99, 2, 2);
		grid.setRule(1, 1, 0, 1, AvalancheRedistributor.NeighborState.UNPLACEABLE, 99, 0, 2);
		grid.setRule(1, 1, 1, 2, AvalancheRedistributor.NeighborState.BLOCKED, 0, 1, 2);

		AvalancheRedistributor redistributor = new AvalancheRedistributor(1);
		int processed = redistributor.redistributeBudget(grid, 1);

		check(processed == 1, "expected one topple event");
		check(grid.getHeight(1, 1) == 1, "mixed/horizontal mode should relax to cap");
		check(grid.getHeight(1, 0) == 1, "valid neighbor should receive only its planned share");
	}

	private static void testPureVerticalUsesFullDump() {
		TestGrid grid = new TestGrid(3, 3);
		grid.setInitialHeight(1, 1, 8);

		// E/W valid vertical destinations (targets below neighbors), N/S blocked.
		grid.setRule(1, 1, 2, 1, AvalancheRedistributor.NeighborState.VALID, 0, 2, 2);
		grid.setRule(1, 1, 0, 1, AvalancheRedistributor.NeighborState.VALID, 0, 0, 2);
		grid.setRule(1, 1, 1, 0, AvalancheRedistributor.NeighborState.BLOCKED, 0, 1, 0);
		grid.setRule(1, 1, 1, 2, AvalancheRedistributor.NeighborState.BLOCKED, 0, 1, 2);

		AvalancheRedistributor redistributor = new AvalancheRedistributor(6);
		int processed = redistributor.redistributeBudget(grid, 1);

		check(processed == 1, "expected one topple event");
		check(grid.getHeight(1, 1) == 0, "pure-vertical mode should full-dump source");
		check(grid.getHeight(2, 2) + grid.getHeight(0, 2) == 8, "all dumped layers should transfer to valid targets");
	}

	private static void testRemainderPrefersVerticalThenCardinal() {
		TestGrid grid = new TestGrid(3, 3);
		grid.setInitialHeight(1, 1, 11);

		// N horizontal valid, E vertical valid, S vertical valid, W blocked.
		grid.setRule(1, 1, 1, 0, AvalancheRedistributor.NeighborState.VALID, 0, 1, 0); // N horizontal
		grid.setRule(1, 1, 2, 1, AvalancheRedistributor.NeighborState.VALID, 0, 2, 2); // E vertical
		grid.setRule(1, 1, 1, 2, AvalancheRedistributor.NeighborState.VALID, 0, 1, 3); // S vertical (off-grid target ok in harness via virtual storage)
		grid.setRule(1, 1, 0, 1, AvalancheRedistributor.NeighborState.BLOCKED, 0, 0, 1);

		AvalancheRedistributor redistributor = new AvalancheRedistributor(6);
		int processed = redistributor.redistributeBudget(grid, 1);

		// Mixed mode => transfer = 11 - 6 = 5, split over 3 candidates => base 1, remainder 2.
		// Remainder should go to vertical first in cardinal order: E then S.
		check(processed == 1, "expected one topple event");
		check(grid.getHeight(1, 1) == 6, "mixed mode should relax source to threshold");
		check(grid.virtualHeight(2, 2) == 2, "E vertical should receive first remainder");
		check(grid.virtualHeight(1, 3) == 2, "S vertical should receive second remainder");
		check(grid.getHeight(1, 0) == 1, "N horizontal keeps base share only");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Harness check failed: " + message);
	}

	private static final class TestGrid implements AvalancheRedistributor.Grid {
		private final int width;
		private final int height;
		private final int[][] heights;
		private final Map<String, Rule> rules = new HashMap<>();
		private final Map<String, Integer> virtualHeights = new HashMap<>();

		private TestGrid(int width, int height) {
			this.width = width;
			this.height = height;
			this.heights = new int[height][width];
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
		public int getHeight(int x, int y) {
			if (inBounds(x, y)) return heights[y][x];
			return virtualHeights.getOrDefault(key(x, y), 0);
		}

		@Override
		public void setHeight(int x, int y, int newHeight) {
			if (!inBounds(x, y)) throw new IllegalStateException("setHeight out of bounds in harness");
			heights[y][x] = newHeight;
		}

		@Override
		public void resolveNeighbor(int sourceX, int sourceY, int neighborX, int neighborY, AvalancheRedistributor.NeighborInfo out) {
			Rule rule = rules.get(key(sourceX, sourceY, neighborX, neighborY));
			if (rule == null) {
				out.set(AvalancheRedistributor.NeighborState.BLOCKED, 0, neighborX, neighborY);
				return;
			}
			out.set(rule.state, rule.deltaHeight, rule.targetX, rule.targetY);
		}

		@Override
		public void addTransferredLayers(int x, int y, int layers) {
			if (layers <= 0) return;
			if (inBounds(x, y)) {
				heights[y][x] += layers;
				return;
			}
			String k = key(x, y);
			virtualHeights.put(k, virtualHeights.getOrDefault(k, 0) + layers);
		}

		private void setInitialHeight(int x, int y, int layers) {
			heights[y][x] = layers;
		}

		private void setRule(int sx, int sy, int nx, int ny, AvalancheRedistributor.NeighborState state, int deltaHeight, int tx, int ty) {
			rules.put(key(sx, sy, nx, ny), new Rule(state, deltaHeight, tx, ty));
		}

		private void setAllNeighborsBlocked(int sx, int sy) {
			for (int[] d : CARDINALS) {
				int nx = sx + d[0];
				int ny = sy + d[1];
				if (!inBounds(nx, ny)) continue;
				setRule(sx, sy, nx, ny, AvalancheRedistributor.NeighborState.BLOCKED, 0, nx, ny);
			}
		}

		private int virtualHeight(int x, int y) {
			return virtualHeights.getOrDefault(key(x, y), 0);
		}

		private boolean inBounds(int x, int y) {
			return x >= 0 && y >= 0 && x < width && y < height;
		}

		private static String key(int sx, int sy, int nx, int ny) {
			return sx + "," + sy + "->" + nx + "," + ny;
		}

		private static String key(int x, int y) {
			return x + "," + y;
		}
	}

	private record Rule(AvalancheRedistributor.NeighborState state, int deltaHeight, int targetX, int targetY) {}
}
