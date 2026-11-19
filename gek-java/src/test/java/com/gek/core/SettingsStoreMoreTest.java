package com.gek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreMoreTest {
    @TempDir Path tmp;

    @Test
    void saveCreatesParentAndPreservesUnknownExtrasOnRoundTrip() throws Exception {
        Path p = tmp.resolve("deep/nested/settings.json");
        SettingsStore store = new SettingsStore(p);
        AppSettings s = AppSettings.defaults();
        s.putExtra("alpha", 1);
        s.putExtra("beta", "x");
        store.save(s);
        assertTrue(Files.isDirectory(p.getParent()));

        AppSettings loaded = store.load();
        assertEquals(1, ((Number) loaded.extra.get("alpha")).intValue());
        assertEquals("x", loaded.extra.get("beta"));
        assertEquals("native", loaded.inference_mode);
    }

    @Test
    void loadPartialJsonKeepsDefaultsForMissingFields() throws Exception {
        Path p = tmp.resolve("partial.json");
        Files.writeString(p, "{\"preset\":\"Accurate\"}");
        AppSettings s = new SettingsStore(p).load();
        assertEquals("Accurate", s.preset);
        assertEquals("native", s.inference_mode);
        assertEquals("auto", s.source_language);
        assertEquals("en", s.target_language);
        assertEquals("", s.sidecar_python);
    }

    @Test
    void loadNormalizesKnownFields_characterization() throws Exception {
        Path p = tmp.resolve("norm.json");
        Files.writeString(p, "{\"inference_mode\":\" native \",\"source_language\":\" auto \",\"target_language\":\" en \",\"sidecar_python\":null}");
        AppSettings s = new SettingsStore(p).load();
        // inference_mode is trimmed+lowercased (and "auto" would map to native); sidecar null -> ""
        assertEquals("native", s.inference_mode);
        assertEquals("", s.sidecar_python);
        // current normalization only fills blank source/target with defaults; non-blank values preserved as-is
        assertEquals(" auto ", s.source_language);
        assertEquals(" en ", s.target_language);
    }
}
