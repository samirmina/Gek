package com.gek.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsStore {
    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);
    private final Path path;
    private final ObjectMapper mapper;

    public SettingsStore(Path path) {
        this.path = path;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public AppSettings load() {
        if (!Files.exists(path)) {
            log.info("Settings not found at {}, using defaults", path);
            return normalize(AppSettings.defaults());
        }
        try {
            AppSettings s = mapper.readValue(path.toFile(), AppSettings.class);
            if (s == null) s = AppSettings.defaults();
            return normalize(s);
        } catch (Exception e) {
            log.warn("Failed to load settings {}, using defaults: {}", path, e.getMessage());
            return normalize(AppSettings.defaults());
        }
    }

    private static AppSettings normalize(AppSettings s) {
        if (s == null) s = AppSettings.defaults();
        if (s.inference_mode == null || s.inference_mode.isBlank() || "auto".equalsIgnoreCase(s.inference_mode.trim())) {
            s.inference_mode = "native";
        } else {
            s.inference_mode = s.inference_mode.trim().toLowerCase();
        }
        if (s.sidecar_python == null) s.sidecar_python = "";
        if (s.source_language == null || s.source_language.isBlank()) s.source_language = "auto";
        if (s.target_language == null || s.target_language.isBlank()) s.target_language = "en";
        return s;
    }

    public void save(AppSettings settings) {
        try {
            Files.createDirectories(path.getParent());
            mapper.writeValue(path.toFile(), settings);
        } catch (IOException e) {
            log.error("Failed to save settings {}: {}", path, e.getMessage(), e);
        }
    }
}
