package com.gek.inference;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class UnavailableEnginesTest {
    @Test
    void speechToTextFailsWithReason() {
        UnavailableSpeechToTextEngine e = new UnavailableSpeechToTextEngine("stt-missing");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> e.warmup().get());
        assertTrue(ex.getCause() instanceof SidecarException);
        assertTrue(ex.getCause().getMessage().contains("stt-missing"));
        ex = assertThrows(ExecutionException.class, () -> e.transcribe(Path.of("x.wav"), "en").get());
        assertTrue(ex.getCause().getMessage().contains("stt-missing"));
        assertDoesNotThrow(e::close);
    }

    @Test
    void speechToTextDefaultReason() {
        UnavailableSpeechToTextEngine e = new UnavailableSpeechToTextEngine(null);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> e.warmup().get());
        assertTrue(ex.getCause().getMessage().toLowerCase().contains("unavailable"));
    }

    @Test
    void translationFailsWithReason() {
        UnavailableTranslationEngine e = new UnavailableTranslationEngine("tr-missing");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> e.translate("hi", "en", "fr").get());
        assertTrue(ex.getCause() instanceof SidecarException);
        assertTrue(ex.getCause().getMessage().contains("tr-missing"));
        assertDoesNotThrow(e::close);
        UnavailableTranslationEngine d = new UnavailableTranslationEngine(null);
        ex = assertThrows(ExecutionException.class, () -> d.translate("x", "a", "b").get());
        assertTrue(ex.getCause().getMessage().toLowerCase().contains("unavailable"));
    }

    @Test
    void diarizationFailsWithReason() {
        UnavailableDiarizationEngine e = new UnavailableDiarizationEngine("dia-missing");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> e.diarize(Path.of("x.wav")).get());
        assertTrue(ex.getCause() instanceof SidecarException);
        assertTrue(ex.getCause().getMessage().contains("dia-missing"));
        assertDoesNotThrow(e::close);
        UnavailableDiarizationEngine d = new UnavailableDiarizationEngine(null);
        ex = assertThrows(ExecutionException.class, () -> d.diarize(Path.of("y.wav")).get());
        assertTrue(ex.getCause().getMessage().toLowerCase().contains("unavailable"));
    }
}
