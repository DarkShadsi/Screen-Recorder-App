package com.screenrecorder;

import com.screenrecorder.recorder.ScreenRecorder;
import com.screenrecorder.service.DriveService;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;

public class Main {

    private static ScreenRecorder currentRecorder;
    private static File currentVideoFile;
    private static DriveService driveService;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Screen Recorder & Drive Exporter");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(420, 180);
            frame.setLayout(new FlowLayout(FlowLayout.CENTER, 15, 20));

            JLabel statusLabel = new JLabel("Status: Initializing Drive Service...");
            JButton recordBtn = new JButton("● Start Recording");
            JButton pauseBtn = new JButton("Pause");
            recordBtn.setEnabled(false);
            pauseBtn.setEnabled(false);

            // Initialize Drive Service on background thread to prevent UI lockup during browser OAuth launch
            new Thread(() -> {
                try {
                    driveService = new DriveService();
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Status: Ready to Record");
                        recordBtn.setEnabled(true);
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Auth Error: Check credentials.json"));
                }
            }).start();

            recordBtn.addActionListener(e -> {
                if (recordBtn.getText().contains("Start")) {
                    try {
                        currentVideoFile = new File(System.getProperty("java.io.tmpdir"),
                                "rec_" + System.currentTimeMillis() + ".mp4");

                        currentRecorder = new ScreenRecorder(currentVideoFile);
                        currentRecorder.start();

                        recordBtn.setText("■ Stop & Upload");
                        pauseBtn.setText("Pause");
                        pauseBtn.setEnabled(true);
                        statusLabel.setText("Status: Recording (Unlimited)...");
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(frame, "Recording error: " + ex.getMessage());
                    }
                } else {
                    recordBtn.setEnabled(false);
                    statusLabel.setText("Status: Processing & Uploading to Drive...");

                    new Thread(() -> {
                        try {
                            // 1. Stop recording stream
                            currentRecorder.stop();

                            // disable pause button while processing/uploading
                            SwingUtilities.invokeLater(() -> pauseBtn.setEnabled(false));

                            // 2. Upload to Google Drive and retrieve link
                            String shareUrl = driveService.uploadVideoAndGetLink(currentVideoFile);

                            // 3. Copy link to clipboard
                            Toolkit.getDefaultToolkit().getSystemClipboard()
                                    .setContents(new StringSelection(shareUrl), null);

                            // 4. Clean up local temp file
                            currentVideoFile.delete();

                            SwingUtilities.invokeLater(() -> {
                                recordBtn.setText("● Start Recording");
                                pauseBtn.setText("Pause");
                                recordBtn.setEnabled(true);
                                statusLabel.setText("Done! Link copied to clipboard.");
                                JOptionPane.showMessageDialog(frame,
                                        "Recording Uploaded Successfully!\n\nLink copied to clipboard:\n" + shareUrl);
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            SwingUtilities.invokeLater(() -> {
                                recordBtn.setText("● Start Recording");
                                pauseBtn.setText("Pause");
                                recordBtn.setEnabled(true);
                                statusLabel.setText("Upload Failed!");
                                JOptionPane.showMessageDialog(frame, "Upload failed: " + ex.getMessage());
                            });
                        }
                    }).start();
                }
            });

            pauseBtn.addActionListener(e -> {
                if (currentRecorder == null) return;
                if (pauseBtn.getText().equalsIgnoreCase("Pause")) {
                    currentRecorder.pause();
                    pauseBtn.setText("Resume");
                    statusLabel.setText("Status: Paused");
                } else {
                    currentRecorder.resume();
                    pauseBtn.setText("Pause");
                    statusLabel.setText("Status: Recording (Unlimited)...");
                }
            });

            frame.add(statusLabel);
            frame.add(recordBtn);
            frame.add(pauseBtn);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}