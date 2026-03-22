package com.mapgrow.benchmark;

import java.util.Arrays;

/**
 * Benchmark: compare approaches for the sea-fill algorithm.
 * Synthetic test: circle of land in center, rest is sea.
 */
public class GrowBenchmark {

    static final int W = 1920;
    static final int H = 1080;
    static final int SIZE = W * H;

    // Cardinal = 1000, Diagonal = 707 (fixed-point ×1000)
    static final int WEIGHT_CARDINAL = 1000;
    static final int WEIGHT_DIAGONAL = 707;

    static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};
    static final int[] WEIGHT_I = {
            WEIGHT_DIAGONAL, WEIGHT_CARDINAL, WEIGHT_DIAGONAL,
            WEIGHT_CARDINAL, WEIGHT_CARDINAL,
            WEIGHT_DIAGONAL, WEIGHT_CARDINAL, WEIGHT_DIAGONAL
    };

    // Double weights for comparison
    static final double DIAGONAL_WEIGHT = 1.0 / Math.sqrt(2.0);
    static final double[] WEIGHT_D = {
            DIAGONAL_WEIGHT, 1.0, DIAGONAL_WEIGHT,
            1.0, 1.0,
            DIAGONAL_WEIGHT, 1.0, DIAGONAL_WEIGHT
    };

    public static void main(String[] args) {
        int landPixels = 0;
        int[] test = createTestMap();
        for (int p : test) if (p != 0) landPixels++;
        System.out.println("Image: " + W + "x" + H + " = " + SIZE + " pixels");
        System.out.println("Land:  " + landPixels + "  Sea: " + (SIZE - landPixels));
        System.out.println();

        System.out.println("Warming up...");
        for (int i = 0; i < 3; i++) {
            frontierCurrent(createTestMap());
            frontierOptimized(createTestMap());
            fullScan(createTestMap());
        }
        System.out.println();

        int runs = 5;
        long[] tFull = new long[runs], tCurrent = new long[runs], tOpt = new long[runs];

        for (int r = 0; r < runs; r++) {
            {
                int[] map = createTestMap();
                long t0 = System.nanoTime();
                int iters = fullScan(map);
                tFull[r] = System.nanoTime() - t0;
                if (r == 0) System.out.println("Full scan:         " + iters + " iterations");
            }
            {
                int[] map = createTestMap();
                long t0 = System.nanoTime();
                int iters = frontierCurrent(map);
                tCurrent[r] = System.nanoTime() - t0;
                if (r == 0) System.out.println("Frontier (current): " + iters + " iterations");
            }
            {
                int[] map = createTestMap();
                long t0 = System.nanoTime();
                int iters = frontierOptimized(map);
                tOpt[r] = System.nanoTime() - t0;
                if (r == 0) System.out.println("Frontier (optimized): " + iters + " iterations");
            }
        }

        System.out.println();
        printStats("Full scan           ", tFull);
        printStats("Frontier (current)  ", tCurrent);
        printStats("Frontier (optimized)", tOpt);
    }

    static int[] createTestMap() {
        int[] pixels = new int[SIZE];
        int cx = W / 2, cy = H / 2;
        int radius = Math.min(W, H) / 8;
        int r2 = radius * radius;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy < r2) {
                    pixels[y * W + x] = 0xFFFF0000;
                }
            }
        }
        return pixels;
    }

    // ─── Full scan (baseline) ───────────────────────────────────────

    static int fullScan(int[] pixels) {
        int[] buffer = new int[SIZE];
        int iteration = 0;
        boolean changed;
        do {
            changed = false;
            iteration++;
            Arrays.fill(buffer, 0);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    if (pixels[y * W + x] != 0) continue;
                    int color = computeColorDouble(pixels, x, y);
                    if (color != 0) { buffer[y * W + x] = color; changed = true; }
                }
            }
            for (int i = 0; i < SIZE; i++) {
                if (buffer[i] != 0) pixels[i] = buffer[i];
            }
        } while (changed);
        return iteration;
    }

    // ─── Current frontier (Arrays.fill + double math) ───────────────

    static int frontierCurrent(int[] pixels) {
        int[] frontier = new int[SIZE];
        boolean[] inFrontier = new boolean[SIZE];
        int frontierSize = 0;

        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int idx = y * W + x;
                if (pixels[idx] == 0 && hasNeighbor(pixels, x, y))
                    { frontier[frontierSize++] = idx; inFrontier[idx] = true; }
            }

        int[] pendingIdx = new int[SIZE];
        int[] pendingColor = new int[SIZE];
        int iteration = 0;

        while (frontierSize > 0) {
            iteration++;
            int pendingSize = 0;
            for (int f = 0; f < frontierSize; f++) {
                int idx = frontier[f];
                int color = computeColorDouble(pixels, idx % W, idx / W);
                if (color != 0) { pendingIdx[pendingSize] = idx; pendingColor[pendingSize] = color; pendingSize++; }
            }

            Arrays.fill(inFrontier, false); // <-- wasteful: clears 2M every iteration
            int nextSize = 0;
            for (int p = 0; p < pendingSize; p++) {
                int idx = pendingIdx[p];
                pixels[idx] = pendingColor[p];
                int x = idx % W, y = idx / W;
                for (int n = 0; n < 8; n++) {
                    int nx = x + DX[n], ny = y + DY[n];
                    if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue;
                    int nIdx = ny * W + nx;
                    if (pixels[nIdx] == 0 && !inFrontier[nIdx])
                        { frontier[nextSize++] = nIdx; inFrontier[nIdx] = true; }
                }
            }
            frontierSize = nextSize;
        }
        return iteration;
    }

    // ─── Optimized frontier ─────────────────────────────────────────
    // 1) Clear only used entries in inFrontier
    // 2) Integer fixed-point arithmetic (no double, no Math.round)
    // 3) Avoid idx % W and idx / W — store x,y in frontier

    static int frontierOptimized(int[] pixels) {
        // Frontier stores packed x,y pairs: frontier[i*2]=x, frontier[i*2+1]=y
        int[] frontier = new int[SIZE * 2];
        boolean[] inFrontier = new boolean[SIZE];
        int frontierSize = 0;

        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int idx = y * W + x;
                if (pixels[idx] == 0 && hasNeighbor(pixels, x, y)) {
                    frontier[frontierSize * 2] = x;
                    frontier[frontierSize * 2 + 1] = y;
                    frontierSize++;
                    inFrontier[idx] = true;
                }
            }

        int[] pendingIdx = new int[SIZE];
        int[] pendingColor = new int[SIZE];
        // Track which inFrontier entries to clear (avoid Arrays.fill)
        int[] toClear = new int[SIZE];
        int iteration = 0;

        while (frontierSize > 0) {
            iteration++;
            int pendingSize = 0;

            for (int f = 0; f < frontierSize; f++) {
                int x = frontier[f * 2], y = frontier[f * 2 + 1];
                int color = computeColorInt(pixels, x, y);
                if (color != 0) {
                    pendingIdx[pendingSize] = y * W + x;
                    pendingColor[pendingSize] = color;
                    pendingSize++;
                }
            }

            // Clear only the entries we used
            for (int f = 0; f < frontierSize; f++) {
                inFrontier[frontier[f * 2 + 1] * W + frontier[f * 2]] = false;
            }

            int nextSize = 0;
            int clearSize = 0;
            for (int p = 0; p < pendingSize; p++) {
                int idx = pendingIdx[p];
                pixels[idx] = pendingColor[p];
                int x = idx % W, y = idx / W;
                for (int n = 0; n < 8; n++) {
                    int nx = x + DX[n], ny = y + DY[n];
                    if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue;
                    int nIdx = ny * W + nx;
                    if (pixels[nIdx] == 0 && !inFrontier[nIdx]) {
                        frontier[nextSize * 2] = nx;
                        frontier[nextSize * 2 + 1] = ny;
                        nextSize++;
                        inFrontier[nIdx] = true;
                    }
                }
            }
            frontierSize = nextSize;
        }
        return iteration;
    }

    // ─── Color computation ──────────────────────────────────────────

    static int computeColorDouble(int[] pixels, int x, int y) {
        double totalR = 0, totalG = 0, totalB = 0, totalWeight = 0;
        for (int n = 0; n < 8; n++) {
            int nx = x + DX[n], ny = y + DY[n];
            if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue;
            int c = pixels[ny * W + nx];
            if (c == 0) continue;
            double w = WEIGHT_D[n];
            totalR += ((c >> 16) & 0xFF) * w;
            totalG += ((c >> 8) & 0xFF) * w;
            totalB += (c & 0xFF) * w;
            totalWeight += w;
        }
        if (totalWeight > 0) {
            int r = (int) Math.round(totalR / totalWeight);
            int g = (int) Math.round(totalG / totalWeight);
            int b = (int) Math.round(totalB / totalWeight);
            return (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        return 0;
    }

    static int computeColorInt(int[] pixels, int x, int y) {
        int totalR = 0, totalG = 0, totalB = 0, totalWeight = 0;
        for (int n = 0; n < 8; n++) {
            int nx = x + DX[n], ny = y + DY[n];
            if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue;
            int c = pixels[ny * W + nx];
            if (c == 0) continue;
            int w = WEIGHT_I[n];
            totalR += ((c >> 16) & 0xFF) * w;
            totalG += ((c >> 8) & 0xFF) * w;
            totalB += (c & 0xFF) * w;
            totalWeight += w;
        }
        if (totalWeight > 0) {
            int r = totalR / totalWeight;
            int g = totalG / totalWeight;
            int b = totalB / totalWeight;
            return (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        return 0;
    }

    static boolean hasNeighbor(int[] pixels, int x, int y) {
        for (int n = 0; n < 8; n++) {
            int nx = x + DX[n], ny = y + DY[n];
            if (nx >= 0 && nx < W && ny >= 0 && ny < H && pixels[ny * W + nx] != 0)
                return true;
        }
        return false;
    }

    static void printStats(String label, long[] times) {
        Arrays.sort(times);
        long median = times[times.length / 2];
        long min = times[0];
        System.out.printf("%s  median: %5d ms  min: %5d ms%n",
                label, median / 1_000_000, min / 1_000_000);
    }
}
