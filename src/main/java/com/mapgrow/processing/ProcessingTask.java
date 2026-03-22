package com.mapgrow.processing;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

public class ProcessingTask extends SwingWorker<Void, String> {

    private final int[] pixels;
    private final int width, height;
    private final BufferedImage overlay;
    private final Consumer<String> statusCallback;
    private final Runnable repaintCallback;
    private final SeaColorProcessor processor = new SeaColorProcessor();

    public ProcessingTask(int[] pixels, int width, int height,
                          BufferedImage overlay,
                          Consumer<String> statusCallback,
                          Runnable repaintCallback) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.overlay = overlay;
        this.statusCallback = statusCallback;
        this.repaintCallback = repaintCallback;
    }

    @Override
    protected Void doInBackground() {
        processor.process(pixels, width, height, overlay,
                this::publishStatus,
                () -> SwingUtilities.invokeLater(repaintCallback));
        return null;
    }

    public void stop() {
        processor.cancel();
        cancel(false);
    }

    private void publishStatus(String status) {
        publish(status);
    }

    @Override
    protected void process(List<String> chunks) {
        if (!chunks.isEmpty() && statusCallback != null) {
            statusCallback.accept(chunks.getLast());
        }
    }
}
