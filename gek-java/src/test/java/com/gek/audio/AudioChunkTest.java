package com.gek.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioChunkTest {
    @Test
    void recordStoresValues() {
        float[] samples = new float[]{0.1f, -0.2f};
        AudioChunk c = new AudioChunk("mic", samples, 123L);
        assertEquals("mic", c.source());
        assertSame(samples, c.samples());
        assertEquals(123L, c.timestampMs());
    }

    @Test
    void allowsNulls() {
        AudioChunk c = new AudioChunk(null, null, 0L);
        assertNull(c.source());
        assertNull(c.samples());
        assertEquals(0L, c.timestampMs());
    }
}
