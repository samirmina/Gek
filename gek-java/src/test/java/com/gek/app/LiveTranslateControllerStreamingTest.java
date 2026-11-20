package com.gek.app;

import com.gek.audio.AudioChunk;
import com.gek.audio.AudioSource;
import com.gek.audio.RecordingTtsEngine;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.StreamingSpeechToTextEngine;
import com.gek.inference.StreamingSttSession;
import com.gek.inference.StreamingTranscriptionListener;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LiveTranslateControllerStreamingTest {
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

    static class NoopSource implements AudioSource {
        private final AtomicBoolean running = new AtomicBoolean(false);
        @Override public void setConsumer(Consumer<AudioChunk> consumer) {}
        @Override public void start() { running.set(true); }
        @Override public void stop() { running.set(false); }
        @Override public boolean isRunning() { return running.get(); }
        @Override public String name() { return "fake-mic"; }
    }

    static class FakeSession implements StreamingSttSession {
        private final AtomicBoolean open = new AtomicBoolean(true);
        @Override public void stop() { open.set(false); }
        @Override public boolean isRunning() { return open.get(); }
        @Override public String finalTranscript() { return ""; }
        @Override public void close() { open.set(false); }
    }

    static class FakeStreamingStt implements StreamingSpeechToTextEngine {
        final AtomicReference<StreamingTranscriptionListener> listener = new AtomicReference<>();
        @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
            return CompletableFuture.completedFuture(new TranscriptionResult("", "en", 1.0, 0.0));
        }
        @Override public boolean streamingAvailable() { return true; }
        @Override public StreamingSttSession startSession(String languageHint, StreamingTranscriptionListener l) {
            listener.set(l);
            return new FakeSession();
        }
        @Override public void close() {}
    }

    static class CountingTranslator implements TranslationEngine {
        final AtomicInteger calls = new AtomicInteger();
        volatile String out = "translated";
        @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(out);
        }
        @Override public void close() {}
    }

    @Test
    void streamingPartialEmitsHudOnlyWithoutPersistTranslateOrTts() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.target_language = "en";
        s.tts_enabled = true;
        FakeStreamingStt stt = new FakeStreamingStt();
        CountingTranslator tr = new CountingTranslator();
        RecordingTtsEngine tts = new RecordingTtsEngine();
        List<String[]> heard = new CopyOnWriteArrayList<>();
        List<String> statuses = new CopyOnWriteArrayList<>();

        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = new LiveTranslateController(AppPaths.resolve(), s, stt, tr, h)) {
            c.setTtsEngine(tts);
            c.setTranscriptListener((o, t, m, src) -> heard.add(new String[]{o, t, m, src}));
            c.setStatusListener(statuses::add);
            c.startWith(new NoopSource());

            StreamingTranscriptionListener l = stt.listener.get();
            assertNotNull(l, "streaming session should capture listener");
            assertTrue(c.isRunning());

            l.onPartial("hello wor");
            // partial callback is synchronous on caller thread in controller
            assertEquals(1, heard.size());
            assertEquals("hello wor", heard.get(0)[0]);
            assertEquals("hello wor", heard.get(0)[1], "partial should mirror text for HUD");
            assertTrue(heard.get(0)[2].startsWith("…"), "partial meta should indicate in-progress");
            assertEquals("fake-mic", heard.get(0)[3]);
            assertEquals(0, tr.calls.get(), "partial must not translate");
            assertEquals(0, h.count(), "partial must not persist history");
            assertTrue(tts.utterances().isEmpty(), "partial must not speak");
            // overlay-only controller has no injection path (structural) and no chat coupling
        }
    }

    @Test
    void streamingFinalRoutesThroughOverlayPipelineAndPersists() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "en";
        s.target_language = "es";
        s.tts_enabled = false;
        FakeStreamingStt stt = new FakeStreamingStt();
        CountingTranslator tr = new CountingTranslator();
        tr.out = "hola";
        List<String[]> heard = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(1);

        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = new LiveTranslateController(AppPaths.resolve(), s, stt, tr, h)) {
            c.setTranscriptListener((o, t, m, src) -> { heard.add(new String[]{o, t, m, src}); got.countDown(); });
            c.startWith(new NoopSource());

            StreamingTranscriptionListener l = stt.listener.get();
            assertNotNull(l);
            l.onFinal("hello");

            assertTrue(got.await(3, TimeUnit.SECONDS), "final should reach transcript listener");
            assertEquals(1, heard.size());
            assertEquals("hello", heard.get(0)[0]);
            assertEquals("hola", heard.get(0)[1]);
            assertEquals("en→es", heard.get(0)[2]);
            assertEquals("fake-mic", heard.get(0)[3]);
            assertEquals(1, tr.calls.get(), "final should translate when src!=tgt");
            assertEquals(1, h.count(), "final should persist history");
            assertEquals("hola", h.recent(1).get(0).translatedText());
        }
    }

    @Test
    void callbacksAreIgnoredWhenControllerNotRunning() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.target_language = "es";
        FakeStreamingStt stt = new FakeStreamingStt();
        CountingTranslator tr = new CountingTranslator();
        List<String[]> heard = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = new LiveTranslateController(AppPaths.resolve(), s, stt, tr, h)) {
            c.setTranscriptListener((o, t, m, src) -> heard.add(new String[]{o, t, m, src}));
            c.startWith(new NoopSource());
            StreamingTranscriptionListener l = stt.listener.get();
            assertNotNull(l);
            c.stop();
            assertFalse(c.isRunning());

            l.onPartial("late partial");
            l.onFinal("late final");
            // give async path a moment if it were (incorrectly) scheduled
            Thread.sleep(120);
            assertTrue(heard.isEmpty(), "stopped controller must not emit HUD updates");
            assertEquals(0, tr.calls.get(), "stopped controller must not translate");
            assertEquals(0, h.count(), "stopped controller must not persist history");
        }
    }

    @Test
    void blankStreamingInputsProduceNoSideEffects() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "en";
        s.target_language = "es";
        FakeStreamingStt stt = new FakeStreamingStt();
        CountingTranslator tr = new CountingTranslator();
        List<String[]> heard = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = new LiveTranslateController(AppPaths.resolve(), s, stt, tr, h)) {
            c.setTranscriptListener((o, t, m, src) -> heard.add(new String[]{o, t, m, src}));
            c.startWith(new NoopSource());
            StreamingTranscriptionListener l = stt.listener.get();
            assertNotNull(l);

            l.onPartial("   ");
            l.onPartial("");
            l.onFinal("   ");
            Thread.sleep(120);
            assertTrue(heard.isEmpty(), "blank streaming text must be ignored");
            assertEquals(0, tr.calls.get());
            assertEquals(0, h.count());
        }
    }

    @Test
    void batchPathRemainsOverlayOnlyAndPersistsFinals() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.target_language = "en";
        s.tts_enabled = false;
        // non-streaming STT -> batch/final-only processing via processForTest
        SpeechToTextEngine stt = new SpeechToTextEngine() {
            @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
                return CompletableFuture.completedFuture(new TranscriptionResult("hello", "en", 1.0, 0.1));
            }
            @Override public void close() {}
        };
        CountingTranslator tr = new CountingTranslator();
        List<String[]> heard = new CopyOnWriteArrayList<>();
        try (HistoryRepository h = new HistoryRepository(AppPaths.resolve().dbPath());
             LiveTranslateController c = new LiveTranslateController(AppPaths.resolve(), s, stt, tr, h)) {
            c.setTranscriptListener((o, t, m, src) -> heard.add(new String[]{o, t, m, src}));
            c.processForTest(new float[]{0.1f, 0.2f}, "mic");
            assertEquals(1, heard.size());
            assertEquals("hello", heard.get(0)[0]);
            assertEquals("hello", heard.get(0)[1]);
            assertEquals("en→en", heard.get(0)[2]);
            assertEquals(0, tr.calls.get(), "same language should not translate");
            assertEquals(1, h.count(), "batch/final path should persist history");
            // overlay-only: controller has no injector/caret APIs; behavior limited to HUD/history/tts callbacks
        }
    }
}
