package com.gek.audio;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-Java segmentation pipeline smoke (no sidecar/inference). */
class SegmentFilePipelineTest {
    @TempDir Path tmp;

    @Test
    void segmentsSpeechAndSilence() throws Exception {
        // create 16k mono: 0.3s speech + 0.5s silence
        float[] speech = new float[4800];
        for (int i = 0; i < speech.length; i++) speech[i] = (float)(0.3 * Math.sin(2*Math.PI*220*i/16000.0));
        float[] silence = new float[8000];
        float[] all = new float[speech.length + silence.length];
        System.arraycopy(speech,0,all,0,speech.length);
        System.arraycopy(silence,0,all,speech.length,silence.length);
        Path wav = tmp.resolve("in.wav");
        WavWriter.writeFloatMono16k(wav, all);
        WavReader.AudioData audio = WavReader.readMono(wav, 16000);
        List<float[]> segments = new ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 120, 0.1f, 4.0f, 0.0f);
        seg.setOnSegment(segments::add);
        int chunk = 4000; // 0.25s
        for (int off=0; off<audio.samples().length; off+=chunk) {
            int n = Math.min(chunk, audio.samples().length-off);
            float[] c = new float[n]; System.arraycopy(audio.samples(), off, c, 0, n);
            seg.accept(new AudioChunk("file", c, 0));
        }
        seg.flush();
        assertFalse(segments.isEmpty(), "expected at least one segment");
        assertTrue(segments.get(0).length >= 3200);
    }
}
