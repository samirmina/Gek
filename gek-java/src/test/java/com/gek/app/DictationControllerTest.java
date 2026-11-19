package com.gek.app;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import com.gek.inject.InputAction;
import com.gek.inject.InputExecutor;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DictationControllerTest {
    @TempDir Path tmp;
    private String prevRoot;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(tmp.resolve("resources/data"));
        Files.createDirectories(tmp.resolve("resources/models"));
        Files.createDirectories(tmp.resolve("resources/data/synonyms"));
        prevRoot = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", tmp.toString());
    }

    @AfterEach
    void tearDown() {
        if (prevRoot == null) System.clearProperty("gek.projectRoot");
        else System.setProperty("gek.projectRoot", prevRoot);
    }

    static class FakeStt implements SpeechToTextEngine {
        final AtomicReference<String> lastLangHint = new AtomicReference<>();
        volatile TranscriptionResult result = new TranscriptionResult("", "en", 1.0, 0.1);
        volatile RuntimeException throwOnTranscribe;
        @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
            lastLangHint.set(languageHint);
            if (throwOnTranscribe != null) return CompletableFuture.failedFuture(throwOnTranscribe);
            return CompletableFuture.completedFuture(result);
        }
        @Override public void close() {}
    }

    static class FakeTranslator implements TranslationEngine {
        volatile String translated = "translated";
        volatile RuntimeException throwOnTranslate;
        final List<String[]> calls = new CopyOnWriteArrayList<>();
        @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
            calls.add(new String[]{text, sourceLang, targetLang});
            if (throwOnTranslate != null) return CompletableFuture.failedFuture(throwOnTranslate);
            return CompletableFuture.completedFuture(translated);
        }
        @Override public void close() {}
    }

    static class FakeInput implements InputExecutor {
        final List<InputAction> actions = new CopyOnWriteArrayList<>();
        volatile boolean result = true;
        @Override public boolean execute(InputAction action) { actions.add(action); return result; }
        @Override public String name() { return "fake"; }
    }

    private DictationController newController(AppSettings settings, FakeStt stt, FakeTranslator tr,
                                              FakeInput input, HistoryRepository history) {
        AppPaths paths = AppPaths.resolve();
        return new DictationController(paths, settings, stt, tr, history, input);
    }

    @Test
    void plainTranscriptInjectsTypingAndPersistsHistory() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "en";
        s.target_language = "en";
        s.dictation_insert_mode = "transcript";
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hello world", "en", 0.9, 0.5);
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f, -0.1f, 0.2f});

            assertEquals(1, input.actions.size());
            assertEquals(InputAction.Kind.TYPE_TEXT, input.actions.get(0).kind());
            assertEquals("hello world ", input.actions.get(0).text());
            assertTrue(tr.calls.isEmpty(), "no translation expected for transcript mode/same lang");
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("injected: ")));
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("hello world", r.originalText());
            assertEquals("en", r.originalLang());
            assertEquals("hello world", r.translatedText());
            assertEquals("en", r.translatedLang());
            assertEquals("dictation", r.sourceName());
            assertEquals("mic", r.audioSource());
        }
    }

    @Test
    void emptyOrBlankTranscriptDoesNothing() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("   ", "en", 0.9, 0.2);
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, tr, input, repo)) {
            c.processForTest(new float[]{0.1f});
            assertTrue(input.actions.isEmpty());
            assertEquals(0, repo.count());
            assertTrue(tr.calls.isEmpty());
        }
    }

    @Test
    void commandInterpretedExecutesActionsAndPersistsCommandRecord() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("select all", "en", 0.9, 0.2);
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f});
            assertFalse(input.actions.isEmpty());
            assertEquals(InputAction.Kind.SELECT_ALL, input.actions.get(0).kind());
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("command: select-all")));
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("select all", r.originalText());
            assertEquals("[select-all]", r.translatedText());
            assertEquals("en", r.translatedLang());
            assertEquals("dictation-cmd", r.sourceName());
            assertTrue(tr.calls.isEmpty());
        }
    }

    @Test
    void commandFailureReported() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("undo", "en", 0.9, 0.2);
        FakeInput input = new FakeInput();
        input.result = false;
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, new FakeTranslator(), input, repo)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f});
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("command-failed: undo")));
            assertEquals(1, repo.count(), "command attempt still recorded");
        }
    }

    @Test
    void translatedInsertModeInjectsTranslation() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        s.dictation_insert_mode = "translated";
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hola mundo", "es", 0.9, 0.3);
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "hello world";
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, tr, input, repo)) {
            c.processForTest(new float[]{0.1f});
            assertEquals(1, tr.calls.size());
            assertArrayEquals(new String[]{"hola mundo", "es", "en"}, tr.calls.get(0));
            assertEquals(1, input.actions.size());
            assertEquals("hello world ", input.actions.get(0).text());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("hola mundo", r.originalText());
            assertEquals("es", r.originalLang());
            assertEquals("hello world", r.translatedText());
            assertEquals("en", r.translatedLang());
        }
    }

    @Test
    void translatedInsertModeBlankTranslationDoesNotInject() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        s.dictation_insert_mode = "translated";
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hola", "es", 0.9, 0.2);
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "   ";
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f});
            assertTrue(input.actions.isEmpty());
            assertEquals(0, repo.count());
            assertTrue(statuses.stream().anyMatch(m -> m.contains("translation unavailable/invalid")));
        }
    }

    @Test
    void translatedInsertModeExceptionDoesNotInject() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        s.dictation_insert_mode = "translated";
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hola", "es", 0.9, 0.2);
        FakeTranslator tr = new FakeTranslator();
        tr.throwOnTranslate = new RuntimeException("boom");
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f});
            assertTrue(input.actions.isEmpty());
            assertEquals(0, repo.count());
            assertTrue(statuses.stream().anyMatch(m -> m.contains("translation failed")));
        }
    }

    @Test
    void sameLanguageSkipsTranslationEvenIfModeTranslated() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "en";
        s.target_language = "en";
        s.dictation_insert_mode = "translated";
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hello", "en", 0.9, 0.2);
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, tr, input, repo)) {
            c.processForTest(new float[]{0.1f});
            assertTrue(tr.calls.isEmpty());
            assertEquals("hello ", input.actions.get(0).text());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("en", r.translatedLang());
            assertEquals("hello", r.translatedText());
        }
    }

    @Test
    void sourceLanguageAutoPassesNullHint() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "auto";
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hi", "en", 0.9, 0.1);
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, new FakeTranslator(), new FakeInput(), repo)) {
            c.processForTest(new float[]{0.1f});
            assertNull(stt.lastLangHint.get());
        }
        s.source_language = "detected";
        stt.lastLangHint.set("sentinel");
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, new FakeTranslator(), new FakeInput(), repo)) {
            c.processForTest(new float[]{0.1f});
            assertNull(stt.lastLangHint.get());
        }
    }

    @Test
    void injectionFailureReportedButHistorySaved() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hello", "en", 0.9, 0.1);
        FakeInput input = new FakeInput();
        input.result = false;
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, new FakeTranslator(), input, repo)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f});
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("not-injected")));
            assertEquals(1, repo.count());
        }
    }

    @Test
    void textAlreadyEndingWithSpaceNotDoubled() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeStt stt = new FakeStt();
        stt.result = new TranscriptionResult("hello ", "en", 0.9, 0.1);
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, new FakeTranslator(), input, repo)) {
            c.processForTest(new float[]{0.1f});
            assertEquals("hello ", input.actions.get(0).text());
        }
    }

    @Test
    void sttFailureReportsErrorAndCleansUp() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeStt stt = new FakeStt();
        stt.throwOnTranscribe = new RuntimeException("stt boom");
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             DictationController c = newController(s, stt, new FakeTranslator(), input, repo)) {
            c.setStatusListener(statuses::add);
            c.processForTest(new float[]{0.1f});
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("error:")));
            assertTrue(input.actions.isEmpty());
            assertEquals(0, repo.count());
        }
    }
}
