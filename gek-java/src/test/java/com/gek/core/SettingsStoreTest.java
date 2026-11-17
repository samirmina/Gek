package com.gek.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path tmp;

    @Test
    void defaultsToNativeAndPreservesUnknownKeys() throws Exception {
        Path p = tmp.resolve("settings.json");
        Files.writeString(p, "{\n  \"whisper_model\": \"base\",\n  \"inference_mode\": \"auto\",\n  \"custom_python_key\": \"keep-me\",\n  \"nested\": {\"a\": 1}\n}\n");
        SettingsStore store = new SettingsStore(p);
        AppSettings s = store.load();
        assertEquals("native", s.inference_mode, "auto/blank should normalize to native");
        assertEquals("base", s.whisper_model);
        assertNotNull(s.extra);
        assertEquals("keep-me", s.extra.get("custom_python_key"));

        store.save(s);
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = new ObjectMapper().readValue(p.toFile(), Map.class);
        assertEquals("native", saved.get("inference_mode"));
        assertEquals("keep-me", saved.get("custom_python_key"), "unknown keys must survive round-trip");
        assertTrue(saved.containsKey("nested"));
    }

    @Test
    void missingFileReturnsNormalizedDefaults() {
        SettingsStore store = new SettingsStore(tmp.resolve("missing.json"));
        AppSettings s = store.load();
        assertNotNull(s);
        assertEquals("native", s.inference_mode);
        assertEquals("auto", s.source_language);
        assertEquals("en", s.target_language);
    }
}
