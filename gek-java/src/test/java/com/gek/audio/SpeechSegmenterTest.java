package com.gek.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeechSegmenterTest {
    @Test
    void emitsOnSilenceAfterSpeech() {
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, 100, 0.1f, 4.0f, 0.0f);
        List<float[]> out = new ArrayList<>();
        seg.setOnSegment(out::add);
        // speech chunk ~0.3s
        float[] speech = new float[4800]; for (int i=0;i<speech.length;i++) speech[i]=0.2f;
        seg.accept(new AudioChunk("mic", speech, System.currentTimeMillis()));
        // silence chunks totaling >100ms
        for (int k=0;k<3;k++) seg.accept(new AudioChunk("mic", new float[1600], System.currentTimeMillis()));
        assertFalse(out.isEmpty());
        assertTrue(out.get(0).length >= 4800);
    }
}
