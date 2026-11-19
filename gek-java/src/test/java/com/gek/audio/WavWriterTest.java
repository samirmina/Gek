package com.gek.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WavWriterTest {
    @TempDir Path tmp;

    @Test
    void writesReadableWav() throws Exception {
        Path p = tmp.resolve("t.wav");
        float[] samples = new float[16000];
        for (int i = 0; i < samples.length; i++) samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / 16000.0);
        WavWriter.writeFloatMono16k(p, samples);
        assertTrue(Files.size(p) > 44);
        var ais = AudioSystem.getAudioInputStream(p.toFile());
        assertEquals(16000f, ais.getFormat().getSampleRate());
        assertEquals(1, ais.getFormat().getChannels());
        ais.close();
    }

    @Test
    void clampsOutOfRangeSamplesAndCreatesParentDirs() throws Exception {
        Path p = tmp.resolve("nested/dir/out.wav");
        float[] samples = new float[]{-2.0f, -1.0f, 0.0f, 1.0f, 2.0f, Float.NaN};
        WavWriter.writeFloatMono16k(p, samples);
        assertTrue(Files.exists(p));
        WavReader.AudioData out = WavReader.readMono(p, 16000);
        assertEquals(samples.length, out.samples().length);
        assertTrue(out.samples()[0] <= -0.99f);
        assertTrue(out.samples()[3] >= 0.99f);
    }

    @Test
    void writesEmptySamplesAndReturnsPath() throws Exception {
        Path p = tmp.resolve("empty.wav");
        Path ret = WavWriter.writeFloatMono16k(p, new float[0]);
        assertEquals(p, ret);
        assertTrue(Files.exists(p));
        assertEquals(44, Files.size(p));
        WavReader.AudioData out = WavReader.readMono(p, 16000);
        assertEquals(0, out.samples().length);
        assertEquals(16000, out.sampleRate());
        assertEquals(0.0, out.durationSeconds());
    }
}
