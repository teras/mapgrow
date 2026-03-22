package com.mapgrow.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

public class ControlPanel extends JPanel {

    private final JButton startButton;
    private final JButton stopButton;
    private final JButton resetButton;
    private final JLabel statusLabel;

    public ControlPanel() {
        super(new BorderLayout(8, 0));
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

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

        add(buttonPanel, BorderLayout.WEST);
        add(statusLabel, BorderLayout.CENTER);
    }

    public void addStartListener(ActionListener l) { startButton.addActionListener(l); }
    public void addStopListener(ActionListener l) { stopButton.addActionListener(l); }
    public void addResetListener(ActionListener l) { resetButton.addActionListener(l); }

    public void setStatus(String status) { statusLabel.setText(status); }

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
