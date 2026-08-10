package com.screenrecorder;

import javax.swing.*;
import java.awt.*;

public class Main {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Screen Recorder & Drive Exporter");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(350, 180);
            frame.setLayout(new FlowLayout(FlowLayout.CENTER, 15, 20));

            JLabel statusLabel = new JLabel("Status: Ready to Record");
            JButton recordBtn = new JButton("● Start Recording");

            recordBtn.addActionListener(e -> {
                if (recordBtn.getText().contains("Start")) {
                    recordBtn.setText("■ Stop & Upload");
                    statusLabel.setText("Status: Recording...");
                } else {
                    recordBtn.setText("● Start Recording");
                    statusLabel.setText("Status: Uploading to Drive...");
                }
            });

            frame.add(statusLabel);
            frame.add(recordBtn);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}