package com.gek.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SequenceAudioSourceTest {
    @Test
    void emitsConfiguredChunks() {
        SequenceAudioSource src = SequenceAudioSource.ofSamples("fake-loopback", "loopback", new float[]{0.1f,0.2f}, new float[]{0.3f});
        List<AudioChunk> got = new ArrayList<>();
        src.setConsumer(got::add);
        src.start();
        assertEquals(2, got.size());
        assertEquals("loopback", got.get(0).source());
        assertEquals(2, got.get(0).samples().length);
        assertEquals("fake-loopback", src.name());
    }
}
