package com.gek.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WavWriter {
    private WavWriter() {}

    public static Path writeFloatMono16k(Path path, float[] samples) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] pcm = toPcm16(samples);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        int dataSize = pcm.length;
        int sampleRate = 16000;
        short channels = 1;
        short bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        short blockAlign = (short) (channels * bitsPerSample / 8);
        out.write(bytes("RIFF"));
        out.write(leInt(36 + dataSize));
        out.write(bytes("WAVE"));
        out.write(bytes("fmt "));
        out.write(leInt(16)); // PCM chunk size
        out.write(leShort((short) 1)); // PCM format
        out.write(leShort(channels));
        out.write(leInt(sampleRate));
        out.write(leInt(byteRate));
        out.write(leShort(blockAlign));
        out.write(leShort(bitsPerSample));
        out.write(bytes("data"));
        out.write(leInt(dataSize));
        out.write(pcm);
        Files.write(path, out.toByteArray());
        return path;
    }

    private static byte[] toPcm16(float[] samples) {
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            float v = Math.max(-1f, Math.min(1f, s));
            short pcm = (short) (v * 32767f);
            bb.putShort(pcm);
        }
        return bb.array();
    }

    private static byte[] bytes(String s) { return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }
    private static byte[] leInt(int v) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(); }
    private static byte[] leShort(short v) { return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array(); }
}
