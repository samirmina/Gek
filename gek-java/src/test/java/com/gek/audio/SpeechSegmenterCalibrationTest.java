package com.gek.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** In-process calibration of SpeechSegmenter behavior (portable, no devices). */
class SpeechSegmenterCalibrationTest {

    @Test
    void flushEmitsBufferedSpeechWhenLongEnough() {
        List<float[]> out = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 500, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(out::add);
        seg.accept(new AudioChunk("t", sine(3200, 220, 0.3), 0)); // 0.2s voiced
        assertTrue(out.isEmpty(), "should not emit before silence/max/flush");
        seg.flush();
        assertEquals(1, out.size());
        assertTrue(out.get(0).length >= 3200);
    }

    @Test
    void flushDropsVeryShortBuffer() {
        List<float[]> out = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 500, 0.5f, 4.0f, 0.0f);
        seg.setOnSegment(out::add);
        seg.accept(new AudioChunk("t", sine(1600, 220, 0.3), 0)); // 0.1s < minChunk and < flush threshold
        seg.flush();
        assertTrue(out.isEmpty(), "short buffered audio should not be emitted");
    }

    @Test
    void maxDurationForcesEmission() {
        List<float[]> out = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 10_000, 0.1f, 0.5f, 0.0f);
        seg.setOnSegment(out::add);
        // continuous speech, never silent, should emit by maxChunkDuration
        for (int i = 0; i < 6; i++) {
            seg.accept(new AudioChunk("t", sine(4000, 220, 0.3), 0)); // 0.25s chunks
        }
        assertFalse(out.isEmpty(), "expected emission by max duration");
        for (float[] s : out) assertTrue(s.length > 0);
    }

    @Test
    void pauseResetsAndIgnoresInput() {
        List<float[]> out = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 100, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(out::add);
        seg.accept(new AudioChunk("t", sine(3200, 220, 0.3), 0));
        seg.setPaused(true);
        seg.accept(new AudioChunk("t", sine(3200, 220, 0.3), 0));
        seg.flush();
        assertTrue(out.isEmpty(), "paused segmenter should discard/reset");
        seg.setPaused(false);
        seg.accept(new AudioChunk("t", sine(3200, 220, 0.3), 0));
        seg.accept(new AudioChunk("t", new float[8000], 0)); // silence to trigger
        assertFalse(out.isEmpty(), "after unpause segmenter should work again");
    }

    @Test
    void resetDropsBufferedState() {
        AtomicInteger calls = new AtomicInteger();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 500, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(s -> calls.incrementAndGet());
        seg.accept(new AudioChunk("t", sine(3200, 220, 0.3), 0));
        seg.reset();
        seg.flush();
        assertEquals(0, calls.get());
    }

    @Test
    void nullOrEmptyChunkIgnored() {
        List<float[]> out = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 100, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(out::add);
        seg.accept(null);
        seg.accept(new AudioChunk("t", null, 0));
        seg.flush();
        assertTrue(out.isEmpty());
    }

    @Test
    void carryoverKeepsTailForNextSegment() {
        List<float[]> out = new ArrayList<>();
        // carryover 0.1s = 1600 samples
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 80, 0.05f, 4.0f, 0.1f);
        seg.setOnSegment(out::add);
        seg.accept(new AudioChunk("t", sine(6400, 220, 0.3), 0)); // speech
        seg.accept(new AudioChunk("t", new float[4800], 0)); // silence triggers emit
        assertEquals(1, out.size());
        // after emit, carryover remains buffered; speaking again can emit another segment
        seg.accept(new AudioChunk("t", sine(6400, 220, 0.3), 0));
        seg.accept(new AudioChunk("t", new float[4800], 0));
        assertTrue(out.size() >= 2, "carryover should allow subsequent segmentation");
    }

    private static float[] sine(int n, double hz, double amp) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 16000.0));
        return a;
    }
}
