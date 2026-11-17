package com.gek.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandLoopbackCaptureTest {
    @Test
    void backendNameIsStableString() {
        String n = CommandLoopbackCapture.backendName();
        assertNotNull(n);
        assertFalse(n.isBlank());
    }
    @Test
    void buildCommandReturnsNullOrNonEmpty() {
        List<String> c = CommandLoopbackCapture.buildBaseCommand(16000, null);
        if (c != null) {
            assertFalse(c.isEmpty());
            assertTrue(c.stream().anyMatch(s -> s.contains("16000") || s.equals("-")));
        }
    }
}
