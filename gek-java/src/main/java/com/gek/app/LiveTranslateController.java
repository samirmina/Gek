package com.gek.app;

import com.gek.audio.AppDucker;
import com.gek.audio.AudioSource;
import com.gek.audio.AutoAudioSelector;
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
import com.gek.inference.StreamingSpeechToTextEngine;
import com.gek.inference.StreamingSttSession;
import com.gek.inference.StreamingTranscriptionListener;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import com.gek.inference.WindowedStreamingSession;
import com.gek.text.SynonymReplacer;
import com.gek.text.TranslationPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Live captions pipeline (overlay-only, decoupled from chat voice typing): pluggable audio source →
 * STT (streaming partials when available, else batch segmenter) → translate → dedupe → persist → HUD/TTS callbacks.
 * Never performs caret injection.
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
    private SpeechSegmenter segmenter; // batch fallback
    private volatile StreamingSttSession streamingSession; // low-latency captions path
    private TtsEngine ttsEngine = new NoopTtsEngine();
    private AppDucker ducker = new NoopAppDucker();
    private Consumer<String> statusListener;
    private volatile TranscriptListener transcriptListener;
    private volatile String lastEmitted = "";

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
        lastEmitted = "";
        if (custom != null) {
            this.source = custom;
            if (sourceMode == null || sourceMode == SourceMode.MIC) sourceMode = SourceMode.CUSTOM;
            this.customSourceLabel = custom.name() != null ? custom.name() : "custom";
        } else {
            this.sourceMode = SourceMode.MIC;
            this.source = new MicAudioSource(sr, v(settings.chunk_duration,0.25f), AutoAudioSelector.resolveMicHint(settings));
            this.customSourceLabel = "mic";
        }

        boolean useStreaming = stt instanceof StreamingSpeechToTextEngine sse && sse.streamingAvailable();
        if (useStreaming) {
            String langHint = norm(settings.source_language);
            streamingSession = ((StreamingSpeechToTextEngine) stt).startSession(langHint, new StreamingTranscriptionListener() {
                @Override public void onPartial(String text) { onStreamingPartial(text); }
                @Override public void onFinal(String text) {
                    onStreamingFinal(text, langHint);
                }
                @Override public void onFinal(String text, String detectedLang) {
                    String effective = (detectedLang != null && !detectedLang.isBlank()) ? detectedLang : langHint;
                    onStreamingFinal(text, effective);
                }
                @Override public void onError(Throwable t) { log.warn("live streaming STT error: {}", t.getMessage()); status("error: " + t.getMessage()); }
                @Override public void onClosed() { /* no-op */ }
            });
            source.setConsumer(chunk -> {
                StreamingSttSession s = streamingSession;
                if (s instanceof WindowedStreamingSession wss) {
                    try { wss.accept(chunk); } catch (Exception ignored) {}
                }
            });
            status("listening (" + label() + ", streaming)");
        } else {
            segmenter = new SpeechSegmenter(sr, 0.01f, v(settings.silence_duration_ms,250), v(settings.min_chunk_duration,0.12f), v(settings.max_chunk_duration,3.0f), 0.2f);
            segmenter.setOnSegment(this::onSegment);
            source.setConsumer(segmenter::accept);
            status("listening (" + label() + ")");
        }
        source.start();
        running.set(true);
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try { if (source!=null) source.stop(); } catch (Exception ignored) {}
        StreamingSttSession ss = streamingSession;
        streamingSession = null;
        try { if (ss!=null) ss.stop(); } catch (Exception ignored) {}
        try { if (ss!=null) ss.close(); } catch (Exception ignored) {}
        try { if (segmenter!=null) segmenter.flush(); } catch (Exception ignored) {}
        source=null; segmenter=null; status("stopped");
    }
    public boolean isRunning(){ return running.get(); }
    public String currentSourceLabel(){ return label(); }
    private String label(){ return sourceMode==SourceMode.LOOPBACK?"loopback":(sourceMode==SourceMode.CUSTOM?customSourceLabel:"mic"); }

    private void onSegment(float[] samples){ if (running.get()) exec.submit(() -> process(samples, label(), false)); }

    private void onStreamingPartial(String text) {
        if (!running.get()) return;
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        TranscriptListener l = transcriptListener;
        if (l != null) {
            try { l.onTranscript(t, t, "… " + label(), label()); } catch (Exception ignored) {}
        }
    }

    private void onStreamingFinal(String text, String langHint) {
        if (!running.get()) return;
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        exec.submit(() -> handleTranscript(t, langHint, label(), false));
    }

    void processForTest(float[] samples, String sourceLabel){ process(samples, sourceLabel==null?"test":sourceLabel, true); }

    private void process(float[] samples, String srcLabel, boolean direct){
        if (!direct && !running.get()) return;
        Path tmp=null;
        try {
            Path dir = paths.dataDir().resolve("tmp"); Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "live-", ".wav");
            WavWriter.writeFloatMono16k(tmp, samples);
            String langHint = norm(settings.source_language);
            TranscriptionResult tr = stt.transcribe(tmp, langHint).join();
            String text = tr.text()==null?"":tr.text().trim();
            if (text.isEmpty()) return;
            String src = tr.language()!=null?tr.language(): (langHint!=null?langHint:"auto");
            handleTranscript(text, src, srcLabel, direct);
        } catch (Exception e) { log.warn("live segment failed: {}", e.getMessage(), e); status("error: "+e.getMessage()); }
        finally {
            if (tmp!=null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    /** Apply translate/dedupe/persist/TTS/HUD to an already-transcribed caption text (overlay-only). */
    private void handleTranscript(String text, String detectedOrHintLang, String srcLabel, boolean direct) {
        if (!direct && !running.get()) return;
        try {
            String src = detectedOrHintLang != null ? detectedOrHintLang : "auto";
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
            if (!direct && !running.get()) return;
            String deduped = dedupeAgainstLast(translated);
            if (deduped.isBlank()) return;
            lastEmitted = deduped;
            if (history!=null) try { history.save(TranslationRecord.ofNew(prepared, src, deduped, tgt, "live", null, srcLabel)); } catch (Exception ignored) {}
            TranscriptListener l = transcriptListener;
            if (l!=null) try { l.onTranscript(prepared, deduped, src+"→"+tgt, srcLabel); } catch (Exception ignored) {}
            maybeSpeak(deduped, tgt);
            status("["+src+"→"+tgt+" "+srcLabel+"] "+preview(deduped));
        } catch (Exception e) { log.warn("live transcript failed: {}", e.getMessage(), e); status("error: "+e.getMessage()); }
    }

    private synchronized String dedupeAgainstLast(String current) {
        if (current == null) return "";
        String cur = current.trim();
        if (cur.isEmpty()) return "";
        String prev = lastEmitted == null ? "" : lastEmitted.trim();
        if (prev.isEmpty()) return cur;
        if (cur.equalsIgnoreCase(prev)) return "";
        String curLow = cur.toLowerCase();
        String prevLow = prev.toLowerCase();
        if (curLow.startsWith(prevLow) && cur.length() > prev.length()) {
            return cur.substring(prev.length()).trim();
        }
        int max = Math.min(prev.length(), cur.length());
        for (int k = Math.min(max, 80); k >= 8; k--) {
            String suffix = prevLow.substring(prevLow.length() - k);
            if (curLow.startsWith(suffix)) {
                String rest = cur.substring(k).trim();
                if (!rest.isEmpty()) return rest;
            }
        }
        return cur;
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

    private void status(String s){ if (statusListener!=null) try{statusListener.accept(s);}catch(Exception ignored){} }
    private static String preview(String s){ return s.length() <= 60 ? s : s.substring(0,57) + "..."; }
    private static String norm(String s){ if(s==null) return null; String v=s.trim(); if(v.isEmpty()||v.equalsIgnoreCase("auto")||v.equalsIgnoreCase("detected")) return null; return v; }
    private static float v(Double x,float d){return x==null?d:x.floatValue();}
    private static int v(Integer x,int d){return x==null?d:x;}
    @Override public void close(){ stop(); exec.shutdownNow(); }
}
