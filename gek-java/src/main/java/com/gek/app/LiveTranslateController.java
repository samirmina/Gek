package com.gek.app;

import com.gek.audio.AppDucker;
import com.gek.audio.AudioSource;
import com.gek.audio.MicAudioSource;
import com.gek.audio.NoopAppDucker;
import com.gek.audio.NoopTtsEngine;
import com.gek.audio.SpeechSegmenter;
import com.gek.audio.TtsEngine;
import com.gek.audio.WavWriter;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import com.gek.text.SynonymReplacer;
import com.gek.text.TranslationPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Live translate pipeline with pluggable audio source.
 * Flow: source -> segment -> transcribe -> translate -> persist -> HUD/TTS callbacks.
 */
public class LiveTranslateController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LiveTranslateController.class);

    public enum SourceMode { MIC, LOOPBACK, CUSTOM }

    @FunctionalInterface
    public interface TranscriptListener {
        void onTranscript(String original, String translated, String meta, String sourceName);
    }

    private final AppPaths paths;
    private final AppSettings settings;
    private final SpeechToTextEngine stt;
    private final TranslationEngine translator;
    private final HistoryRepository history;
    private final SynonymReplacer synonyms;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"gek-live"); t.setDaemon(true); return t; });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioSource source;
    private SourceMode sourceMode = SourceMode.MIC;
    private String customSourceLabel = "custom";
    private SpeechSegmenter segmenter;
    private TtsEngine ttsEngine = new NoopTtsEngine();
    private AppDucker ducker = new NoopAppDucker();
    private Consumer<String> statusListener;
    private volatile TranscriptListener transcriptListener;

    public LiveTranslateController(AppPaths paths, AppSettings settings, SpeechToTextEngine stt, TranslationEngine translator, HistoryRepository history) {
        this.paths = paths; this.settings = settings; this.stt = stt; this.translator = translator; this.history = history;
        this.synonyms = SynonymReplacer.load(paths.synonymsDir());
    }

    public void setStatusListener(Consumer<String> l) { this.statusListener = l; }
    public void setTranscriptListener(TranscriptListener l) { this.transcriptListener = l; }
    public void setTtsEngine(TtsEngine ttsEngine) { this.ttsEngine = ttsEngine != null ? ttsEngine : new NoopTtsEngine(); }
    public void setDucker(AppDucker ducker) { this.ducker = ducker != null ? ducker : new NoopAppDucker(); }
    public TtsEngine getTtsEngine() { return ttsEngine; }
    public AppDucker getDucker() { return ducker; }

    public synchronized void startMic() throws Exception { this.sourceMode = SourceMode.MIC; startWith(null); }
    public synchronized void startLoopback(AudioSource loopbackSource) throws Exception {
        if (loopbackSource == null) throw new IllegalArgumentException("loopback source is required");
        this.sourceMode = SourceMode.LOOPBACK;
        startWith(loopbackSource);
    }
    public synchronized void startWith(AudioSource custom) throws Exception {
        if (running.get()) return;
        try { stt.warmup().exceptionally(e -> { log.warn("warmup: {}", e.getMessage()); return null; }); } catch (Exception e) { log.warn("warmup: {}", e.getMessage()); }
        int sr = 16000;
        segmenter = new SpeechSegmenter(sr, 0.01f, v(settings.silence_duration_ms,300), v(settings.min_chunk_duration,0.15f), v(settings.max_chunk_duration,4.0f), 0.2f);
        segmenter.setOnSegment(this::onSegment);
        if (custom != null) {
            this.source = custom;
            if (sourceMode == null || sourceMode == SourceMode.MIC) sourceMode = SourceMode.CUSTOM;
            this.customSourceLabel = custom.name() != null ? custom.name() : "custom";
        } else {
            this.sourceMode = SourceMode.MIC;
            this.source = new MicAudioSource(sr, v(settings.chunk_duration,0.25f), settings.audio_mic);
            this.customSourceLabel = "mic";
        }
        source.setConsumer(segmenter::accept);
        source.start();
        running.set(true);
        status("listening (" + label() + ")");
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try { if (source!=null) source.stop(); } catch (Exception ignored) {}
        try { if (segmenter!=null) segmenter.flush(); } catch (Exception ignored) {}
        source=null; segmenter=null; status("stopped");
    }
    public boolean isRunning(){ return running.get(); }
    public String currentSourceLabel(){ return label(); }
    private String label(){ return sourceMode==SourceMode.LOOPBACK?"loopback":(sourceMode==SourceMode.CUSTOM?customSourceLabel:"mic"); }

    private void onSegment(float[] samples){ if (running.get()) exec.submit(() -> process(samples, label())); }

    void processForTest(float[] samples, String sourceLabel){ process(samples, sourceLabel==null?"test":sourceLabel); }

    private void process(float[] samples, String srcLabel){
        Path tmp=null;
        boolean ducked = false;
        try {
            Path dir = paths.dataDir().resolve("tmp"); Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "live-", ".wav");
            WavWriter.writeFloatMono16k(tmp, samples);
            String langHint = norm(settings.source_language);
            TranscriptionResult tr = stt.transcribe(tmp, langHint).join();
            String text = tr.text()==null?"":tr.text().trim();
            if (text.isEmpty()) return;
            String src = tr.language()!=null?tr.language(): (langHint!=null?langHint:"auto");
            String prepared = synonyms.apply(text, src);
            String tgt = settings.target_language!=null?settings.target_language:"en";
            String translated = prepared;
            if (!tgt.equalsIgnoreCase(src)) {
                try {
                    String trOut = translator.translate(prepared, src, tgt).join();
                    if (trOut == null || trOut.isBlank() || TranslationPostProcessor.isRepetitive(trOut, prepared)) {
                        translated = prepared;
                    } else {
                        translated = trOut;
                    }
                } catch (Exception e) { log.warn("translate: {}", e.getMessage()); translated = prepared; }
            }
            if (history!=null) try { history.save(TranslationRecord.ofNew(prepared, src, translated, tgt, "live", null, srcLabel)); } catch (Exception ignored) {}
            TranscriptListener l = transcriptListener;
            if (l!=null) try { l.onTranscript(prepared, translated, src+"→"+tgt, srcLabel); } catch (Exception ignored) {}
            maybeSpeak(translated, tgt);
            status("["+src+"→"+tgt+" "+srcLabel+"] "+preview(translated));
        } catch (Exception e) { log.warn("live segment failed: {}", e.getMessage(), e); status("error: "+e.getMessage()); }
        finally {
            if (ducked) try { ducker.unduck(); } catch (Exception ignored) {}
            if (tmp!=null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private void maybeSpeak(String text, String lang) {
        if (!Boolean.TRUE.equals(settings.tts_enabled)) return;
        if (text == null || text.isBlank()) return;
        boolean ducked = false;
        try {
            if (Boolean.TRUE.equals(settings.auto_mute_during_tts) && ducker != null && ducker.isSupported()) {
                ducker.duck(); ducked = true;
            }
            ttsEngine.speak(text, lang);
        } catch (Exception e) {
            log.debug("tts failed: {}", e.getMessage());
        } finally {
            if (ducked) try { ducker.unduck(); } catch (Exception ignored) {}
        }
    }

    private void status(String s){ log.info("Live: {}", s); if (statusListener!=null) try{statusListener.accept(s);}catch(Exception ignored){} }
    private static String preview(String s){ return s.length() <= 60 ? s : s.substring(0,57) + "..."; }
    private static String norm(String s){ if(s==null) return null; String v=s.trim(); if(v.isEmpty()||v.equalsIgnoreCase("auto")||v.equalsIgnoreCase("detected")) return null; return v; }
    private static float v(Double x,float d){return x==null?d:x.floatValue();}
    private static int v(Integer x,int d){return x==null?d:x;}
    @Override public void close(){ stop(); exec.shutdownNow(); }
}
