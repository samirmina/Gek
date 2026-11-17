package com.gek.inference;

import com.gek.core.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-native translation engine (Option C).
 * Prefers in-process embedded ctranslate2+SentencePiece (JNA + embedded CPython runtime, no subprocess)
 * when shim+venv runtime is available; falls back to local helper subprocess over Argos packages.
 */
public class NativeTranslationEngine implements TranslationEngine {
    private final AppPaths paths;
    private final TranslationEngine delegate;
    private final NativeRuntimeProbe.TranslationRuntime runtime;
    private final String reason;
    private final boolean inJvm;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public NativeTranslationEngine(AppPaths paths) {
        this.paths = paths;
        NativeRuntimeProbe.TranslationRuntime jvmRt = NativeRuntimeProbe.resolveInJvmTranslationRuntime(paths);
        if (jvmRt.usable()) {
            EmbeddedTranslationEngine emb = new EmbeddedTranslationEngine(paths);
            this.delegate = emb;
            this.runtime = jvmRt;
            this.inJvm = true;
            this.reason = "In-process JVM translation ready (embedded ctranslate2+SentencePiece, no subprocess). " + emb.reason();
        } else {
            this.delegate = null;
            NativeRuntimeProbe.TranslationRuntime procRt = NativeRuntimeProbe.resolveTranslationRuntime(paths);
            this.runtime = procRt;
            this.inJvm = false;
            this.reason = procRt.reason();
        }
    }

    @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
        if (delegate != null) return delegate.translate(text, sourceLang, targetLang);
        String in = text == null ? "" : text;
        String src = normalize(sourceLang);
        String tgt = normalize(targetLang);
        if (in.isBlank() || (src != null && tgt != null && src.equalsIgnoreCase(tgt))) {
            return CompletableFuture.completedFuture(in);
        }
        if (src == null || "auto".equalsIgnoreCase(src) || "detected".equalsIgnoreCase(src)) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException(
                    "Native translation requires explicit source language (auto-detect not available without sidecar). " + hintReason()));
        }
        if (tgt == null || tgt.isBlank()) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException("Target language is required. " + hintReason()));
        }
        if (!runtime.usable()) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException(reason));
        }
        Optional<Path> pkg = NativeRuntimeProbe.findArgosPackage(paths, src, tgt);
        if (pkg.isEmpty()) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException(
                    "No usable Argos package found for " + src + "->" + tgt
                            + " (requires CTranslate2 model + SentencePiece tokenizer; BPE-only packages are unsupported by this helper). "
                            + hintReason()));
        }
        String cacheInput = in.length() > 4000 ? in.substring(0, 4000) : in;
        final String key = src + "\u0000" + tgt + "\u0000" + cacheInput.hashCode() + "\u0000" + in.length();
        String cached = cache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return CompletableFuture.supplyAsync(() -> {
            String c2 = cache.get(key);
            if (c2 != null) return c2;
            try {
                String out = runTranslate(pkg.get(), in);
                String cleaned = sanitize(out);
                if (cleaned == null) cleaned = "";
                if (cache.size() > 1024) cache.clear();
                cache.putIfAbsent(key, cleaned);
                return cleaned;
            } catch (InferenceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new InferenceUnavailableException("Native Argos translation failed for " + src + "->" + tgt + ": " + msg, e);
            }
        });
    }

    private String runTranslate(Path pkg, String text) throws Exception {
        String py = runtime.python().orElseThrow(() -> new InferenceUnavailableException("Translation helper python not found. " + hintReason()));
        Path script = runtime.script().orElseThrow(() -> new InferenceUnavailableException("Translation helper script not found. " + hintReason()));
        if (!Files.isDirectory(pkg)) throw new InferenceUnavailableException("Argos package not found: " + pkg);
        String input = text == null ? "" : text;
        if (input.length() > 12000) input = input.substring(0, 12000);
        List<String> cmd = new ArrayList<>();
        cmd.add(py);
        cmd.add(script.toAbsolutePath().toString());
        cmd.add("--package"); cmd.add(pkg.toAbsolutePath().toString());
        cmd.add("--text"); cmd.add(input);
        NativeProcessResult r = NativeProcessRunner.run(cmd, paths != null ? paths.projectRoot() : null, null, Duration.ofMinutes(2));
        if (r.exitCode() != 0) {
            String err = r.stderr() == null ? "" : r.stderr().trim();
            String out = r.stdout() == null ? "" : r.stdout().trim();
            String detail = !err.isEmpty() ? err : out;
            throw new InferenceUnavailableException("translator helper exit " + r.exitCode() + (detail.isEmpty() ? "" : (": " + trim(detail, 700))));
        }
        return extractOutput(r.stdout());
    }

    private static String extractOutput(String stdout) {
        if (stdout == null) return "";
        String[] lines = stdout.split("\r?\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return stripOuter(out.toString());
    }

    private static String stripOuter(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && Character.isWhitespace(s.charAt(start))) start++;
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        return start <= 0 && end >= s.length() ? s : s.substring(start, end);
    }

    private static String sanitize(String s) {
        if (s == null) return null;
        String out = s.replace("\u2581", " ").replace("\u2047", "").replace('\u00A0', ' ');
        String[] lines = out.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i].replaceAll("[ \\t]+", " ").trim());
        }
        return stripOuter(sb.toString());
    }

    private String hintReason() {
        String r = reason == null ? "" : reason;
        if (r.length() > 220) r = r.substring(0, 217) + "...";
        return r;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 3)) + "...";
    }

    @Override public void close() {
        if (delegate instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }
    public String reason() { return reason; }
    public boolean runtimeUsable() { return runtime.usable() || (delegate instanceof EmbeddedTranslationEngine e && e.runtimeUsable()); }
    public boolean inJvm() { return inJvm; }
}
