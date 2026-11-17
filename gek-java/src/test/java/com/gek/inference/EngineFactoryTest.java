package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineFactoryTest {

    @Test
    void defaultsToNativeWithoutSidecar() {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        assertEquals("native", s.inference_mode);
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, false)) {
            assertEquals("native", e.mode());
            assertNull(e.sidecarOrNull());
            assertNotNull(e.stt());
            assertNotNull(e.translator());
            assertNotNull(e.diarizer());
            assertNotNull(e.note());
            assertTrue(e.note().toLowerCase().contains("native") || e.note().toLowerCase().contains("stt"));
        }
    }

    @Test
    void sidecarModeRequiresExplicitAllowance() {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        s.inference_mode = "sidecar";
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, false)) {
            assertEquals("native", e.mode(), "sidecar must not be used unless explicitly allowed");
            assertNull(e.sidecarOrNull());
        }
        try (EngineFactory.Engines e = EngineFactory.create(paths, s, true)) {
            assertEquals("sidecar", e.mode());
            assertNotNull(e.sidecarOrNull());
            assertSame(e.sidecarOrNull(), e.stt());
        }
    }
}
