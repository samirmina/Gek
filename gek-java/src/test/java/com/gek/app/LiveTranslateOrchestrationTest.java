package com.gek.app;

import com.gek.audio.RecordingAppDucker;
import com.gek.audio.RecordingTtsEngine;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LiveTranslateOrchestrationTest {

    static class FakeEngine implements SpeechToTextEngine, TranslationEngine {
        @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<TranscriptionResult> transcribe(Path audioWav, String languageHint) {
            return CompletableFuture.completedFuture(new TranscriptionResult("hola mundo", "es", 0.9, 0.1));
        }
        @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
            return CompletableFuture.completedFuture("hello world");
        }
        @Override public void close() {}
    }

    static class NoopHistory extends HistoryRepository {
        NoopHistory(Path dbPath) throws Exception { super(dbPath); }
    }

    @Test
    void processSpeaksAndReportsWithPluggableSource() throws Exception {
        Path tmp = Files.createTempDirectory("gek-live-test");
        // create minimal structure so AppPaths.resolve(project root = tmp) works if needed
        Files.createDirectories(tmp.resolve("resources/data"));
        Files.createDirectories(tmp.resolve("resources/models"));
        String prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", tmp.toString());
        try {
            AppPaths paths = AppPaths.resolve();
            AppSettings settings = AppSettings.defaults();
            settings.target_language = "en";
            settings.tts_enabled = true;
            settings.auto_mute_during_tts = true;
            FakeEngine engine = new FakeEngine();
            try (NoopHistory history = new NoopHistory(paths.dbPath());
                 engine;
                 LiveTranslateController ctl = new LiveTranslateController(paths, settings, engine, engine, history)) {
                RecordingTtsEngine tts = new RecordingTtsEngine();
                RecordingAppDucker ducker = new RecordingAppDucker();
                ctl.setTtsEngine(tts);
                ctl.setDucker(ducker);
                AtomicReference<String> meta = new AtomicReference<>();
                AtomicReference<String> source = new AtomicReference<>();
                ctl.setTranscriptListener((o, t, m, s) -> { meta.set(m); source.set(s); });
                ctl.processForTest(new float[]{0.01f, -0.01f, 0.02f}, "loopback-test");
                assertEquals(1, tts.utterances().size());
                assertEquals("hello world", tts.utterances().get(0).text());
                assertEquals("es→en", meta.get());
                assertEquals("loopback-test", source.get());
                assertEquals(1, ducker.duckCount());
                assertEquals(1, ducker.unduckCount());
                List<TranslationRecord> recent = history.recent(10);
                assertFalse(recent.isEmpty());
                assertEquals("loopback-test", recent.get(0).audioSource());
            }
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }
}
