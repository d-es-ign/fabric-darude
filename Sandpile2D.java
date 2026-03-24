public final class Sandpile2D {
    private final int width;
    private final int height;
    private final int size;

    // Cell heights (grain counts)
    private final int[] h;

    // Precomputed neighbors: 4 entries per cell (up, down, left, right), -1 means sink/outside
    // Memory cost: 16 bytes per cell (4 ints).
    private final int[] nbr;

    // Ring queue of active cells (cells with h[i] >= 4)
    private int[] q;
    private int qHead = 0;
    private int qTail = 0;
    private final boolean[] inQueue;

    public Sandpile2D(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid size");
        this.width = width;
        this.height = height;
        this.size = width * height;

        this.h = new int[size];
        this.nbr = new int[size << 2];
        this.q = new int[Math.max(16, Integer.highestOneBit(size) << 1)];
        this.inQueue = new boolean[size];

        buildNeighbors();
    }

    private void buildNeighbors() {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                int base = i << 2;
                nbr[base] = (y > 0) ? (i - width) : -1; // up
                nbr[base + 1] = (y + 1 < height) ? (i + width) : -1; // down
                nbr[base + 2] = (x > 0) ? (i - 1) : -1; // left
                nbr[base + 3] = (x + 1 < width) ? (i + 1) : -1; // right
            }
        }
    }

    public int get(int x, int y) {
        checkBounds(x, y);
        return h[idx(x, y)];
    }

    public void add(int x, int y, int grains) {
        if (grains <= 0) return;
        checkBounds(x, y);
        int i = idx(x, y);
        h[i] += grains;
        if (h[i] >= 4) enqueue(i);
    }

    // Process up to maxTopples toppling events; returns number processed.
    // Call this each game tick with a fixed budget.
    public int relaxBudget(int maxTopples) {
        int processed = 0;

        while (processed < maxTopples && qHead != qTail) {
            int i = dequeue();
            int v = h[i];
            if (v < 4) continue;

            // Batch topples: if v=27 => qTopples=6 in one step.
            int qTopples = v >>> 2; // v / 4
            h[i] = v - (qTopples << 2); // v % 4

            int base = i << 2;
            for (int k = 0; k < 4; k++) {
                int n = nbr[base + k];
                if (n < 0) continue; // sink boundary
                int nv = h[n] + qTopples;
                h[n] = nv;
                if (nv >= 4) enqueue(n);
            }

            processed++;
        }

        return processed;
    }

    public boolean isStable() {
        return qHead == qTail;
    }

    public int[] rawHeights() {
        return java.util.Arrays.copyOf(h, h.length);
    }

    private int idx(int x, int y) {
        return y * width + x;
    }

    private void checkBounds(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException("x=" + x + ", y=" + y);
        }
    }

    private void enqueue(int i) {
        if (inQueue[i]) return;
        inQueue[i] = true;

        int next = (qTail + 1) & (q.length - 1);
        if (next == qHead) growQueue();

        q[qTail] = i;
        qTail = (qTail + 1) & (q.length - 1);
    }

    private int dequeue() {
        int i = q[qHead];
        qHead = (qHead + 1) & (q.length - 1);
        inQueue[i] = false;
        return i;
    }

    // q length is always power-of-two
    private void growQueue() {
        int oldLen = q.length;
        int[] n = new int[oldLen << 1];

        int count = (qTail - qHead + oldLen) & (oldLen - 1);
        for (int j = 0; j < count; j++) {
            n[j] = q[(qHead + j) & (oldLen - 1)];
        }

        q = n;
        qHead = 0;
        qTail = count;
    }
}
