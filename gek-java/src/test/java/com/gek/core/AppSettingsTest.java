package com.gek.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {
    private final ObjectMapper M = new ObjectMapper();

    @Test void defaults() {
        AppSettings s = AppSettings.defaults();
        assertNotNull(s);
        assertEquals("Balanced", s.preset);
        assertEquals("small", s.whisper_model);
        assertEquals("native", s.inference_mode);
        assertEquals("en", s.target_language);
        assertEquals("auto", s.source_language);
        assertEquals("CTRL+SHIFT+SPACE", s.dictation_hotkey);
        assertEquals("toggle", s.dictation_mode);
        assertEquals("transcript", s.dictation_insert_mode);
        assertTrue(s.diarization_enabled);
        assertTrue(s.dictation_enabled);
        assertTrue(s.stt_enabled);
        assertTrue(s.tts_enabled);
        assertNotNull(s.extra);
        assertTrue(s.extra.isEmpty());
    }

    @Test void unknownFieldsCapturedInExtra() throws Exception {
        String json = "{\"preset\":\"Fast\",\"unknown_key\":\"value\",\"mystery\":42}";
        AppSettings s = M.readValue(json, AppSettings.class);
        assertEquals("Fast", s.preset);
        assertEquals("value", s.extra.get("unknown_key"));
        assertEquals(42, ((Number) s.extra.get("mystery")).intValue());
    }

    @Test void extraRoundTrips() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.putExtra("custom", "x");
        String json = M.writeValueAsString(s);
        assertTrue(json.contains("custom"));
        AppSettings back = M.readValue(json, AppSettings.class);
        assertEquals("x", back.extra.get("custom"));
    }

    @Test void putExtraInitializesWhenNull() {
        AppSettings s = new AppSettings();
        s.extra = null;
        s.putExtra("k", "v");
        assertNotNull(s.extra);
        assertEquals("v", s.extra.get("k"));
        assertSame(s.extra, s.getExtra());
    }
}
