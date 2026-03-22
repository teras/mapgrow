package com.mapgrow;

import com.formdev.flatlaf.FlatLightLaf;
import com.mapgrow.geo.CountryStore;
import com.mapgrow.map.MapPanel;
import com.mapgrow.processing.ProcessingTask;
import com.mapgrow.ui.ControlPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class MapGrowApp extends JFrame {

    private final MapPanel mapPanel;
    private final ControlPanel controlPanel;
    private final CountryStore countryStore;
    private ProcessingTask currentTask;

    public MapGrowApp() {
        super("MapGrow");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        countryStore = new CountryStore();
        mapPanel = new MapPanel();

        controlPanel = new ControlPanel();
        controlPanel.addStartListener(e -> startProcessing());
        controlPanel.addStopListener(e -> stopProcessing());
        controlPanel.addResetListener(e -> resetView());
        controlPanel.addViewModeListener(mapPanel::setViewMode);
        mapPanel.setZoomChangeListener(() -> controlPanel.setZoom(mapPanel.getZoom()));

        setLayout(new BorderLayout());
        add(mapPanel, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.SOUTH);

        setSize(1024, 768);
        setLocationRelativeTo(null);

        SwingWorker<Void, Void> loader = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                countryStore.load();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    mapPanel.setCountryStore(countryStore);
                    controlPanel.setStatus("Ready - " + countryStore.getAllCountries().size() + " countries loaded");
                } catch (Exception ex) {
                    controlPanel.setStatus("Error: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        };
        loader.execute();
    }

    private void startProcessing() {
        int width = mapPanel.getWidth();
        int height = mapPanel.getHeight();

        // Build initial pixel array from the already-computed overlay tiles
        int[] pixels = mapPanel.buildViewportPixels(width, height);

        mapPanel.setInteractionEnabled(false);
        controlPanel.setProcessing(true);

        // Shared overlay for expansion
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        mapPanel.setExpansionOverlay(overlay);

        currentTask = new ProcessingTask(
                pixels, width, height, overlay,
                controlPanel::setStatus,
                mapPanel::repaint);

        currentTask.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName()) &&
                    SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                controlPanel.setProcessing(false);
                controlPanel.setStatus("Done!");
            }
        });

        currentTask.execute();
    }

    private void stopProcessing() {
        if (currentTask != null) {
            currentTask.stop();
            controlPanel.setProcessing(false);
            controlPanel.setStatus("Stopped");
        }
    }

    private void resetView() {
        mapPanel.clearExpansionOverlay();
        mapPanel.setInteractionEnabled(true);
        controlPanel.reset();
        currentTask = null;
    }

    public static void main(String[] args) {
        FlatLightLaf.setup();
        SwingUtilities.invokeLater(() -> new MapGrowApp().setVisible(true));
    }
}
