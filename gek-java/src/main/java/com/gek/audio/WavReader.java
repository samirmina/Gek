package com.gek.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal WAV/PCM reader for pipeline tooling (WSL2/headless friendly, no external deps). */
public final class WavReader {
    private WavReader() {}

    public record AudioData(float[] samples, int sampleRate, int channels) {
        public double durationSeconds() { return sampleRate <= 0 ? 0 : (double) samples.length / (double) sampleRate; }
    }

    /** Read audio as mono float samples in [-1,1]. Resamples/channels handled best-effort via Java Sound conversion. */
    public static AudioData readMono(Path path, int targetSampleRate) throws Exception {
        try (AudioInputStream in0 = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            AudioFormat src = in0.getFormat();
            AudioFormat target = new AudioFormat(
                    targetSampleRate > 0 ? (float) targetSampleRate : src.getSampleRate(),
                    16, 1, true, false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(target, in0)) {
                byte[] pcm = in.readAllBytes();
                float[] samples = new float[pcm.length / 2];
                for (int i = 0; i < samples.length; i++) {
                    int lo = pcm[i * 2] & 0xFF;
                    int hi = pcm[i * 2 + 1];
                    short s = (short) ((hi << 8) | lo);
                    samples[i] = s / 32768f;
                }
                return new AudioData(samples, (int) target.getSampleRate(), 1);
            }
        }
    }

    public static AudioData readMono(Path path) throws Exception { return readMono(path, 16000); }
}
