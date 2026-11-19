package com.gek.app;

import com.gek.audio.AppDucker;
import com.gek.audio.RecordingAppDucker;
import com.gek.audio.RecordingTtsEngine;
import com.gek.audio.TtsEngine;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LiveTranslateControllerFlowTest {
    @TempDir Path tmp;
    private String prev;

    @BeforeEach void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("resources/data"));
        Files.createDirectories(tmp.resolve("resources/models"));
        Files.createDirectories(tmp.resolve("resources/data/synonyms"));
        prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", tmp.toString());
    }
    @AfterEach void tearDown() {
        if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
    }

    static class Stt implements SpeechToTextEngine {
        volatile TranscriptionResult result = new TranscriptionResult("hello", "en", 1.0, 0.1);
        volatile RuntimeException fail;
        @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
            if (fail != null) return CompletableFuture.failedFuture(fail);
            return CompletableFuture.completedFuture(result);
        }
        @Override public void close() {}
    }
    static class Tr implements TranslationEngine {
        volatile String out = "translated";
        volatile RuntimeException fail;
        final AtomicInteger calls = new AtomicInteger();
        @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
            calls.incrementAndGet();
            if (fail != null) return CompletableFuture.failedFuture(fail);
            return CompletableFuture.completedFuture(out);
        }
        @Override public void close() {}
    }

    private LiveTranslateController ctl(AppSettings s, Stt stt, Tr tr, HistoryRepository h) {
        return new LiveTranslateController(AppPaths.resolve(), s, stt, tr, h);
    }

    @Test
    void emptyTranscriptProducesNoOutput() throws Exception {
        AppSettings s = AppSettings.defaults(); s.target_language = "es";
        Stt stt = new Stt(); stt.result = new TranscriptionResult("   ", "en", 1, 0.1);
        Tr tr = new Tr();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, tr, h)) {
            c.setStatusListener(statuses::add);
            c.setTranscriptListener((o,t,m,src) -> fail("should not emit"));
            c.processForTest(new float[]{0.1f}, "mic");
            assertEquals(0, tr.calls.get());
            assertEquals(0, h.count());
        }
    }

    @Test
    void sameLanguageSkipsTranslationAndCanSpeakWithoutDuckingWhenUnsupported() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.target_language = "en";
        s.tts_enabled = true;
        s.auto_mute_during_tts = true;
        Stt stt = new Stt(); stt.result = new TranscriptionResult("hello", "en", 1, 0.1);
        Tr tr = new Tr();
        RecordingTtsEngine tts = new RecordingTtsEngine();
        AppDucker noDuck = new AppDucker() {
            @Override public void duck() { fail("should not duck unsupported"); }
            @Override public void unduck() { fail("should not unduck unsupported"); }
            @Override public boolean isSupported() { return false; }
        };
        List<String[]> heard = new ArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, tr, h)) {
            c.setTtsEngine(tts);
            c.setDucker(noDuck);
            c.setTranscriptListener((o,t,m,src) -> heard.add(new String[]{o,t,m,src}));
            c.processForTest(new float[]{0.2f}, "mic");
            assertEquals(0, tr.calls.get(), "no translation when src==tgt");
            assertEquals(1, heard.size());
            assertEquals("hello", heard.get(0)[0]);
            assertEquals("hello", heard.get(0)[1]);
            assertEquals("en→en", heard.get(0)[2]);
            assertEquals(1, tts.utterances().size());
            assertEquals("en", tts.utterances().get(0).lang());
            assertEquals(1, h.count());
        }
    }

    @Test
    void translationFailureFallsBackToPreparedText() throws Exception {
        AppSettings s = AppSettings.defaults(); s.target_language = "en";
        Stt stt = new Stt(); stt.result = new TranscriptionResult("hola", "es", 1, 0.1);
        Tr tr = new Tr(); tr.fail = new RuntimeException("boom");
        List<String> out = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, tr, h)) {
            c.setTranscriptListener((o,t,m,src) -> out.add(t));
            c.processForTest(new float[]{0.2f}, "mic");
            assertEquals(List.of("hola"), out);
            assertEquals("hola", h.recent(1).get(0).translatedText());
        }
    }

    @Test
    void blankOrRepetitiveTranslationFallsBackAndDedupesIdentical() throws Exception {
        AppSettings s = AppSettings.defaults(); s.target_language = "en"; s.tts_enabled = false;
        Stt stt = new Stt(); stt.result = new TranscriptionResult("hola mundo", "es", 1, 0.1);
        Tr tr = new Tr(); tr.out = "   ";
        List<String> out = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, tr, h)) {
            c.setTranscriptListener((o,t,m,src) -> out.add(t));
            c.processForTest(new float[]{0.2f}, "mic");
            assertEquals(1, out.size());
            assertEquals("hola mundo", out.get(0));
            // identical -> deduped away
            c.processForTest(new float[]{0.3f}, "mic");
            assertEquals(1, out.size(), "duplicate emission should be suppressed");
            assertEquals(1, h.count());
        }
    }

    @Test
    void dedupeKeepsOnlyNewSuffix() throws Exception {
        AppSettings s = AppSettings.defaults(); s.target_language = "en"; s.tts_enabled = false;
        Stt stt = new Stt();
        Tr tr = new Tr();
        List<String> out = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, tr, h)) {
            c.setTranscriptListener((o,t,m,src) -> out.add(t));
            stt.result = new TranscriptionResult("hello", "en", 1, 0.1);
            c.processForTest(new float[]{0.1f}, "mic");
            stt.result = new TranscriptionResult("hello world", "en", 1, 0.1);
            c.processForTest(new float[]{0.2f}, "mic");
            assertEquals(List.of("hello", "world"), out);
        }
    }

    @Test
    void statusListenerReceivesUpdatesAndTtsDucksWhenSupported() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.target_language = "en"; s.tts_enabled = true; s.auto_mute_during_tts = true;
        Stt stt = new Stt(); stt.result = new TranscriptionResult("hi", "en", 1, 0.1);
        Tr tr = new Tr();
        RecordingTtsEngine tts = new RecordingTtsEngine();
        RecordingAppDucker ducker = new RecordingAppDucker();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, tr, h)) {
            c.setTtsEngine(tts); c.setDucker(ducker);
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f}, "custom-src");
            assertTrue(statuses.stream().anyMatch(x -> x.contains("en→en") && x.contains("custom-src")));
            assertEquals(1, ducker.duckCount());
            assertEquals(1, ducker.unduckCount());
            // direct process path does not mutate source/sourceMode; label remains default
            assertEquals("mic", c.currentSourceLabel());
            assertNotNull(c.getTtsEngine());
            assertNotNull(c.getDucker());
        }
    }

    @Test
    void ttsDisabledSkipsSpeaking() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.target_language = "en"; s.tts_enabled = false; s.auto_mute_during_tts = true;
        Stt stt = new Stt(); stt.result = new TranscriptionResult("hi", "en", 1, 0.1);
        RecordingTtsEngine tts = new RecordingTtsEngine();
        RecordingAppDucker ducker = new RecordingAppDucker();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, new Tr(), h)) {
            c.setTtsEngine(tts); c.setDucker(ducker);
            c.processForTest(new float[]{0.1f}, "mic");
            assertTrue(tts.utterances().isEmpty());
            assertEquals(0, ducker.duckCount());
        }
    }

    @Test
    void sttFailureReportsErrorStatus() throws Exception {
        AppSettings s = AppSettings.defaults();
        Stt stt = new Stt(); stt.fail = new RuntimeException("stt-bad");
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = ctl(s, stt, new Tr(), h)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f}, "mic");
            assertTrue(statuses.stream().anyMatch(x -> x.startsWith("error:")));
            assertEquals(0, h.count());
        }
    }
}
