package com.gek.app;

import com.gek.audio.AutoAudioSelector;
import com.gek.audio.MicCapture;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.core.PlatformSupport;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.StreamingSttSession;
import com.gek.inference.StreamingTranscriptionListener;
import com.gek.inference.TranslationEngine;
import com.gek.inference.WindowedStreamingSession;
import com.gek.inject.InputAction;
import com.gek.inject.InputExecutor;
import com.gek.inject.RobotInputExecutor;
import com.gek.inject.VoiceCommandInterpreter;
import com.gek.inject.WindowsFocusManager;
import com.gek.text.SynonymReplacer;
import com.gek.text.TranscriptSanitizer;
import com.gek.text.TranslationPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Chat voice-typing (decoupled from live captions/HUD), Claude-like single-update flow.
 * Captures mic while listening but does not inject evolving partials into the field; on hotkey
 * stop/finalize it sanitizes once, composes the final text (translation-oriented with safe fallback),
 * and performs a single in-field update leaving text for review/edit/send (no auto-send). No GEK
 * overlay, no HUD/live-caption coupling.
 */
public class ChatVoiceTypingController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatVoiceTypingController.class);

    private final AppPaths paths;
    private final AppSettings settings;
    private final SpeechToTextEngine stt;
    private final TranslationEngine translator;
    private final InputExecutor input;
    private final VoiceCommandInterpreter commands = new VoiceCommandInterpreter();
    private final HistoryRepository history; // optional
    private final SynonymReplacer synonyms;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gek-chat-typing"); t.setDaemon(true); return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean finalHandled = new AtomicBoolean(false);
    private final AtomicReference<StreamingSttSession> sessionRef = new AtomicReference<>();
    private volatile MicCapture mic;
    private volatile Consumer<String> statusListener;
    private volatile Consumer<String> partialListener;

    // In-field finalization state. Guarded by fieldLock for replace consistency.
    private final Object fieldLock = new Object();
    private String lastInjectedText = "";
    private boolean autoUpdatePaused = false;
    private final AtomicLong partialSeq = new AtomicLong(0);

    public ChatVoiceTypingController(AppPaths paths, AppSettings settings, SpeechToTextEngine stt,
                                     TranslationEngine translator, HistoryRepository history) {
        this(paths, settings, stt, translator, history, RobotInputExecutor.forPlatform());
    }

    public ChatVoiceTypingController(AppPaths paths, AppSettings settings, SpeechToTextEngine stt,
                                     TranslationEngine translator, HistoryRepository history,
                                     InputExecutor input) {
        this.paths = paths; this.settings = settings; this.stt = stt; this.translator = translator;
        this.history = history;
        this.input = input != null ? input : RobotInputExecutor.forPlatform();
        this.synonyms = SynonymReplacer.load(paths.synonymsDir());
    }

    public void setStatusListener(Consumer<String> statusListener) { this.statusListener = statusListener; }
    /** Receives observer updates (single-update flow: no in-field partial injection). */
    public void setPartialListener(Consumer<String> partialListener) { this.partialListener = partialListener; }

    private void status(String s) {
        log.info("ChatTyping: {}", s);
        if (statusListener != null) try { statusListener.accept(s); } catch (Exception ignored) {}
    }
    private void partial(String s) {
        if (partialListener != null) try { partialListener.accept(s == null ? "" : s); } catch (Exception ignored) {}
    }

    public synchronized void start() throws Exception {
        if (running.get()) return;
        finalHandled.set(false);
        partialSeq.set(0);
        synchronized (fieldLock) {
            lastInjectedText = "";
            autoUpdatePaused = false;
        }
        status("starting voice typing...");
        try { stt.warmup().exceptionally(e -> { log.warn("warmup issue: {}", e.getMessage()); return null; }); } catch (Exception e) { log.warn("warmup: {}", e.getMessage()); }

        String langHint = normalizeLang(settings.source_language);
        Path tmpDir = paths.dataDir().resolve("tmp");
        StreamingTranscriptionListener listener = new StreamingTranscriptionListener() {
            @Override public void onPartial(String text) {
                handleStreamingPartial(text, langHint);
            }
            @Override public void onPartial(String text, String detectedLang) {
                String effective = effectiveLang(detectedLang, langHint);
                handleStreamingPartial(text, effective);
            }
            @Override public void onFinal(String text) {
                if (finalHandled.compareAndSet(false, true)) {
                    exec.submit(() -> finalizeText(text, langHint));
                }
            }
            @Override public void onFinal(String text, String detectedLang) {
                if (finalHandled.compareAndSet(false, true)) {
                    String effective = effectiveLang(detectedLang, langHint);
                    exec.submit(() -> finalizeText(text, effective));
                }
            }
            @Override public void onError(Throwable t) {
                log.warn("streaming error: {}", t == null ? "unknown" : t.getMessage());
                status("error: " + (t == null ? "unknown" : rootMsg(t)));
            }
        };
        StreamingSttSession session = new WindowedStreamingSession(stt, langHint, listener, tmpDir,
                WindowedStreamingSession.ACCUMULATING_MAX_WINDOW_SECONDS);
        sessionRef.set(session);

        int sr = 16000;
        float chunk = val(settings.chunk_duration, 0.25f);
        mic = new MicCapture(sr, chunk, AutoAudioSelector.resolveMicHint(settings));
        mic.setConsumer(chunkData -> {
            StreamingSttSession s = sessionRef.get();
            if (running.get() && s instanceof WindowedStreamingSession wss) {
                wss.accept(chunkData);
            }
        });
        mic.start();
        running.set(true);
        partial("");
        status("listening (chat voice typing)");
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        status("stopping (finalizing)...");
        try { if (mic != null) mic.stop(); } catch (Exception e) { log.debug("mic stop: {}", e.getMessage()); }
        mic = null;
        StreamingSttSession s = sessionRef.getAndSet(null);
        if (s != null) {
            try { s.stop(); } catch (Exception e) { log.debug("session stop: {}", e.getMessage()); }
        } else if (finalHandled.compareAndSet(false, true)) {
            status("stopped");
        }
    }

    public boolean isRunning() { return running.get(); }

    private void handleStreamingPartial(String rawText, String langHint) {
        // Single-update flow: do not inject/translate partials while speaking. Capture runs until
        // hotkey stop; finalize performs one sanitized/composed update at end.
        if (finalHandled.get()) return;
        status("listening");
    }

    private void applyStreamingPartial(String prepared, String srcLang, long seq) {
        // Unused for in-field updates in single-update flow (kept for test/internal compatibility).
        if (seq != partialSeq.get() || finalHandled.get()) return;
        partial(prepared == null ? "" : prepared);
    }

    /** Translate text for chat field when translation-oriented; falls back to transcript on failure/unusable output. */
    private String translateForChat(String prepared, String srcLang, String tgtLang) {
        if (tgtLang.equalsIgnoreCase(srcLang)) return prepared;
        if ("auto".equalsIgnoreCase(srcLang)) {
            // cannot translate reliably from unknown source; keep transcript
            return prepared;
        }
        try {
            String translated = translator.translate(prepared, srcLang, tgtLang).join();
            if (translated == null || translated.isBlank() || TranslationPostProcessor.isRepetitive(translated, prepared)) {
                return prepared;
            }
            return translated;
        } catch (Exception e) {
            log.debug("partial/final translation fallback: {}", e.getMessage());
            return prepared;
        }
    }

    /**
     * Replace the previously injected segment in the focused external field with {@code newText}.
     * Uses delta/common-prefix editing. In single-update flow this is invoked once on finalize
     * (from empty to final). Best-effort: if auto-updates paused, does nothing destructive.
     * Returns true if the field was updated (or no change needed).
     */
    private boolean replaceInjectedText(String newText, boolean streaming) {
        String next = newText == null ? "" : newText;
        synchronized (fieldLock) {
            if (next.equals(lastInjectedText)) return true;
            if (autoUpdatePaused) return false;
            String prev = lastInjectedText;
            int prefix = commonPrefixLength(prev, next);
            int remove = prev.length() - prefix;
            String suffix = next.substring(prefix);
            List<InputAction> actions = new ArrayList<>(2);
            if (remove > 0) actions.add(InputAction.backspace(remove));
            if (!suffix.isEmpty()) actions.add(InputAction.typeText(suffix));
            if (actions.isEmpty()) {
                lastInjectedText = next;
                return true;
            }
            boolean ok = input.executeAll(actions, false);
            if (ok) {
                lastInjectedText = next;
                return true;
            } else {
                // cannot reliably know field state after failed replace; pause to avoid clobbering edits
                autoUpdatePaused = true;
                status(streaming ? "target not secured (auto-update paused)" : "not-injected (target not secured)");
                return false;
            }
        }
    }

    static int commonPrefixLength(String a, String b) {
        if (a == null || b == null) return 0;
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /** Finalize recognized text: commands or translated text left in external field for review (no auto-send). */
    private void finalizeText(String rawText, String langHint) {
        try {
            partialSeq.incrementAndGet(); // invalidate in-flight partial updates
            String text = TranscriptSanitizer.sanitize(rawText);
            if (text.isEmpty()) {
                // nothing spoken -> leave field as-is (no partials were injected)
                status("stopped (no speech)");
                partial("");
                return;
            }
            String srcLang = nvl(langHint, "auto");
            String prepared = synonyms.apply(text, srcLang);

            VoiceCommandInterpreter.Interpretation interp = commands.interpret(prepared);
            if (interp.command()) {
                boolean ok = input.executeAll(interp.actions(), false);
                status((ok ? "command: " : "command-failed: ") + interp.description());
                if (history != null) {
                    try { history.save(TranslationRecord.ofNew(prepared, srcLang, "[" + interp.description() + "]", srcLang, "dictation-cmd", null, "mic")); } catch (Exception e) { log.debug("history save: {}", e.getMessage()); }
                }
                partial("");
                return;
            }

            String tgtLang = nvl(settings.target_language, "en");
            String outText;
            String translationNote = null;
            boolean translatedApplied = false;

            if (tgtLang.equalsIgnoreCase(srcLang)) {
                outText = prepared;
            } else {
                try {
                    String translated = translator.translate(prepared, srcLang, tgtLang).join();
                    if (translated == null || translated.isBlank() || TranslationPostProcessor.isRepetitive(translated, prepared)) {
                        translationNote = "translation unavailable/invalid (inserted transcript)";
                        outText = prepared;
                        tgtLang = srcLang;
                    } else {
                        outText = translated;
                        translatedApplied = true;
                    }
                } catch (Exception e) {
                    translationNote = "translation failed (inserted transcript): " + e.getMessage();
                    outText = prepared;
                    tgtLang = srcLang;
                }
            }

            // Single final update at end (sanitized once): leave text in place for review/edit/send.
            boolean ok = replaceInjectedText(outText, false);
            String baseStatus = ok ? "ready: " + preview(outText) : "not-injected (target not secured): " + preview(outText);
            status(translationNote == null ? baseStatus : translationNote + " — " + baseStatus);
            if (history != null) {
                try {
                    history.save(TranslationRecord.ofNew(prepared, srcLang, translatedApplied ? outText : prepared, translatedApplied ? tgtLang : srcLang, "dictation", null, "mic"));
                } catch (Exception e) { log.debug("history save: {}", e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("finalize failed: {}", e.getMessage(), e);
            status("error: " + e.getMessage());
        } finally {
            partial("");
        }
    }

    /** Test hook: run finalize logic directly (no mic/session). */
    void finalizeForTest(String rawText, String langHint) {
        finalHandled.set(true);
        finalizeText(rawText, normalizeLang(langHint));
    }

    /** Test hook: simulate a streaming partial (single-update flow: no in-field injection). */
    void partialForTest(String rawText, String langHint) {
        String effective = normalizeLang(langHint);
        String text = TranscriptSanitizer.sanitize(rawText);
        if (text.isEmpty()) { partial(""); return; }
        handleStreamingPartial(text, effective);
    }

    private static String effectiveLang(String detectedLang, String fallback) {
        return (detectedLang != null && !detectedLang.isBlank()) ? detectedLang : fallback;
    }

    private static String normalizeLang(String s) {
        if (s == null) return null; String v = s.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("auto") || v.equalsIgnoreCase("detected")) return null;
        return v;
    }
    private static String nvl(String v, String d) { return (v == null || v.isBlank()) ? d : v; }
    private static float val(Double v, float d) { return v == null ? d : v.floatValue(); }
    private static float val(Float v, float d) { return v == null ? d : v; }
    private static String preview(String s) { return s.length() <= 60 ? s : s.substring(0, 57) + "..."; }
    private static String rootMsg(Throwable t) {
        Throwable c = t; while (c != null && c.getCause() != null) c = c.getCause();
        if (c == null) return "error";
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    @Override public void close() { stop(); exec.shutdownNow(); }
}
