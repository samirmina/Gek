package com.gek.app;

import com.gek.audio.MicCapture;
import com.gek.audio.SpeechSegmenter;
import com.gek.audio.WavWriter;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import com.gek.inject.TextInjector;
import com.gek.text.SynonymReplacer;
import com.gek.text.TranslationPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.LineUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DictationController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DictationController.class);
    private final AppPaths paths;
    private final AppSettings settings;
    private final SpeechToTextEngine stt;
    private final TranslationEngine translator;
    private final TextInjector injector;
    private final HistoryRepository history; // optional
    private final SynonymReplacer synonyms;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gek-dictation"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private MicCapture mic;
    private SpeechSegmenter segmenter;
    private Consumer<String> statusListener;

    public DictationController(AppPaths paths, AppSettings settings, SpeechToTextEngine stt,
                               TranslationEngine translator, TextInjector injector, HistoryRepository history) {
        this.paths = paths; this.settings = settings; this.stt = stt; this.translator = translator;
        this.injector = injector; this.history = history;
        this.synonyms = SynonymReplacer.load(paths.synonymsDir());
    }

    public void setStatusListener(Consumer<String> statusListener) { this.statusListener = statusListener; }
    private void status(String s) { log.info("Dictation: {}", s); if (statusListener != null) try { statusListener.accept(s); } catch (Exception ignored) {} }

    public synchronized void start() throws Exception {
        if (running.get()) return;
        status("starting inference engine...");
        try { stt.warmup().exceptionally(e -> { log.warn("warmup issue: {}", e.getMessage()); return null; }); } catch (Exception e) { log.warn("warmup: {}", e.getMessage()); }
        int sr = 16000;
        float chunk = val(settings.chunk_duration, 0.25f);
        float silenceThr = 0.01f;
        int silenceMs = val(settings.silence_duration_ms, 300);
        float minDur = val(settings.min_chunk_duration, 0.15f);
        float maxDur = val(settings.max_chunk_duration, 4.0f);
        float carry = 0.2f;
        segmenter = new SpeechSegmenter(sr, silenceThr, silenceMs, minDur, maxDur, carry);
        segmenter.setOnSegment(this::onSegment);
        mic = new MicCapture(sr, chunk, settings.audio_mic);
        mic.setConsumer(segmenter::accept);
        mic.start();
        running.set(true);
        status("listening (dictation)");
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        status("stopping...");
        try { if (mic != null) mic.stop(); } catch (Exception e) { log.debug("mic stop: {}", e.getMessage()); }
        try { if (segmenter != null) segmenter.flush(); } catch (Exception e) { log.debug("flush: {}", e.getMessage()); }
        mic = null; segmenter = null;
        status("stopped");
    }

    public boolean isRunning() { return running.get(); }

    private void onSegment(float[] samples) {
        if (!running.get()) return;
        exec.submit(() -> processSegment(samples));
    }

    private void processSegment(float[] samples) {
        Path tmp = null;
        try {
            Path dir = paths.dataDir().resolve("tmp");
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "dict-", ".wav");
            WavWriter.writeFloatMono16k(tmp, samples);
            String langHint = normalizeLang(settings.source_language);
            TranscriptionResult tr = stt.transcribe(tmp, langHint).join();
            String text = tr.text() == null ? "" : tr.text().trim();
            if (text.isEmpty()) return;
            String srcLang = tr.language() != null ? tr.language() : nvl(langHint, "auto");
            String prepared = synonyms.apply(text, srcLang);
            String outText = prepared;
            String tgtLang = nvl(settings.target_language, "en");
            boolean insertTranslated = "translated".equalsIgnoreCase(nvl(settings.dictation_insert_mode, "transcript"));
            if (insertTranslated && !tgtLang.equalsIgnoreCase(srcLang)) {
                try {
                    String translated = translator.translate(prepared, srcLang, tgtLang).join();
                    if (translated == null || translated.isBlank() || TranslationPostProcessor.isRepetitive(translated, prepared)) {
                        log.debug("translation empty/repetitive, keeping source text");
                        outText = prepared;
                    } else {
                        outText = translated;
                    }
                } catch (Exception e) { log.warn("translate failed, injecting transcript: {}", e.getMessage()); outText = prepared; }
            } else {
                tgtLang = srcLang;
                outText = prepared;
            }
            String inject = outText;
            if (!inject.endsWith(" ") && !inject.endsWith("\n")) inject = inject + " ";
            boolean ok = injector.inject(inject);
            status((ok ? "injected: " : "result: ") + preview(outText));
            if (history != null) {
                try { history.save(TranslationRecord.ofNew(prepared, srcLang, insertTranslated ? outText : prepared, insertTranslated ? tgtLang : srcLang, "dictation", null, "mic")); } catch (Exception e) { log.debug("history save: {}", e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("segment processing failed: {}", e.getMessage(), e);
            status("error: " + e.getMessage());
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private static String normalizeLang(String s) {
        if (s == null) return null; String v = s.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("auto") || v.equalsIgnoreCase("detected")) return null;
        return v;
    }
    private static String nvl(String v, String d) { return (v == null || v.isBlank()) ? d : v; }
    private static float val(Double v, float d) { return v == null ? d : v.floatValue(); }
    private static float val(Float v, float d) { return v == null ? d : v; }
    private static int val(Integer v, int d) { return v == null ? d : v; }
    private static String preview(String s) { return s.length() <= 60 ? s : s.substring(0, 57) + "..."; }

    @Override public void close() { stop(); exec.shutdownNow(); }
}
