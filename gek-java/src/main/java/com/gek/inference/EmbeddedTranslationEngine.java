package com.gek.inference;

import com.gek.core.AppPaths;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process JVM translation via embedded CPython + ctranslate2/sentencepiece (JNA shim).
 * No Python subprocess, no argostranslate sidecar server.
 */
public class EmbeddedTranslationEngine implements TranslationEngine {
    private final AppPaths paths;
    private final NativeRuntimeProbe.TranslationRuntime runtime;
    private final String reason;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private EmbeddedTranslationLib lib;
    private boolean initAttempted;

    public EmbeddedTranslationEngine(AppPaths paths) {
        this.paths = paths;
        this.runtime = NativeRuntimeProbe.resolveInJvmTranslationRuntime(paths);
        this.reason = runtime.reason();
    }

    @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
        String in = text == null ? "" : text;
        String src = normalize(sourceLang);
        String tgt = normalize(targetLang);
        if (in.isBlank() || (src != null && tgt != null && src.equalsIgnoreCase(tgt))) {
            return CompletableFuture.completedFuture(in);
        }
        if (src == null || "auto".equalsIgnoreCase(src) || "detected".equalsIgnoreCase(src)) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException(
                    "Native translation requires explicit source language (auto-detect not available). " + hintReason()));
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
                            + " (requires CTranslate2 model + SentencePiece tokenizer; BPE-only packages are unsupported). "
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
                ensureRuntime();
                String input = in.length() > 12000 ? in.substring(0, 12000) : in;
                PointerByReference errRef = new PointerByReference();
                Pointer outPtr;
                synchronized (lock) {
                    if (lib == null) throw new InferenceUnavailableException("In-JVM translation runtime not initialized: " + reason);
                    outPtr = lib.gek_translate(pkg.get().toAbsolutePath().toString(), input, errRef);
                }
                String err = EmbeddedTranslationLib.takeError(errRef, lib);
                try {
                    if (outPtr == null || Pointer.nativeValue(outPtr) == 0) {
                        String msg = (err != null && !err.isBlank()) ? err : "translation returned null";
                        throw new InferenceUnavailableException("In-JVM Argos translation failed for " + src + "->" + tgt + ": " + msg);
                    }
                    String cleaned = sanitize(outPtr.getString(0, "UTF-8"));
                    if (cleaned == null) cleaned = "";
                    if (cache.size() > 1024) cache.clear();
                    cache.putIfAbsent(key, cleaned);
                    return cleaned;
                } finally {
                    if (outPtr != null && Pointer.nativeValue(outPtr) != 0) {
                        try { lib.gek_translate_free_str(outPtr); } catch (Throwable ignored) {}
                    }
                }
            } catch (InferenceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new InferenceUnavailableException("In-JVM Argos translation failed for " + src + "->" + tgt + ": " + msg, e);
            }
        });
    }

    private void ensureRuntime() {
        synchronized (lock) {
            if (lib != null) return;
            if (initAttempted) throw new InferenceUnavailableException("In-JVM translation init previously failed: " + reason);
            initAttempted = true;
            if (!runtime.usable()) throw new InferenceUnavailableException(reason);
            String shim = runtime.shimLibrary().orElseThrow(() -> new InferenceUnavailableException("translation shim missing"));
            String home = runtime.pythonHome().orElseThrow(() -> new InferenceUnavailableException("venv home missing"));
            String site = runtime.sitePackages().orElseThrow(() -> new InferenceUnavailableException("site-packages missing"));
            try {
                lib = EmbeddedTranslationLib.tryLoad(shim);
                if (lib == null) throw new InferenceUnavailableException("Failed to load translation shim: " + shim);
                PointerByReference errRef = new PointerByReference();
                int rc = lib.gek_translate_init(home, site, errRef);
                String err = EmbeddedTranslationLib.takeError(errRef, lib);
                if (rc != 0) {
                    lib = null;
                    throw new InferenceUnavailableException("Failed to init in-JVM translation runtime rc=" + rc + (err != null && !err.isBlank() ? (": " + err) : ""));
                }
            } catch (InferenceUnavailableException e) {
                throw e;
            } catch (Throwable t) {
                lib = null;
                throw new InferenceUnavailableException("Failed to init in-JVM translation runtime: " + t.getMessage(), t);
            }
        }
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

    private static String stripOuter(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && Character.isWhitespace(s.charAt(start))) start++;
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        return start <= 0 && end >= s.length() ? s : s.substring(start, end);
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

    @Override public void close() { /* keep embedded interpreter for process lifetime */ }
    public String reason() { return reason; }
    public boolean runtimeUsable() { return runtime.usable(); }
}
