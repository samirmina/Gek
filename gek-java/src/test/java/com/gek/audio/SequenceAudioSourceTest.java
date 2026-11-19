package com.gek.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SequenceAudioSourceTest {
    @Test
    void emitsConfiguredChunks() {
        SequenceAudioSource src = SequenceAudioSource.ofSamples("fake-loopback", "loopback", new float[]{0.1f, 0.2f}, new float[]{0.3f});
        List<AudioChunk> got = new ArrayList<>();
        src.setConsumer(got::add);
        src.start();
        assertEquals(2, got.size());
        assertEquals("loopback", got.get(0).source());
        assertEquals(2, got.get(0).samples().length);
        assertEquals("fake-loopback", src.name());
        assertFalse(src.isRunning());
    }

    @Test
    void defaultsNameAndHandlesNullInputs() {
        SequenceAudioSource src = new SequenceAudioSource(null, null);
        AtomicInteger count = new AtomicInteger();
        src.setConsumer(c -> count.incrementAndGet());
        assertEquals("sequence", src.name());
        src.start();
        assertEquals(0, count.get());
        src.stop();
        assertFalse(src.isRunning());
    }

    @Test
    void ofSamplesFallsBackToNameAsChunkSource() {
        SequenceAudioSource src = SequenceAudioSource.ofSamples("src-name", null, new float[]{1f});
        List<AudioChunk> got = new ArrayList<>();
        src.setConsumer(got::add);
        src.start();
        assertEquals(1, got.size());
        assertEquals("src-name", got.get(0).source());
    }

    @Test
    void canBeStartedAgainAfterCompletion() {
        SequenceAudioSource src = SequenceAudioSource.ofSamples("n", "s", new float[]{1f});
        List<AudioChunk> got = new ArrayList<>();
        src.setConsumer(got::add);
        src.start();
        assertFalse(src.isRunning());
        src.start();
        assertEquals(2, got.size(), "completed source may replay configured chunks on subsequent start");
    }
}
