package com.gek.app;

import com.gek.audio.AutoAudioSelector;
import com.gek.audio.MicCapture;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.StreamingSttSession;
import com.gek.inference.StreamingTranscriptionListener;
import com.gek.inference.TranslationEngine;
import com.gek.inference.WindowedStreamingSession;
import com.gek.inject.InputExecutor;
import com.gek.inject.RobotInputExecutor;
import com.gek.inject.VoiceCommandInterpreter;
import com.gek.text.SynonymReplacer;
import com.gek.text.TranscriptSanitizer;
import com.gek.text.TranslationPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streaming chat voice-typing system (decoupled from live captions/HUD).
 * Captures mic continuously, emits live partial transcripts for chat-style UI, and on stop/finalize
 * applies voice commands/synonyms/translation then injects at the caret (secured target). No
 * SpeechSegmenter batching and no HUD/overlay transcript coupling.
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
    /** Receives live evolving partial transcript (chat-style). */
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
        status("starting streaming voice typing...");
        try { stt.warmup().exceptionally(e -> { log.warn("warmup issue: {}", e.getMessage()); return null; }); } catch (Exception e) { log.warn("warmup: {}", e.getMessage()); }

        String langHint = normalizeLang(settings.source_language);
        Path tmpDir = paths.dataDir().resolve("tmp");
        StreamingTranscriptionListener listener = new StreamingTranscriptionListener() {
            @Override public void onPartial(String text) {
                if (!running.get() && !finalHandled.get()) return; // allow last partial before stop drains
                partial(text);
                status("listening (streaming)");
            }
            @Override public void onFinal(String text) {
                // finalize once, on executor (may involve translation/injection)
                if (finalHandled.compareAndSet(false, true)) {
                    exec.submit(() -> finalizeText(text, langHint));
                }
            }
            @Override public void onError(Throwable t) {
                log.warn("streaming error: {}", t == null ? "unknown" : t.getMessage());
                status("error: " + (t == null ? "unknown" : rootMsg(t)));
            }
        };
        // Accumulating window for chat/dictation so long utterances don't lose older words (rolling 20s is for live captions).
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
            } else if (running.get() && s != null) {
                // generic session: best-effort feed if it is windowed-compatible in future; ignore otherwise
                // (WindowedStreamingSession is the primary contract today)
                if (s instanceof WindowedStreamingSession w) w.accept(chunkData);
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
            // nothing to finalize
            status("stopped");
        }
    }

    public boolean isRunning() { return running.get(); }

    /** Finalize recognized text: commands or dictation insert at caret (chat/typing logic). */
    private void finalizeText(String rawText, String langHint) {
        try {
            // Strip non-speech artifacts (e.g. [BLANK_AUDIO], (blank audio)) so they are never injected/copied.
            String text = TranscriptSanitizer.sanitize(rawText);
            if (text.isEmpty()) { status("stopped (no speech)"); partial(""); return; }
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

            String outText = prepared;
            String tgtLang = nvl(settings.target_language, "en");
            boolean insertTranslated = "translated".equalsIgnoreCase(nvl(settings.dictation_insert_mode, "transcript"));
            if (insertTranslated && !tgtLang.equalsIgnoreCase(srcLang)) {
                try {
                    String translated = translator.translate(prepared, srcLang, tgtLang).join();
                    if (translated == null || translated.isBlank() || TranslationPostProcessor.isRepetitive(translated, prepared)) {
                        status("translation unavailable/invalid (no text inserted)");
                        partial("");
                        return;
                    }
                    outText = translated;
                } catch (Exception e) {
                    status("translation failed (no text inserted): " + e.getMessage());
                    partial("");
                    return;
                }
            } else {
                tgtLang = srcLang;
                outText = prepared;
            }

            String toType = outText.endsWith(" ") || outText.endsWith("\n") ? outText : outText + " ";
            boolean ok = input.executeAll(java.util.List.of(com.gek.inject.InputAction.typeText(toType)), false);
            status((ok ? "injected: " : "not-injected (target not secured): ") + preview(outText));
            if (history != null) {
                try { history.save(TranslationRecord.ofNew(prepared, srcLang, insertTranslated ? outText : prepared, insertTranslated ? tgtLang : srcLang, "dictation", null, "mic")); } catch (Exception e) { log.debug("history save: {}", e.getMessage()); }
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
