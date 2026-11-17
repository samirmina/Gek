package com.gek.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Port of STTPipeline buffering: accumulates chunks, detects silence/min/max/carryover,
 * emits float[] segments ready for transcription.
 */
public class SpeechSegmenter {
    private final float sampleRate;
    private final float silenceThreshold;
    private final int silenceDurationMs;
    private final float minChunkDuration;
    private final float maxChunkDuration;
    private final float carryoverDuration;
    private final List<float[]> buffer = new ArrayList<>();
    private float bufferedSeconds = 0f;
    private float silenceSeconds = 0f;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private Consumer<float[]> onSegment;

    public SpeechSegmenter(float sampleRate, float silenceThreshold, int silenceDurationMs,
                           float minChunkDuration, float maxChunkDuration, float carryoverDuration) {
        this.sampleRate = sampleRate;
        this.silenceThreshold = silenceThreshold;
        this.silenceDurationMs = silenceDurationMs;
        this.minChunkDuration = minChunkDuration;
        this.maxChunkDuration = maxChunkDuration;
        this.carryoverDuration = carryoverDuration;
    }

    public void setOnSegment(Consumer<float[]> onSegment) { this.onSegment = onSegment; }
    public void setPaused(boolean p) { paused.set(p); if (p) reset(); }

    public synchronized void accept(AudioChunk chunk) {
        if (paused.get() || chunk == null || chunk.samples() == null) return;
        float[] samples = chunk.samples();
        buffer.add(samples);
        float dur = samples.length / sampleRate;
        bufferedSeconds += dur;
        float rms = rms(samples);
        if (rms < silenceThreshold) silenceSeconds += dur; else silenceSeconds = 0f;

        boolean bySilence = silenceSeconds * 1000f >= silenceDurationMs && bufferedSeconds >= minChunkDuration;
        boolean byMax = bufferedSeconds >= maxChunkDuration;
        if (bySilence || byMax) {
            emit();
        }
    }

    private void emit() {
        if (buffer.isEmpty()) return;
        float[] merged = merge(buffer);
        float mergedSec = merged.length / sampleRate;
        if (mergedSec < Math.max(0.12f, minChunkDuration)) { reset(); return; }
        float[] carry = tail(merged, carryoverDuration);
        buffer.clear();
        bufferedSeconds = 0f;
        silenceSeconds = 0f;
        if (carry.length > 0) { buffer.add(carry); bufferedSeconds = carry.length / sampleRate; }
        if (onSegment != null) onSegment.accept(merged);
    }

    public synchronized void flush() {
        if (bufferedSeconds >= 0.15f) emit(); else reset();
    }

    public synchronized void reset() {
        buffer.clear();
        bufferedSeconds = 0f;
        silenceSeconds = 0f;
    }

    private static float rms(float[] s) {
        double sum = 0; for (float v : s) sum += v * v; return (float) Math.sqrt(sum / Math.max(1, s.length));
    }
    private static float[] merge(List<float[]> chunks) {
        int total = 0; for (float[] c : chunks) total += c.length;
        float[] out = new float[total]; int o = 0; for (float[] c : chunks) { System.arraycopy(c,0,out,o,c.length); o+=c.length; }
        return out;
    }
    private float[] tail(float[] src, float sec) {
        int n = Math.min(src.length, Math.max(0, (int)(sec * sampleRate)));
        if (n <= 0) return new float[0];
        float[] out = new float[n];
        System.arraycopy(src, src.length - n, out, 0, n);
        return out;
    }
}
