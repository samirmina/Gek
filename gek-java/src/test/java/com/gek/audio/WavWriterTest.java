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
        for (int i = 0; i < samples.length; i++) samples[i] = (float) Math.sin(2*Math.PI*440*i/16000.0);
        WavWriter.writeFloatMono16k(p, samples);
        assertTrue(Files.size(p) > 44);
        var ais = AudioSystem.getAudioInputStream(p.toFile());
        assertEquals(16000f, ais.getFormat().getSampleRate());
        assertEquals(1, ais.getFormat().getChannels());
        ais.close();
    }
}
