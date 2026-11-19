package com.gek.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Calibration of default settings and extra-field handling (portable). */
class AppSettingsDefaultsTest {

    @Test
    void defaultsAreStableForCoreRuntimeFields() {
        AppSettings s = AppSettings.defaults();
        assertEquals("Balanced", s.preset);
        assertEquals("small", s.whisper_model);
        assertEquals("cpu", s.whisper_device);
        assertEquals("int8", s.whisper_compute_type);
        assertEquals(Integer.valueOf(5), s.whisper_beam_size);
        assertNull(s.whisper_language);
        assertEquals(Double.valueOf(1.0), s.tts_speed);
        assertEquals("native", s.inference_mode);
        assertEquals("auto", s.source_language);
        assertEquals("en", s.target_language);
        assertEquals("", s.sidecar_python);
        assertEquals("CTRL+SHIFT+SPACE", s.dictation_hotkey);
        assertEquals("toggle", s.dictation_mode);
        assertEquals("transcript", s.dictation_insert_mode);
        assertEquals(Boolean.TRUE, s.diarization_enabled);
        assertEquals(Boolean.TRUE, s.stt_enabled);
        assertEquals(Boolean.TRUE, s.tts_enabled);
        assertEquals(Boolean.TRUE, s.auto_mute_during_tts);
        assertNotNull(s.extra);
        assertTrue(s.extra.isEmpty());
    }

    @Test
    void extraFieldsRoundTripThroughAccessors() {
        AppSettings s = new AppSettings();
        s.putExtra("k1", "v1");
        s.putExtra("k2", 7);
        Map<String, Object> ex = s.getExtra();
        assertEquals("v1", ex.get("k1"));
        assertEquals(7, ((Number) ex.get("k2")).intValue());
        // putExtra should tolerate null extra map defensively
        s.extra = null;
        s.putExtra("k3", true);
        assertNotNull(s.extra);
        assertEquals(Boolean.TRUE, s.extra.get("k3"));
    }
}
