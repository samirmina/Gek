package com.gek.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WavReaderTest {
    @TempDir Path tmp;

    @Test
    void roundTripMono16k() throws Exception {
        Path p = tmp.resolve("rt.wav");
        float[] in = new float[1600];
        for (int i = 0; i < in.length; i++) in[i] = (i % 100) / 100f - 0.5f;
        WavWriter.writeFloatMono16k(p, in);
        WavReader.AudioData out = WavReader.readMono(p, 16000);
        assertEquals(16000, out.sampleRate());
        assertEquals(1, out.channels());
        assertEquals(in.length, out.samples().length);
        assertTrue(out.durationSeconds() > 0.09 && out.durationSeconds() < 0.11);
    }

    @Test
    void readMonoDefaultOverload() throws Exception {
        Path p = tmp.resolve("rt2.wav");
        float[] in = new float[800];
        for (int i = 0; i < in.length; i++) in[i] = (float) Math.sin(i / 10.0);
        WavWriter.writeFloatMono16k(p, in);
        WavReader.AudioData out = WavReader.readMono(p);
        assertEquals(16000, out.sampleRate());
        assertEquals(1, out.channels());
        assertEquals(in.length, out.samples().length);
        assertEquals(in.length / 16000.0, out.durationSeconds(), 1e-9);
    }

    @Test
    void durationSecondsZeroWhenSampleRateNonPositive() {
        WavReader.AudioData data = new WavReader.AudioData(new float[]{0.1f, 0.2f}, 0, 1);
        assertEquals(0.0, data.durationSeconds());
        WavReader.AudioData dataNeg = new WavReader.AudioData(new float[]{0.1f}, -1, 1);
        assertEquals(0.0, dataNeg.durationSeconds());
    }
}
