package com.screenrecorder.recorder;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import javax.sound.sampled.*;
import java.awt.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class ScreenRecorder {

    private final File outputFile;
    private FFmpegFrameGrabber desktopGrabber;
    private FFmpegFrameRecorder recorder;
    private TargetDataLine micLine;

    private volatile boolean isRecording = false;
    private volatile boolean isPaused = false;
    // pause bookkeeping to keep timestamps continuous across pauses
    private volatile long pauseStartMillis = 0L;
    private volatile long pausedAccumulatedMillis = 0L;
    private Thread recordThread;

    private int audioChannels = 2;
    private int audioSampleRate = 48000;

    public ScreenRecorder(File outputFile) {
        this.outputFile = outputFile;
    }

    public void start() throws Exception {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = screenSize.width;
        int height = screenSize.height;
        int frameRate = 30;

        // 1. Configure Screen Capture
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            desktopGrabber = new FFmpegFrameGrabber("desktop");
            desktopGrabber.setFormat("gdigrab");
        } else if (osName.contains("mac")) {
            desktopGrabber = new FFmpegFrameGrabber("1:none");
            desktopGrabber.setFormat("avfoundation");
        } else {
            desktopGrabber = new FFmpegFrameGrabber(":0.0");
            desktopGrabber.setFormat("x11grab");
        }

        desktopGrabber.setFrameRate(frameRate);
        desktopGrabber.setImageWidth(width);
        desktopGrabber.setImageHeight(height);
        desktopGrabber.start();

        // 2. Dynamic Audio Line Setup (Supports standard Windows 48kHz Stereo / 44.1kHz Stereo)
        setupAudioLine();

        // 3. Configure Output Video File Encoder
        recorder = new FFmpegFrameRecorder(outputFile, width, height, micLine != null ? audioChannels : 0);
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("mp4");
        recorder.setFrameRate(frameRate);
        recorder.setVideoBitrate(2000000); // 2 Mbps

        if (micLine != null) {
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.setSampleRate(audioSampleRate);
            recorder.setAudioChannels(audioChannels);
            recorder.setAudioBitrate(128000); // 128 kbps audio
        }
        recorder.start();

        isRecording = true;

        // 4. Start Capture Loop
        recordThread = new Thread(this::captureLoop, "ScreenRecorder-Thread");
        recordThread.start();
    }

    /**
     * Pause the recording. While paused, frames and audio will not be written to the output
     * and internal timestamps will be adjusted so the resulting file has continuous timing.
     */
    public void pause() {
        if (!isRecording || isPaused) return;
        isPaused = true;
        pauseStartMillis = System.currentTimeMillis();
        System.out.println("Recording paused");
    }

    /**
     * Resume recording after a pause.
     */
    public void resume() {
        if (!isRecording || !isPaused) return;
        // accumulate paused duration so timestamps don't jump
        pausedAccumulatedMillis += (System.currentTimeMillis() - pauseStartMillis);
        pauseStartMillis = 0L;
        isPaused = false;
        System.out.println("Recording resumed");
    }

    public boolean isPaused() {
        return isPaused;
    }

    private void setupAudioLine() {
        // Fallback formats list (48kHz Stereo -> 44.1kHz Stereo -> 44.1kHz Mono)
        AudioFormat[] formatsToTry = new AudioFormat[]{
                new AudioFormat(48000.0f, 16, 2, true, false), // 48kHz Stereo (Standard Windows)
                new AudioFormat(44100.0f, 16, 2, true, false), // 44.1kHz Stereo
                new AudioFormat(44100.0f, 16, 1, true, false)  // 44.1kHz Mono
        };

        for (AudioFormat format : formatsToTry) {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (AudioSystem.isLineSupported(info)) {
                    micLine = (TargetDataLine) AudioSystem.getLine(info);
                    micLine.open(format);
                    micLine.start();
                    this.audioSampleRate = (int) format.getSampleRate();
                    this.audioChannels = format.getChannels();
                    System.out.println("Audio line initialized successfully: " + format);
                    return;
                }
            } catch (Exception ignored) {
                // Try next format in line
            }
        }
        System.out.println("No compatible microphone audio format found. Recording screen only.");
        micLine = null;
    }

    private void captureLoop() {
        long startTime = System.currentTimeMillis();
        byte[] audioBuffer = new byte[4096];

        while (isRecording) {
            try {
                // If paused, do not grab frames or audio but keep loop alive
                if (isPaused) {
                    Thread.sleep(100);
                    continue;
                }

                // Grab screen frame
                Frame videoFrame = desktopGrabber.grab();
                if (videoFrame != null) {
                    long timeStamp = (System.currentTimeMillis() - startTime - pausedAccumulatedMillis) * 1000;
                    if (timeStamp < 0) timeStamp = 0;
                    recorder.setTimestamp(timeStamp);
                    recorder.record(videoFrame);
                }

                // Grab audio samples
                if (micLine != null && micLine.available() > 0) {
                    int bytesRead = micLine.read(audioBuffer, 0, Math.min(micLine.available(), audioBuffer.length));
                    if (bytesRead > 0) {
                        short[] samples = new short[bytesRead / 2];
                        ByteBuffer.wrap(audioBuffer, 0, bytesRead)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(samples);

                        recorder.recordSamples(audioSampleRate, audioChannels, ShortBuffer.wrap(samples));
                    }
                }

                Thread.sleep(1000 / 30);
            } catch (Exception e) {
                if (isRecording) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void stop() throws Exception {
        isRecording = false;

        if (recordThread != null) {
            recordThread.join();
        }
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }
        if (recorder != null) {
            recorder.stop();
            recorder.release();
        }
        if (desktopGrabber != null) {
            desktopGrabber.stop();
            desktopGrabber.release();
        }
    }
}