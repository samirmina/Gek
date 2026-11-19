package com.gek.inference;

import com.gek.audio.AudioChunk;
import com.gek.audio.WavWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backend-agnostic streaming STT session that produces live partials by periodically re-decoding a
 * rolling audio window through a one-shot {@link SpeechToTextEngine}. When backed by a warm/in-process
 * engine this yields low-latency chat-style partials without model reload per segment.
 *
 * <p>Feed audio via {@link #accept(AudioChunk)} (16 kHz mono float). Partials are emitted on a
 * schedule while new audio arrives; {@link #stop()} performs a final decode and emits
 * {@link StreamingTranscriptionListener#onFinal(String)}. Single-flight decoding prevents queue
 * explosion; intermediate partial requests are coalesced.
 */
public final class WindowedStreamingSession implements StreamingSttSession {
    private static final float SAMPLE_RATE = 16000f;
    private static final long PARTIAL_INTERVAL_MS = 700;
    private static final float MAX_WINDOW_SECONDS = 20f;   // rolling window for partial decode
    private static final float MIN_PARTIAL_SECONDS = 0.35f;

    private final SpeechToTextEngine engine;
    private final String languageHint;
    private final StreamingTranscriptionListener listener;
    private final Path tmpDir;

    private final Object lock = new Object();
    private final List<float[]> chunks = new ArrayList<>();
    private int totalSamples = 0;
    private boolean dirty = false;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final AtomicBoolean pendingPartial = new AtomicBoolean(false);
    private final AtomicReference<String> lastText = new AtomicReference<>("");
    private final AtomicInteger decodeSeq = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler;

    public WindowedStreamingSession(SpeechToTextEngine engine, String languageHint,
                                    StreamingTranscriptionListener listener, Path tmpDir) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.languageHint = languageHint;
        this.listener = listener == null ? new StreamingTranscriptionListener(){} : listener;
        this.tmpDir = tmpDir;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gek-chat-partial");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::maybeDecodePartial, PARTIAL_INTERVAL_MS, PARTIAL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Feed captured audio (expected 16 kHz mono float). Safe to call from capture thread. */
    public void accept(AudioChunk chunk) {
        if (chunk == null || chunk.samples() == null || chunk.samples().length == 0) return;
        acceptSamples(chunk.samples());
    }

    public void acceptSamples(float[] samples) {
        if (!running.get() || samples == null || samples.length == 0) return;
        synchronized (lock) {
            if (!running.get()) return;
            chunks.add(samples.clone());
            totalSamples += samples.length;
            dirty = true;
            trimLocked();
        }
    }

    private void trimLocked() {
        int max = (int) (MAX_WINDOW_SECONDS * SAMPLE_RATE);
        while (totalSamples > max && chunks.size() > 1) {
            float[] removed = chunks.remove(0);
            totalSamples -= (removed == null ? 0 : removed.length);
        }
        if (totalSamples < 0) totalSamples = 0;
    }

    private void maybeDecodePartial() {
        if (!running.get() || stopping.get() || closed.get()) return;
        boolean should;
        float secs;
        synchronized (lock) {
            should = dirty;
            secs = totalSamples / SAMPLE_RATE;
        }
        if (!should || secs < MIN_PARTIAL_SECONDS) return;
        if (!decoding.compareAndSet(false, true)) {
            pendingPartial.set(true);
            return;
        }
        decodeSnapshot(false);
    }

    private void decodeSnapshot(boolean isFinal) {
        float[] snapshot;
        synchronized (lock) {
            dirty = false;
            snapshot = mergeLocked();
        }
        if (snapshot == null || snapshot.length == 0) {
            decoding.set(false);
            if (isFinal) finishFinal("");
            else drainPending();
            return;
        }
        int seq = decodeSeq.incrementAndGet();
        runDecode(snapshot, seq, isFinal);
    }

    private void runDecode(float[] snapshot, int seq, boolean isFinal) {
        CompletableFuture
                .supplyAsync(() -> transcribeSamples(snapshot))
                .whenComplete((text, err) -> {
                    try {
                        if (closed.get() && !isFinal) return;
                        if (err != null) {
                            listener.onError(err);
                        } else if (seq == decodeSeq.get()) {
                            String t = text == null ? "" : text.trim();
                            if (isFinal) {
                                lastText.set(t);
                                finishFinal(t);
                                return;
                            } else if (!t.isEmpty() && !t.equals(lastText.get())) {
                                lastText.set(t);
                                listener.onPartial(t);
                            }
                        }
                    } finally {
                        decoding.set(false);
                        if (!isFinal) drainPending();
                    }
                });
    }

    private void drainPending() {
        if (pendingPartial.getAndSet(false) && running.get() && !stopping.get() && !closed.get()) {
            if (decoding.compareAndSet(false, true)) {
                decodeSnapshot(false);
            } else {
                pendingPartial.set(true);
            }
        }
    }

    private String transcribeSamples(float[] samples) {
        Path tmp = null;
        try {
            if (tmpDir != null) Files.createDirectories(tmpDir);
            tmp = (tmpDir != null)
                    ? Files.createTempFile(tmpDir, "chat-part-", ".wav")
                    : Files.createTempFile("chat-part-", ".wav");
            WavWriter.writeFloatMono16k(tmp, samples);
            TranscriptionResult tr = engine.transcribe(tmp, languageHint).join();
            return tr == null ? "" : (tr.text() == null ? "" : tr.text());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private float[] mergeLocked() {
        if (chunks.isEmpty()) return new float[0];
        if (chunks.size() == 1) return chunks.get(0).clone();
        float[] out = new float[totalSamples];
        int o = 0;
        for (float[] c : chunks) {
            if (c == null) continue;
            int n = Math.min(c.length, out.length - o);
            if (n <= 0) break;
            System.arraycopy(c, 0, out, o, n);
            o += n;
        }
        if (o == out.length) return out;
        float[] trimmed = new float[Math.max(0, o)];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private void finishFinal(String text) {
        if (closed.compareAndSet(false, true)) {
            running.set(false);
            scheduler.shutdownNow();
            listener.onFinal(text == null ? "" : text);
            listener.onClosed();
        }
    }

    @Override
    public void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        running.set(false);
        scheduler.shutdownNow();
        awaitIdleBriefly();
        if (decoding.compareAndSet(false, true)) {
            decodeSnapshot(true);
        } else {
            schedulerSafeFinal();
        }
    }

    private void awaitIdleBriefly() {
        int spins = 0;
        while (decoding.get() && spins++ < 50) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void schedulerSafeFinal() {
        Thread t = new Thread(() -> {
            awaitIdleBriefly();
            if (closed.get()) return;
            if (decoding.compareAndSet(false, true)) {
                decodeSnapshot(true);
            } else if (!closed.get()) {
                finishFinal(lastText.get());
            }
        }, "gek-chat-final");
        t.setDaemon(true);
        t.start();
    }

    @Override public boolean isRunning() { return running.get() && !closed.get(); }

    @Override public String finalTranscript() { return lastText.get(); }

    @Override public void close() {
        if (closed.get()) return;
        stop();
        if (!closed.get()) finishFinal(lastText.get());
    }
}
