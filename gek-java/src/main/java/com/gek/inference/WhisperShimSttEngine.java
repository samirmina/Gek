package com.gek.inference;

import com.gek.audio.WavReader;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process JVM STT via libwhisper (whisper.cpp) + local GGML model using JNA shim.
 * Zero Python, no subprocess.
 */
public class WhisperShimSttEngine implements SpeechToTextEngine {
    private static final Logger log = LoggerFactory.getLogger(WhisperShimSttEngine.class);

    private final AppPaths paths;
    private final AppSettings settings;
    private final NativeRuntimeProbe.SttRuntime runtime;
    private final String reason;
    private final Object lock = new Object();
    private WhisperCppLib lib;
    private Pointer ctx;
    private boolean initAttempted;

    public WhisperShimSttEngine(AppPaths paths, AppSettings settings) {
        this.paths = paths;
        this.settings = settings;
        String requested = settings != null && settings.whisper_model != null ? settings.whisper_model : "small";
        this.runtime = NativeRuntimeProbe.resolveInJvmSttRuntime(paths, requested);
        this.reason = runtime.reason();
    }

    @Override public CompletableFuture<Void> warmup() {
        if (!runtime.usable()) return CompletableFuture.failedFuture(new InferenceUnavailableException(reason));
        return CompletableFuture.supplyAsync(() -> {
            ensureContext();
            return null;
        });
    }

    @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
        if (wavPath == null) return CompletableFuture.failedFuture(new InferenceUnavailableException("wavPath is required"));
        if (!runtime.usable()) return CompletableFuture.failedFuture(new InferenceUnavailableException(reason));
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureContext();
                if (!Files.exists(wavPath)) throw new InferenceUnavailableException("Audio file not found: " + wavPath);
                WavReader.AudioData ad = WavReader.readMono(wavPath, 16000);
                float[] samples = ad.samples();
                if (samples == null || samples.length == 0) {
                    return new TranscriptionResult("", normalizeLang(languageHint), 0.0, 0.0);
                }
                WhisperCppLib.ShimResult out = new WhisperCppLib.ShimResult();
                String hint = normalizeLang(languageHint);
                int rc;
                synchronized (lock) {
                    if (ctx == null || lib == null) throw new InferenceUnavailableException("whisper context not initialized: " + reason);
                    rc = lib.gek_whisper_transcribe(ctx, samples, samples.length, hint, out);
                }
                try {
                    if (rc != 0) {
                        throw new InferenceUnavailableException("In-JVM whisper (libwhisper) failed rc=" + rc + " model=" + runtime.model().orElse(null));
                    }
                    String text = out.text == null ? "" : out.text.getString(0, "UTF-8");
                    String lang = out.lang == null ? hint : out.lang.getString(0, "UTF-8");
                    if (lang != null) lang = lang.trim().toLowerCase(Locale.ROOT);
                    if (lang != null && lang.isEmpty()) lang = hint;
                    String detectedOrHint = (hint != null ? hint : lang);
                    double dur = ad.durationSeconds();
                    return new TranscriptionResult(text == null ? "" : text.trim(), detectedOrHint, 0.0, dur);
                } finally {
                    try { if (lib != null) lib.gek_whisper_free_result(out); } catch (Throwable ignored) {}
                }
            } catch (InferenceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new InferenceUnavailableException("In-JVM whisper transcription failed: " + msg, e);
            }
        });
    }

    private void ensureContext() {
        synchronized (lock) {
            if (ctx != null && lib != null) return;
            if (initAttempted && (ctx == null || lib == null)) {
                throw new InferenceUnavailableException("whisper context init previously failed: " + reason);
            }
            initAttempted = true;
            if (!runtime.usable()) throw new InferenceUnavailableException(reason);
            String libPath = runtime.binary().orElseThrow(() -> new InferenceUnavailableException("libwhisper shim path missing"));
            String modelPath = runtime.model().orElseThrow(() -> new InferenceUnavailableException("GGML model missing")).toString();
            try {
                lib = WhisperCppLib.tryLoad(libPath);
                if (lib == null) throw new InferenceUnavailableException("Failed to load whisper shim library: " + libPath);
                ctx = lib.gek_whisper_init(modelPath);
                if (ctx == null || Pointer.nativeValue(ctx) == 0) {
                    ctx = null;
                    throw new InferenceUnavailableException("Failed to initialize whisper context for model: " + modelPath);
                }
                log.info("In-JVM whisper ready (shim={}, model={})", libPath, modelPath);
            } catch (InferenceUnavailableException e) {
                throw e;
            } catch (Throwable t) {
                lib = null; ctx = null;
                throw new InferenceUnavailableException("Failed to init in-JVM whisper: " + t.getMessage(), t);
            }
        }
    }

    private static String normalizeLang(String hint) {
        if (hint == null) return null;
        String v = hint.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "auto".equals(v) || "detected".equals(v) || "auto-detect".equals(v)) return null;
        return v;
    }

    @Override public void close() {
        synchronized (lock) {
            try { if (lib != null && ctx != null) lib.gek_whisper_free(ctx); } catch (Throwable ignored) {}
            ctx = null; lib = null;
        }
    }

    public String reason() { return reason; }
    public boolean runtimeUsable() { return runtime.usable(); }
}
