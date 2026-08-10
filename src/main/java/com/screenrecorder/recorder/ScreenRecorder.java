package com.screenrecorder.recorder;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.awt.*;
import java.io.File;

public class ScreenRecorder {

    private final File outputFile;
    private final String audioDeviceName;
    private FFmpegFrameGrabber desktopGrabber;
    private FFmpegFrameGrabber audioGrabber;
    private FFmpegFrameRecorder recorder;

    private volatile boolean isRecording = false;
    private Thread videoThread;
    private Thread audioThread;

    /**
     * @param outputFile      destination MP4 file
     * @param audioDeviceName DirectShow audio device name (e.g. "Microphone Array (...)"),
     *                        or empty/null to skip audio capture (see {@link #NO_AUDIO}).
     */
    public ScreenRecorder(File outputFile) {
        this(outputFile, resolveAudioDevice());
    }

    public ScreenRecorder(File outputFile, String audioDeviceName) {
        this.outputFile = outputFile;
        this.audioDeviceName = audioDeviceName;
    }

    /** Sentinel meaning "do not record audio". Used by the no-arg constructor default. */
    public static final String NO_AUDIO = "";

    /**
     * Picks the audio device. Priority:
     * <ol>
     *   <li>System property {@code screenrecorder.audio} or env var {@code SCREENRECORDER_AUDIO}
     *       if set and non-empty.</li>
     *   <li>{@link #NO_AUDIO} — recording proceeds without audio. Run {@code TestAudioDevices}
     *       to discover device names, then pass yours in via the property above.</li>
     * </ol>
     * This avoids hard-coding a device that won't exist on other machines.
     */
    private static String resolveAudioDevice() {
        String override = System.getProperty("screenrecorder.audio");
        if (override == null || override.isEmpty()) {
            override = System.getenv("SCREENRECORDER_AUDIO");
        }
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return NO_AUDIO;
    }

    public void start() throws Exception {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = screenSize.width;
        int height = screenSize.height;
        int frameRate = 30;

        // 1. Configure Video Grabber (GDI Display Capture)
        desktopGrabber = new FFmpegFrameGrabber("desktop");
        desktopGrabber.setFormat("gdigrab");
        desktopGrabber.setFrameRate(frameRate);
        desktopGrabber.setImageWidth(width);
        desktopGrabber.setImageHeight(height);
        desktopGrabber.start();

        // 2. Configure Audio Grabber using Windows DirectShow (Same subsystem as OBS).
        //    Skip entirely if the user selected "No Audio".
        boolean hasAudio = false;
        if (audioDeviceName != null && !audioDeviceName.isEmpty()) {
            try {
                audioGrabber = new FFmpegFrameGrabber("audio=" + audioDeviceName);
                audioGrabber.setFormat("dshow");
                audioGrabber.start();
                hasAudio = true;
                System.out.println("DirectShow Audio Device connected: " + audioDeviceName);
            } catch (Exception e) {
                System.out.println("DirectShow audio init warning (" + audioDeviceName + "): " + e.getMessage());
                audioGrabber = null;
            }
        } else {
            System.out.println("Audio capture disabled by user selection.");
        }

        // 3. Configure File Output Recorder
        recorder = new FFmpegFrameRecorder(outputFile, width, height, hasAudio ? 2 : 0);
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("mp4");
        recorder.setFrameRate(frameRate);
        recorder.setVideoBitrate(2000000);

        if (hasAudio) {
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.setSampleRate(44100);
            recorder.setAudioBitrate(128000);
        }

        recorder.start();
        isRecording = true;

        // 4. Start Separate Capture Threads.
        //    FFmpegFrameRecorder.setTimestamp(long) takes microseconds and the
        //    recorder derives the frame number from (timestamp * frameRate / 1_000_000).
        //    Set timestamps on video frames only; let FFmpeg auto-PTS the audio
        //    stream from its sample rate, which is the supported pattern.
        final long startTime = System.nanoTime();

        // Prime the audio grabber: discard its first few frames so it has
        // a stable clock before we start recording.
        if (hasAudio) {
            try {
                for (int i = 0; i < 5; i++) {
                    audioGrabber.grab();
                }
            } catch (Exception ignored) {}
        }

        videoThread = new Thread(() -> {
            while (isRecording) {
                try {
                    Frame videoFrame = desktopGrabber.grab();
                    if (videoFrame != null) {
                        long ptsMicros = (System.nanoTime() - startTime) / 1000L;
                        synchronized (recorder) {
                            recorder.setTimestamp(ptsMicros);
                            recorder.record(videoFrame);
                        }
                    }
                    Thread.sleep(1000 / frameRate);
                } catch (Exception ignored) {}
            }
        }, "Video-Grabber-Thread");

        if (hasAudio) {
            audioThread = new Thread(() -> {
                while (isRecording) {
                    try {
                        Frame audioFrame = audioGrabber.grab();
                        if (audioFrame != null) {
                            synchronized (recorder) {
                                // No setTimestamp here — recorder auto-derives audio PTS.
                                recorder.record(audioFrame);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }, "Audio-Grabber-Thread");
            audioThread.start();
        }

        videoThread.start();
    }

    public void stop() throws Exception {
        isRecording = false;

        if (videoThread != null) videoThread.join();
        if (audioThread != null) audioThread.join();

        if (recorder != null) {
            recorder.stop();
            recorder.release();
        }
        if (desktopGrabber != null) {
            desktopGrabber.stop();
            desktopGrabber.release();
        }
        if (audioGrabber != null) {
            audioGrabber.stop();
            audioGrabber.release();
        }
    }
}