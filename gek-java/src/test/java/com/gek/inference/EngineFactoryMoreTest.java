package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineFactoryMoreTest {

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
}
