package com.gek.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Expanded smoke/calibration for WAV IO and segmentation pipeline (pure Java, no devices/inference). */
class WavRoundTripAndSegmentationSmokeTest {
    @TempDir Path tmp;

    @Test
    void wavWriteReadRoundTripPreservesLengthAndApproxSamples() throws Exception {
        float[] in = new float[16000];
        for (int i = 0; i < in.length; i++) in[i] = (float) (0.25 * Math.sin(2 * Math.PI * 440 * i / 16000.0));
        Path wav = tmp.resolve("tone.wav");
        WavWriter.writeFloatMono16k(wav, in);
        WavReader.AudioData out = WavReader.readMono(wav, 16000);
        assertEquals(16000, out.sampleRate(), 0.01);
        assertEquals(in.length, out.samples().length);
        assertEquals(1.0, out.durationSeconds(), 0.02);
        double err = 0;
        for (int i = 0; i < in.length; i++) err += Math.abs(in[i] - out.samples()[i]);
        err /= in.length;
        assertTrue(err < 1e-3, "mean abs error too large: " + err);
    }

    @Test
    void sustainedSilenceEmitsOnlyLowEnergySegments_characterization() throws Exception {
        float[] silence = new float[16000];
        List<float[]> segments = segment(silence);
        // Segmenter emits on silence-duration boundary once min buffered duration is reached; characterize as low-energy.
        for (float[] s : segments) {
            assertTrue(rms(s) < 0.02f, "expected near-silent segment, rms=" + rms(s));
            assertTrue(s.length >= 1600, "segment unexpectedly short: " + s.length);
        }
    }

    @Test
    void speechFollowedBySilenceEmitsBoundedSpeechSegment() throws Exception {
        float[] speech = sine(4800, 220, 0.3);
        float[] silence = new float[8000];
        float[] all = concat(speech, silence);
        List<float[]> segments = segment(all);
        assertFalse(segments.isEmpty(), "expected at least one segment");
        boolean hasSpeech = false;
        for (float[] s : segments) {
            assertTrue(s.length >= 1600, "segment unexpectedly short: " + s.length);
            assertTrue(s.length <= 16000 * 5, "segment unexpectedly long: " + s.length);
            if (rms(s) >= 0.02f) hasSpeech = true;
        }
        assertTrue(hasSpeech, "expected at least one non-silent segment");
    }

    @Test
    void multipleBurstsEmitMultipleSegments() throws Exception {
        float[] burst = sine(4800, 300, 0.35);
        float[] gap = new float[9600]; // 0.6s silence > silenceMs
        float[] all = concat(concat(concat(burst, gap), burst), gap);
        List<float[]> segments = segment(all);
        assertTrue(segments.size() >= 2, "expected >=2 segments, got " + segments.size());
    }

    private static List<float[]> segment(float[] all) {
        List<float[]> segments = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 120, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(segments::add);
        int chunk = 4000;
        for (int off = 0; off < all.length; off += chunk) {
            int n = Math.min(chunk, all.length - off);
            float[] c = new float[n];
            System.arraycopy(all, off, c, 0, n);
            seg.accept(new AudioChunk("file", c, 0));
        }
        seg.flush();
        return segments;
    }

    private static float[] sine(int n, double hz, double amp) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 16000.0));
        return a;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] r = new float[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static float rms(float[] s) {
        double sum = 0;
        for (float v : s) sum += v * v;
        return (float) Math.sqrt(sum / Math.max(1, s.length));
    }
}
