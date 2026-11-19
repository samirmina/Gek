package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Expanded smoke/calibration for engine selection and native-mode wiring (no sidecar process required). */
class EngineFactorySelectionSmokeTest {

    @Test
    void autoAndUnknownModesResolveToNativeWithoutSidecar() {
        AppPaths paths = AppPaths.resolve();
        for (String mode : new String[]{"auto", "java", "native", "", "weird-mode"}) {
            AppSettings s = AppSettings.defaults();
            s.inference_mode = mode;
            try (EngineFactory.Engines e = EngineFactory.create(paths, s, false)) {
                assertEquals("native", e.mode(), "mode=" + mode);
                assertNull(e.sidecarOrNull(), "mode=" + mode);
                assertNotNull(e.stt());
                assertNotNull(e.translator());
                assertNotNull(e.diarizer());
                assertNotNull(e.note());
                assertFalse(e.note().isBlank());
            }
        }
    }

    @Test
    void sidecarAllowedYieldsSidecarModeAndSharedClient() {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        s.inference_mode = "sidecar";
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, true)) {
            assertEquals("sidecar", e.mode());
            assertNotNull(e.sidecarOrNull());
            assertSame(e.sidecarOrNull(), e.stt());
            assertSame(e.sidecarOrNull(), e.translator());
            assertSame(e.sidecarOrNull(), e.diarizer());
        }
    }

    @Test
    void pythonAliasRequestsSidecarButNeedsAllowance() {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        s.inference_mode = "python";
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, false)) {
            assertEquals("native", e.mode());
            assertNull(e.sidecarOrNull());
        }
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, true)) {
            assertEquals("sidecar", e.mode());
            assertNotNull(e.sidecarOrNull());
        }
    }

    @Test
    void nativeEnginesExposeConcreteTypes() {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, false)) {
            assertInstanceOf(NativeSpeechToTextEngine.class, e.stt());
            assertInstanceOf(NativeTranslationEngine.class, e.translator());
            assertInstanceOf(NativeDiarizationEngine.class, e.diarizer());
        }
    }
}
