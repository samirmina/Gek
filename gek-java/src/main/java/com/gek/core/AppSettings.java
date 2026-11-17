package com.gek.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppSettings {
    // Existing Python keys (compat)
    public String preset = "Balanced";
    public String whisper_model = "small";
    public String whisper_device = "cpu";
    public String whisper_compute_type = "int8";
    public Integer whisper_beam_size = 5;
    public String whisper_language = null; // null/auto
    public Double tts_speed = 1.0;
    public Double hud_opacity = 0.95;
    public String audio_loopback = null;
    public String audio_mic = null;
    public Double chunk_duration = 0.25;
    public Integer silence_duration_ms = 300;
    public Double min_chunk_duration = 0.15;
    public Double max_chunk_duration = 4.0;
    public Boolean diarization_enabled = Boolean.TRUE;
    public Integer diarization_max_speakers = 4;
    public Double process_poll_interval = 2.0;
    public Double tts_duck_level = 0.2;
    public Double tts_volume = 1.0;
    public Double whisper_idle_timeout = 0.0;
    public Double whisper_transcribe_timeout_factor = 0.0;
    public Double whisper_no_speech_threshold = 0.6;
    public Double whisper_log_prob_threshold = -1.0;
    public Double whisper_compression_ratio_threshold = 2.4;
    public Boolean whisper_condition_on_previous_text = Boolean.FALSE;
    public Boolean stt_enabled = Boolean.TRUE;
    public Boolean tts_enabled = Boolean.TRUE;
    public Boolean auto_mute_during_tts = Boolean.TRUE;

    // Java-port additions (superset)
    public String target_language = "en";
    public String source_language = "auto";
    public String dictation_hotkey = "CTRL+SHIFT+SPACE";
    public String dictation_mode = "toggle"; // toggle | push_to_talk
    public String dictation_insert_mode = "transcript"; // transcript | translated
    public Boolean dictation_enabled = Boolean.TRUE;
    public String inference_mode = "native"; // native (default, Java migration path) | sidecar (temporary Python fallback, requires ML deps)
    public String sidecar_python = ""; // optional override path
    public Map<String, Object> extra = new LinkedHashMap<>();

    @JsonAnySetter
    public void putExtra(String key, Object value) {
        if (extra == null) extra = new LinkedHashMap<>();
        extra.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }

    public static AppSettings defaults() { return new AppSettings(); }
}
