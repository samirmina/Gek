package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Selects inference engines. Defaulting toward Java-native migration: "native" (or unset in future) uses
 * Java engines; "sidecar" is an explicit, temporary fallback for environments that still have Python ML deps.
 */
public final class EngineFactory {
    private static final Logger log = LoggerFactory.getLogger(EngineFactory.class);
    private EngineFactory() {}

    public record Engines(SpeechToTextEngine stt, TranslationEngine translator, DiarizationEngine diarizer,
                          SidecarClient sidecarOrNull, String mode, String note) implements AutoCloseable {
        @Override public void close() {
            try { if (stt != null) stt.close(); } catch (Exception ignored) {}
            try { if (translator != null && translator != stt) translator.close(); } catch (Exception ignored) {}
            try { if (diarizer != null && diarizer != stt && diarizer != translator) diarizer.close(); } catch (Exception ignored) {}
        }
    }

    public static Engines create(AppPaths paths, AppSettings settings, boolean allowSidecarFallback) {
        String raw = settings != null && settings.inference_mode != null ? settings.inference_mode.trim().toLowerCase(Locale.ROOT) : "native";
        boolean wantSidecar = "sidecar".equals(raw) || "python".equals(raw);
        boolean wantNative = raw.isEmpty() || "native".equals(raw) || "auto".equals(raw) || "java".equals(raw);
        if (wantSidecar && allowSidecarFallback) {
            SidecarClient sc = new SidecarClient(paths, settings);
            String note = "Using Python sidecar (temporary fallback, explicitly selected). Migration target is Java-native inference (Option C).";
            log.info(note);
            return new Engines(sc, sc, sc, sc, "sidecar", note);
        }
        if (wantSidecar && !allowSidecarFallback) {
            String note = "Sidecar requested but disabled for this command; using native Java engine placeholders.";
            log.info(note);
            return nativeEngines(paths, settings, "native", note);
        }
        // native/default: prefer Java path (no Python dependency)
        String note = "Using Java-native inference mode (no Python dependency).";
        if (!wantNative) {
            note = "Unknown inference_mode='" + raw + "'; defaulting to Java-native mode (no Python dependency).";
            log.warn(note);
        }
        return nativeEngines(paths, settings, "native", note);
    }

    private static Engines nativeEngines(AppPaths paths, AppSettings settings, String mode, String note) {
        NativeSpeechToTextEngine stt = new NativeSpeechToTextEngine(paths, settings);
        NativeTranslationEngine tr = new NativeTranslationEngine(paths);
        NativeDiarizationEngine di = new NativeDiarizationEngine(paths);
        String effectiveNote = note;
        if (note == null || note.contains("Java-native inference mode")) {
            StringBuilder sb = new StringBuilder("Java-native inference mode. ");
            sb.append("STT: ").append(stt.runtimeUsable() ? ("READY (" + (stt.inJvm() ? "in-process libwhisper JNA shim" : "whisper.cpp/GGML subprocess") + ", no Python/subprocess)") : "NOT READY");
            sb.append("; Translation: ").append(tr.runtimeUsable() ? ("READY (" + (tr.inJvm() ? "in-process embedded ctranslate2+SentencePiece shim" : "local helper subprocess") + ", no sidecar server)") : "NOT READY");
            sb.append("; Diarization: ").append(di.runtimeUsable() ? "READY" : "NOT READY").append(".");
            effectiveNote = sb.toString();
        }
        return new Engines(stt, tr, di, null, mode, effectiveNote);
    }
}
