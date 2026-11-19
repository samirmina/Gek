package com.gek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path tmp;

    @Test void loadMissingReturnsNormalizedDefaults() {
        Path p = tmp.resolve("nope.json");
        AppSettings s = new SettingsStore(p).load();
        assertNotNull(s);
        assertEquals("native", s.inference_mode);
        assertEquals("auto", s.source_language);
        assertEquals("en", s.target_language);
        assertEquals("", s.sidecar_python);
    }

    @Test void loadInvalidReturnsNormalizedDefaults() throws Exception {
        Path p = tmp.resolve("bad.json");
        Files.writeString(p, "{ not valid json");
        AppSettings s = new SettingsStore(p).load();
        assertNotNull(s);
        assertEquals("native", s.inference_mode);
        assertEquals("auto", s.source_language);
        assertEquals("en", s.target_language);
    }

    @Test void saveAndLoadRoundTrip() throws Exception {
        Path p = tmp.resolve("nested/dir/settings.json");
        SettingsStore store = new SettingsStore(p);
        AppSettings s = AppSettings.defaults();
        s.preset = "Fast";
        s.target_language = "ar";
        s.whisper_model = "medium";
        s.putExtra("custom_flag", true);
        store.save(s);
        assertTrue(Files.exists(p));

        AppSettings loaded = store.load();
        assertEquals("Fast", loaded.preset);
        assertEquals("ar", loaded.target_language);
        assertEquals("medium", loaded.whisper_model);
        assertEquals(true, loaded.extra.get("custom_flag"));
        assertEquals("native", loaded.inference_mode);
    }

    @Test void loadNormalizesBlankAndAutoInferenceMode() throws Exception {
        Path p = tmp.resolve("s.json");
        Files.writeString(p, "{\"inference_mode\":\"  \",\"source_language\":\"\",\"target_language\":\"\",\"sidecar_python\":null}");
        AppSettings s = new SettingsStore(p).load();
        assertEquals("native", s.inference_mode);
        assertEquals("auto", s.source_language);
        assertEquals("en", s.target_language);
        assertEquals("", s.sidecar_python);

        Files.writeString(p, "{\"inference_mode\":\"auto\"}");
        assertEquals("native", new SettingsStore(p).load().inference_mode);

        Files.writeString(p, "{\"inference_mode\":\" SIDECAR \"}");
        assertEquals("sidecar", new SettingsStore(p).load().inference_mode);
    }

    @Test void loadEmptyJsonYieldsDefaults() throws Exception {
        Path p = tmp.resolve("nullish.json");
        Files.writeString(p, "null");
        AppSettings s = new SettingsStore(p).load();
        assertEquals("native", s.inference_mode);
        assertEquals("auto", s.source_language);
        assertEquals("en", s.target_language);
    }
}
