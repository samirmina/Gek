package com.gek.inference;

import com.gek.core.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class NativeDiarizationEngineTest {
    @TempDir Path tmp;

    @Test
    void nullWavFails() {
        String prev = System.getProperty("gek.projectRoot");
        try {
            System.setProperty("gek.projectRoot", tmp.toString());
            AppPaths paths = AppPaths.resolve();
            NativeDiarizationEngine e = new NativeDiarizationEngine(paths);
            ExecutionException ex = assertThrows(ExecutionException.class, () -> e.diarize(null).get());
            assertTrue(ex.getCause() instanceof InferenceUnavailableException || ex.getCause() instanceof IllegalArgumentException
                    || ex.getCause() instanceof RuntimeException);
            assertNotNull(e.reason());
            assertDoesNotThrow(e::close);
            assertFalse(e.runtimeUsable() || e.runtimeUsable()); // just exercise accessor
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void unavailableRuntimeFailsFast() throws Exception {
        String prev = System.getProperty("gek.projectRoot");
        try {
            Files.createDirectories(tmp.resolve("resources/models"));
            System.setProperty("gek.projectRoot", tmp.toString());
            AppPaths paths = AppPaths.resolve();
            NativeDiarizationEngine e = new NativeDiarizationEngine(paths);
            // no onnx/ort assets in tmp project => not usable in most envs; if usable, skip
            if (!e.runtimeUsable()) {
                ExecutionException ex = assertThrows(ExecutionException.class, () -> e.diarize(tmp.resolve("x.wav")).get());
                assertNotNull(ex.getCause());
                assertTrue(e.reason() != null && !e.reason().isBlank());
            }
            assertDoesNotThrow(e::close);
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }
}
