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
}
