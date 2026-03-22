package com.mapgrow.processing;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.function.Consumer;

public class SeaColorProcessor {

    private static final int WEIGHT_CARDINAL = 1000;
    private static final int WEIGHT_DIAGONAL = 707;

    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] WEIGHTS = {
            WEIGHT_DIAGONAL, WEIGHT_CARDINAL, WEIGHT_DIAGONAL,
            WEIGHT_CARDINAL, WEIGHT_CARDINAL,
            WEIGHT_DIAGONAL, WEIGHT_CARDINAL, WEIGHT_DIAGONAL
    };

    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / 60;

    private volatile boolean cancelled = false;

    public void cancel() {
        cancelled = true;
    }

    public void process(int[] sourcePixels, int width, int height,
                        BufferedImage overlay,
                        Consumer<String> statusCallback,
                        Runnable repaintCallback) {

        int size = width * height;
        int[] imgData = ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData();
        System.arraycopy(sourcePixels, 0, imgData, 0, size);

        if (repaintCallback != null) repaintCallback.run();

        // Frontier as flat array of linear indices + dedup boolean[]
        int[] frontier = new int[size];
        boolean[] inFrontier = new boolean[size];
        int frontierSize = 0;

        int totalSea = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (imgData[idx] == 0) {
                    totalSea++;
                    if (hasNeighbor(imgData, x, y, width, height)) {
                        frontier[frontierSize++] = idx;
                        inFrontier[idx] = true;
                    }
                }
            }
        }

        int[] pendingIdx = new int[size];
        int[] pendingColor = new int[size];
        int iteration = 0;
        int totalColored = 0;
        long lastRepaint = System.nanoTime();

        while (frontierSize > 0 && !cancelled) {
            iteration++;
            int pendingSize = 0;

            for (int f = 0; f < frontierSize; f++) {
                int idx = frontier[f];
                int x = idx % width, y = idx / width;
                int color = computeColor(imgData, x, y, width, height);
                if (color != 0) {
                    pendingIdx[pendingSize] = idx;
                    pendingColor[pendingSize] = color;
                    pendingSize++;
                }
            }

            Arrays.fill(inFrontier, false);
            int nextSize = 0;

            for (int p = 0; p < pendingSize; p++) {
                int idx = pendingIdx[p];
                imgData[idx] = pendingColor[p];
                totalColored++;
                int x = idx % width, y = idx / width;

                for (int n = 0; n < 8; n++) {
                    int nx = x + DX[n], ny = y + DY[n];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int nIdx = ny * width + nx;
                    if (imgData[nIdx] == 0 && !inFrontier[nIdx]) {
                        frontier[nextSize++] = nIdx;
                        inFrontier[nIdx] = true;
                    }
                }
            }
            frontierSize = nextSize;

            // 60fps throttle
            long now = System.nanoTime();
            if (now - lastRepaint >= FRAME_INTERVAL_NS || frontierSize == 0) {
                if (statusCallback != null) {
                    statusCallback.accept("Iteration " + iteration + ": " + totalColored + "/" + totalSea);
                }
                if (repaintCallback != null) {
                    repaintCallback.run();
                }
                lastRepaint = now;
            }
        }
    }

    private static int computeColor(int[] pixels, int x, int y, int w, int h) {
        int totalR = 0, totalG = 0, totalB = 0, totalWeight = 0;
        for (int n = 0; n < 8; n++) {
            int nx = x + DX[n], ny = y + DY[n];
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
            int c = pixels[ny * w + nx];
            if (c == 0) continue;
            int wt = WEIGHTS[n];
            totalR += ((c >> 16) & 0xFF) * wt;
            totalG += ((c >> 8) & 0xFF) * wt;
            totalB += (c & 0xFF) * wt;
            totalWeight += wt;
        }
        if (totalWeight > 0) {
            return (0xFF << 24)
                    | ((totalR / totalWeight) << 16)
                    | ((totalG / totalWeight) << 8)
                    | (totalB / totalWeight);
        }
        return 0;
    }

    private static boolean hasNeighbor(int[] pixels, int x, int y, int w, int h) {
        for (int n = 0; n < 8; n++) {
            int nx = x + DX[n], ny = y + DY[n];
            if (nx >= 0 && nx < w && ny >= 0 && ny < h && pixels[ny * w + nx] != 0)
                return true;
        }
        return false;
    }
}
