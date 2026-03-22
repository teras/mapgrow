package com.mapgrow.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class ResultDialog extends JDialog {

    public ResultDialog(Frame owner, BufferedImage image) {
        super(owner, "MapGrow Result", false);

        JLabel imageLabel = new JLabel(new ImageIcon(image));
        JScrollPane scrollPane = new JScrollPane(imageLabel);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(image.getWidth() + 20, 1200),
                Math.min(image.getHeight() + 20, 800)));

        JButton saveButton = new JButton("Save PNG");
        saveButton.addActionListener(e -> saveImage(image));

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(saveButton);

        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
    }

    private void saveImage(BufferedImage image) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("mapgrow_result.png"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ImageIO.write(image, "PNG", chooser.getSelectedFile());
                JOptionPane.showMessageDialog(this, "Image saved!");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error saving: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
