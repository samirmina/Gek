package com.gek.inference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleInferenceTypesTest {
    @Test
    void transcriptionResultRecord() {
        TranscriptionResult r = new TranscriptionResult("hello", "en", 0.9, 1.2);
        assertEquals("hello", r.text());
        assertEquals("en", r.language());
        assertEquals(0.9, r.confidence());
        assertEquals(1.2, r.durationSec());
        assertEquals(r, new TranscriptionResult("hello", "en", 0.9, 1.2));
        assertNotNull(r.toString());
    }

    @Test
    void sidecarException() {
        SidecarException e = new SidecarException("m");
        assertEquals("m", e.getMessage());
        RuntimeException c = new RuntimeException("c");
        SidecarException e2 = new SidecarException("m2", c);
        assertEquals("m2", e2.getMessage());
        assertSame(c, e2.getCause());
    }

    @Test
    void nativeProcessResult() {
        NativeProcessResult ok = new NativeProcessResult(0, "o", "e", 5);
        assertTrue(ok.ok());
        assertEquals("o", ok.stdout());
        assertEquals("e", ok.stderr());
        assertEquals(5, ok.millis());
        assertFalse(new NativeProcessResult(2, "", "", 0).ok());
    }

    @Test
    void inferenceUnavailableException() {
        InferenceUnavailableException a = new InferenceUnavailableException("x");
        assertEquals("x", a.getMessage());
        Exception cause = new Exception("y");
        InferenceUnavailableException b = new InferenceUnavailableException("z", cause);
        assertEquals("z", b.getMessage());
        assertSame(cause, b.getCause());
    }
}
