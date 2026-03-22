package com.mapgrow.ui;

import com.mapgrow.map.MapPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

public class ControlPanel extends JPanel {

    private final JButton startButton;
    private final JButton stopButton;
    private final JButton resetButton;
    private final JLabel statusLabel;

    private final JLabel zoomLabel;
    private final JRadioButton rbMapOverlay;
    private final JRadioButton rbLandSea;
    private final JRadioButton rbLandSeaColored;
    private final JRadioButton rbNaturalEarth;

    public ControlPanel() {
        super(new BorderLayout(8, 4));
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        // Top row: buttons + status
        JPanel topRow = new JPanel(new BorderLayout(8, 0));
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        resetButton = new JButton("Reset");
        stopButton.setEnabled(false);
        resetButton.setEnabled(false);
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(resetButton);

        statusLabel = new JLabel("Ready");
        zoomLabel = new JLabel("Zoom: 6");
        topRow.add(buttonPanel, BorderLayout.WEST);
        topRow.add(statusLabel, BorderLayout.CENTER);
        topRow.add(zoomLabel, BorderLayout.EAST);

        // Bottom row: view mode radio buttons
        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        ButtonGroup group = new ButtonGroup();
        rbMapOverlay = new JRadioButton("Map + Overlay", true);
        rbLandSea = new JRadioButton("Land/Sea");
        rbLandSeaColored = new JRadioButton("Land/Sea Colored");
        rbNaturalEarth = new JRadioButton("Natural Earth");
        group.add(rbMapOverlay);
        group.add(rbLandSea);
        group.add(rbLandSeaColored);
        group.add(rbNaturalEarth);
        radioPanel.add(rbMapOverlay);
        radioPanel.add(rbLandSea);
        radioPanel.add(rbLandSeaColored);
        radioPanel.add(rbNaturalEarth);

        add(topRow, BorderLayout.NORTH);
        add(radioPanel, BorderLayout.SOUTH);
    }

    public void addStartListener(ActionListener l) { startButton.addActionListener(l); }
    public void addStopListener(ActionListener l) { stopButton.addActionListener(l); }
    public void addResetListener(ActionListener l) { resetButton.addActionListener(l); }

    public void addViewModeListener(Consumer<MapPanel.ViewMode> listener) {
        rbMapOverlay.addActionListener(e -> listener.accept(MapPanel.ViewMode.MAP_WITH_OVERLAY));
        rbLandSea.addActionListener(e -> listener.accept(MapPanel.ViewMode.LAND_SEA));
        rbLandSeaColored.addActionListener(e -> listener.accept(MapPanel.ViewMode.LAND_SEA_COLORED));
        rbNaturalEarth.addActionListener(e -> listener.accept(MapPanel.ViewMode.NATURAL_EARTH));
    }

    public void setStatus(String status) { statusLabel.setText(status); }
    public void setZoom(int zoom) { zoomLabel.setText("Zoom: " + zoom); }

    public void setProcessing(boolean processing) {
        startButton.setEnabled(!processing);
        stopButton.setEnabled(processing);
        if (!processing) resetButton.setEnabled(true);
    }

    public void reset() {
        resetButton.setEnabled(false);
        stopButton.setEnabled(false);
        startButton.setEnabled(true);
        statusLabel.setText("Ready");
    }
}
