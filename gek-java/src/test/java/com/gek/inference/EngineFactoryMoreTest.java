package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class EngineFactoryMoreTest {

    @TempDir Path tempDir;

    private AppPaths tempPaths() {
        String prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", tempDir.toString());
        try {
            return AppPaths.resolve();
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot");
            else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void unknownModeDefaultsToNative() {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        s.inference_mode = "weird-mode";
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, true)) {
            assertEquals("native", e.mode());
            assertNull(e.sidecarOrNull());
            assertNotNull(e.note());
        }
    }

    @Test
    void autoAndJavaModesUseNative() {
        AppPaths paths = AppPaths.resolve();
        for (String m : new String[]{"auto", "java", "native", "", "  "}) {
            AppSettings s = AppSettings.defaults();
            s.inference_mode = m;
            try (EngineFactory.Engines e = EngineFactory.create(paths, s, true)) {
                assertEquals("native", e.mode());
                assertNull(e.sidecarOrNull());
            }
        }
    }

    @Test
    void pythonAliasRequestsSidecarWhenAllowed() {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        s.inference_mode = "python";
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, true)) {
            assertEquals("sidecar", e.mode());
            assertNotNull(e.sidecarOrNull());
        }
    }

    @Test
    void sidecarRequestedButDisallowedFallsBackToNative() {
        AppPaths paths = AppPaths.resolve();
        for (String m : new String[]{"sidecar", "python"}) {
            AppSettings s = AppSettings.defaults();
            s.inference_mode = m;
            try (EngineFactory.Engines e = EngineFactory.create(paths, s, false)) {
                assertEquals("native", e.mode());
                assertNull(e.sidecarOrNull());
                assertNotNull(e.note());
                assertTrue(e.note().contains("disabled"));
            }
        }
    }

    @Test
    void nullSettingsDefaultsToNative() {
        AppPaths paths = AppPaths.resolve();
        try (EngineFactory.Engines e = EngineFactory.create(paths, null, true)) {
            assertEquals("native", e.mode());
            assertNull(e.sidecarOrNull());
        }
    }

    @Test
    void nativeModeComposesAllEnginesAndSummarizesReadiness() {
        AppPaths paths = tempPaths();
        AppSettings s = AppSettings.defaults();
        s.inference_mode = "native";
        String prev = System.getProperty("gek.stt.disableWarmServer");
        System.setProperty("gek.stt.disableWarmServer", "true");
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, true)) {
            assertEquals("native", e.mode());
            assertNull(e.sidecarOrNull(), "native mode must not attach sidecar");
            assertNotNull(e.stt());
            assertNotNull(e.translator());
            assertNotNull(e.diarizer());
            assertTrue(e.stt() instanceof NativeSpeechToTextEngine);
            assertNotNull(e.note());
            String note = e.note().toLowerCase(Locale.ROOT);
            assertTrue(note.contains("stt"), "note should summarize STT: " + e.note());
            assertTrue(note.contains("translation"), "note should summarize translation: " + e.note());
            assertTrue(note.contains("diarization"), "note should summarize diarization: " + e.note());
            // in empty temp env runtimes are not ready
            assertTrue(note.contains("not ready") || note.contains("ready"),
                    "note should report readiness state: " + e.note());
        } finally {
            if (prev == null) System.clearProperty("gek.stt.disableWarmServer");
            else System.setProperty("gek.stt.disableWarmServer", prev);
        }
    }
}
