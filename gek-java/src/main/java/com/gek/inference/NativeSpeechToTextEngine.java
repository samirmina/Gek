package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Java-native STT engine (P0/P1 contract: native mode targets in-process JVM inference).
 * Prefers in-process libwhisper via JNA shim when available (zero subprocess/Python);
 * falls back to whisper.cpp subprocess backend when shim is unavailable but CLI+GGML are present.
 */
public class NativeSpeechToTextEngine implements StreamingSpeechToTextEngine {
    private final AppPaths paths;
    private final AppSettings settings;
    private final SpeechToTextEngine delegate;
    private final NativeRuntimeProbe.SttRuntime runtime;
    private final String reason;
    private final boolean inJvm;

    public NativeSpeechToTextEngine(AppPaths paths, AppSettings settings) {
        this.paths = paths;
        this.settings = settings;
        String requested = settings != null && settings.whisper_model != null ? settings.whisper_model : "small";
        NativeRuntimeProbe.SttRuntime jvmRt = NativeRuntimeProbe.resolveInJvmSttRuntime(paths, requested);
        NativeRuntimeProbe.SttRuntime resolved = jvmRt.usable() ? jvmRt : NativeRuntimeProbe.resolveSttRuntime(paths, requested);

        // Prefer a persistent warm whisper-server backend when available (keeps model loaded;
        // accelerates repeated transcribe/streaming window decodes). Falls back to shim/CLI.
        WhisperServerSttEngine server = resolved.usable() ? WhisperServerSttEngine.tryCreate(paths, settings, resolved) : null;
        if (server != null) {
            this.delegate = server;
            this.runtime = resolved;
            this.inJvm = false;
            this.reason = "Warm whisper-server STT backend active (model kept loaded). " + server.reason();
            return;
        }

        if (jvmRt.usable()) {
            WhisperShimSttEngine shim = new WhisperShimSttEngine(paths, settings);
            this.delegate = shim;
            this.runtime = jvmRt;
            this.inJvm = true;
            this.reason = "In-process JVM STT ready (libwhisper JNA shim, no subprocess/Python). " + shim.reason();
        } else {
            NativeRuntimeProbe.SttRuntime procRt = resolved;
            this.delegate = null;
            this.runtime = procRt;
            this.inJvm = false;
            this.reason = procRt.reason();
        }
    }

    @Override public CompletableFuture<Void> warmup() {
        if (delegate != null) return delegate.warmup();
        if (runtime.usable()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.failedFuture(new InferenceUnavailableException(reason));
    }

    @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
        if (wavPath == null) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException("wavPath is required"));
        }
        if (delegate != null) return delegate.transcribe(wavPath, languageHint);
        if (!runtime.usable()) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException(reason));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transcribeWithWhisperCpp(wavPath, languageHint);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new InferenceUnavailableException("Native whisper.cpp transcription failed: " + msg + " | " + reason, e);
            }
        });
    }

    private TranscriptionResult transcribeWithWhisperCpp(Path wavPath, String languageHint) throws Exception {
        if (!Files.exists(wavPath)) throw new InferenceUnavailableException("Audio file not found: " + wavPath);
        List<String> cmd = new ArrayList<>();
        cmd.add(runtime.binary().orElseThrow());
        cmd.add("-m"); cmd.add(runtime.model().orElseThrow().toString());
        cmd.add("-f"); cmd.add(wavPath.toAbsolutePath().toString());
        cmd.add("-nt"); // no timestamps
        String reqLang = normalizeLang(languageHint);
        if (reqLang != null) { cmd.add("-l"); cmd.add(reqLang); } else { cmd.add("-l"); cmd.add("auto"); }
        NativeProcessResult r = NativeProcessRunner.run(cmd, paths != null ? paths.projectRoot() : null, null, Duration.ofMinutes(10));
        String combined = (r.stdout() == null ? "" : r.stdout()) + "\n" + (r.stderr() == null ? "" : r.stderr());
        String text = extractText(r.stdout(), r.stderr());
        String detected = extractDetectedLanguage(combined);
        String outLang = reqLang != null ? reqLang : detected;
        if (isFailureOutput(combined, text) || (r.exitCode() != 0 && (text == null || text.isBlank()))) {
            throw new InferenceUnavailableException("whisper.cpp failed (exit " + r.exitCode() + "): " + trim(combined, 800));
        }
        return new TranscriptionResult(text == null ? "" : text.trim(), outLang, 0.0, 0.0);
    }

    private static boolean isFailureOutput(String combined, String extracted) {
        String c = combined == null ? "" : combined.toLowerCase(Locale.ROOT);
        String e = extracted == null ? "" : extracted.trim().toLowerCase(Locale.ROOT);
        if (c.contains("failed to initialize whisper context") || c.contains("error not all tensors loaded")
                || c.contains("failed to load model") || c.contains("error: failed to")) {
            if (e.isEmpty() || e.startsWith("error:") || e.contains("failed to initialize") || e.contains("failed to load")) return true;
        }
        if (e.startsWith("error: failed") || e.contains("failed to initialize whisper context")) return true;
        return false;
    }

    private static String extractText(String stdout, String stderr) {
        // Prefer stdout lines that look like transcript (strip common timestamp prefixes)
        String best = cleanTranscript(stdout);
        if (!best.isBlank()) return best;
        return cleanTranscript(stderr);
    }

    private static String extractDetectedLanguage(String all) {
        if (all == null) return null;
        try (BufferedReader br = new BufferedReader(new StringReader(all))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                // whisper.cpp prints: "whisper_full_with_state: auto-detected language: en (p = 0.958783)"
                int i = t.toLowerCase(Locale.ROOT).indexOf("auto-detected language:");
                if (i >= 0) {
                    String rest = t.substring(i + "auto-detected language:".length()).trim();
                    if (rest.isEmpty()) continue;
                    // take token before space/(
                    int sp = rest.indexOf(' ');
                    String lang = sp >= 0 ? rest.substring(0, sp) : rest;
                    lang = lang.replaceAll("[^a-zA-Z0-9_-]", "").trim().toLowerCase(Locale.ROOT);
                    if (!lang.isEmpty()) return lang;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String cleanTranscript(String s) {
        if (s == null || s.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new StringReader(s))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                // skip progress/log lines and whisper.cpp runtime noise (stdout/stderr, incl. CUDA init)
                if (t.startsWith("whisper_") || t.startsWith("main:") || t.startsWith("system_info") || t.startsWith("ggml_")
                        || t.startsWith("load_backend:") || t.startsWith("read_audio_data:") || t.startsWith("whisper_backend")
                        || t.startsWith("register_backend:") || t.startsWith("init_backend:") || t.startsWith("gguf_")
                        || t.startsWith("sampling:") || t.startsWith("whisper_init") || t.startsWith("whisper_model_load")
                        || t.startsWith("ggml_cuda_init") || t.startsWith("Device ") || t.startsWith("CUDA0")
                        || t.contains("loaded CPU backend from") || t.contains("trying to decode with miniaudio")
                        || t.contains("compute capability") || t.contains("CUDA devices")) continue;
                // strip "[00:00:00.000 --> 00:00:02.000] text"
                if (t.startsWith("[") && t.contains("]")) {
                    int idx = t.indexOf(']');
                    if (idx >= 0 && idx + 1 < t.length()) {
                        String rest = t.substring(idx + 1).trim();
                        if (rest.matches("^\\[[0-9:.]+ --> [0-9:.]+\\].*")) {
                            int idx2 = rest.indexOf(']');
                            if (idx2 >= 0) rest = rest.substring(idx2 + 1).trim();
                        }
                        t = rest;
                    }
                }
                if (t.isEmpty() || t.equals("[BLANK_AUDIO]")) continue;
                if (t.startsWith("[_BEG_]") || t.startsWith("[_TT_")) continue;
                if (out.length() > 0) out.append(' ');
                out.append(t);
            }
        } catch (Exception ignored) {}
        return out.toString().trim();
    }

    private static String normalizeLang(String hint) {
        if (hint == null) return null;
        String v = hint.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "auto".equals(v) || "detected".equals(v) || "auto-detect".equals(v)) return null;
        return v;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    @Override public void close() {
        if (delegate instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }
    public String reason() { return reason; }
    public boolean runtimeUsable() { return runtime.usable(); }
    public boolean inJvm() { return inJvm; }

    @Override
    public StreamingSttSession startSession(String languageHint, StreamingTranscriptionListener listener) {
        SpeechToTextEngine backend = delegate != null ? delegate : this;
        java.nio.file.Path tmp = paths != null ? paths.dataDir().resolve("tmp") : null;
        return new WindowedStreamingSession(backend, languageHint, listener, tmp);
    }

    /** Partials available when an STT backend is usable (warmest when in-JVM). */
    @Override
    public boolean streamingAvailable() { return runtimeUsable(); }
}
