package com.gek.audio;

import com.gek.core.PlatformSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Best-effort loopback/monitor capture by spawning an external recorder that outputs
 * 16-bit little-endian mono PCM at the requested sample rate to stdout.
 * Supports Pulse/PipeWire tools (parec/parecord/pw-record/pw-cat/ffmpeg) when available.
 */
public class CommandLoopbackCapture implements LoopbackCapture {
    private static final Logger log = LoggerFactory.getLogger(CommandLoopbackCapture.class);
    private final int sampleRate;
    private final float chunkSeconds;
    private final String monitorSource; // optional pulse source/monitor name
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Consumer<AudioChunk> consumer;
    private Process process;
    private Thread reader;

    public CommandLoopbackCapture(int sampleRate, float chunkSeconds, String monitorSource) {
        this.sampleRate = sampleRate;
        this.chunkSeconds = chunkSeconds;
        this.monitorSource = monitorSource;
    }

    public static boolean isSupported() {
        return buildBaseCommand(16000, null) != null;
    }

    public static String backendName() {
        List<String> c = buildBaseCommand(16000, null);
        if (c == null || c.isEmpty()) return "none";
        String exe = c.get(0);
        int slash = exe.lastIndexOf('/');
        return slash >= 0 ? exe.substring(slash + 1) : exe;
    }

    @Override public void setConsumer(Consumer<AudioChunk> consumer) { this.consumer = consumer; }

    @Override public synchronized void start() throws Exception {
        if (running.get()) return;
        List<String> cmd = buildBaseCommand(sampleRate, monitorSource);
        if (cmd == null) throw new IOException("No loopback/monitor capture command available (install Pulse/PipeWire tools or ffmpeg)");
        log.info("Starting command loopback: {}", String.join(" ", cmd));
        process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        running.set(true);
        reader = new Thread(() -> readLoop(process.getInputStream()), "gek-loopback-cmd");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(InputStream raw) {
        int bytesPerChunk = Math.max(320, (int)(sampleRate * chunkSeconds * 2));
        byte[] buf = new byte[bytesPerChunk];
        try (BufferedInputStream in = new BufferedInputStream(raw)) {
            while (running.get()) {
                int read = 0;
                while (read < buf.length && running.get()) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) return;
                    read += n;
                }
                if (read <= 0) continue;
                float[] samples = toFloat(buf, read);
                Consumer<AudioChunk> c = consumer;
                if (c != null) c.accept(new AudioChunk("loopback", samples, System.currentTimeMillis()));
            }
        } catch (IOException e) {
            if (running.get()) log.warn("loopback read ended: {}", e.getMessage());
        } finally {
            running.set(false);
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

    static List<String> buildBaseCommand(int sampleRate, String monitorSource) {
        String src = (monitorSource == null || monitorSource.isBlank()) ? "@DEFAULT_MONITOR@" : monitorSource;
        if (PlatformSupport.commandExists("pw-record")) {
            // PipeWire: record default monitor to stdout raw s16le mono
            List<String> c = new ArrayList<>(List.of("pw-record", "--format", "s16", "--rate", String.valueOf(sampleRate), "--channels", "1", "-"));
            if (!"@DEFAULT_MONITOR@".equals(src)) { c.add(3, "--target"); c.add(4, src); }
            return c;
        }
        if (PlatformSupport.commandExists("parec")) {
            List<String> c = new ArrayList<>(List.of("parec", "--format=s16le", "--rate=" + sampleRate, "--channels=1", "--raw"));
            c.add("--device=" + src);
            return c;
        }
        if (PlatformSupport.commandExists("parecord")) {
            List<String> c = new ArrayList<>(List.of("parecord", "--format=s16le", "--rate=" + sampleRate, "--channels=1", "--raw"));
            c.add("--device=" + src);
            return c;
        }
        if (PlatformSupport.commandExists("ffmpeg")) {
            // best-effort pulse input; may fail without pulse device but command exists
            String dev = "@DEFAULT_MONITOR@".equals(src) ? "default" : src;
            return List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "pulse", "-i", dev,
                    "-ac", "1", "-ar", String.valueOf(sampleRate), "-f", "s16le", "-");
        }
        return null;
    }

    @Override public synchronized void stop() {
        running.set(false);
        Process p = process;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
            try { if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly(); } catch (Exception ignored) {}
        }
        process = null;
        if (reader != null) {
            try { reader.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            reader = null;
        }
    }

    @Override public boolean isRunning() { return running.get() && process != null && process.isAlive(); }
    @Override public String name() { return "loopback-cmd:" + backendName(); }
}
