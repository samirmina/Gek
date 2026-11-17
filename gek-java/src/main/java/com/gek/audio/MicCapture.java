package com.gek.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Microphone capture via Java Sound API. Produces 16kHz mono float chunks.
 * Note: system/loopback capture on Windows typically requires WASAPI (JNA) - handled separately later.
 */
public class MicCapture implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MicCapture.class);
    private final int sampleRate;
    private final float chunkSeconds;
    private final String deviceNameHint;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private TargetDataLine line;
    private Consumer<AudioChunk> consumer;

    public MicCapture(int sampleRate, float chunkSeconds, String deviceNameHint) {
        this.sampleRate = sampleRate;
        this.chunkSeconds = chunkSeconds;
        this.deviceNameHint = deviceNameHint;
    }

    public void setConsumer(Consumer<AudioChunk> consumer) { this.consumer = consumer; }
    public boolean isRunning() { return running.get(); }

    public void start() throws LineUnavailableException {
        if (running.get()) return;
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        Mixer.Info mixerInfo = findMixer(deviceNameHint);
        if (mixerInfo != null) {
            line = (TargetDataLine) AudioSystem.getMixer(mixerInfo).getLine(info);
        } else {
            line = (TargetDataLine) AudioSystem.getLine(info);
        }
        int buffer = (int) (sampleRate * chunkSeconds * 2);
        line.open(fmt, buffer * 4);
        line.start();
        running.set(true);
        thread = new Thread(this::loop, "gek-mic-capture");
        thread.setDaemon(true);
        thread.start();
        log.info("Mic capture started ({} Hz, {}s, deviceHint={})", sampleRate, chunkSeconds, deviceNameHint);
    }

    private Mixer.Info findMixer(String hint) {
        if (hint == null || hint.isBlank()) return null;
        String h = hint.toLowerCase();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            String name = (mi.getName() + " " + mi.getDescription()).toLowerCase();
            if (name.contains(h)) return mi;
        }
        return null;
    }

    private void loop() {
        int bytesPerChunk = (int) (sampleRate * chunkSeconds * 2);
        byte[] buf = new byte[bytesPerChunk];
        while (running.get()) {
            int read = 0;
            while (read < buf.length && running.get()) {
                int n = line.read(buf, read, buf.length - read);
                if (n <= 0) break;
                read += n;
            }
            if (read <= 0) continue;
            float[] samples = toFloat(buf, read);
            if (consumer != null) consumer.accept(new AudioChunk("mic", samples, System.currentTimeMillis()));
        }
    }

    private static float[] toFloat(byte[] pcm16le, int len) {
        int n = len / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            out[i] = s / 32768f;
        }
        return out;
    }

    public void stop() {
        running.set(false);
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
        }
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        log.info("Mic capture stopped");
    }

    @Override public void close() { stop(); }
}
