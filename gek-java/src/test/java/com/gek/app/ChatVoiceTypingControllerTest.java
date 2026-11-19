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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic tests for {@link ChatVoiceTypingController} finalize/caret logic.
 * Chat typing is decoupled from live captions/HUD: it injects at caret and writes
 * "dictation"/"dictation-cmd" history only (never "live"/HUD transcripts).
 */
class ChatVoiceTypingControllerTest {
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
        @Override public CompletableFuture<Void> warmup() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
            return CompletableFuture.completedFuture(new TranscriptionResult("", "en", 1.0, 0.0));
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

    private ChatVoiceTypingController newController(AppSettings settings, FakeTranslator tr,
                                                    FakeInput input, HistoryRepository history) {
        AppPaths paths = AppPaths.resolve();
        return new ChatVoiceTypingController(paths, settings, new FakeStt(), tr, history, input);
    }

    @Test
    void plainTranscriptInjectsTypingAndPersistsDictationHistory() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "en";
        s.target_language = "en";
        s.dictation_insert_mode = "transcript";
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        AtomicReference<String> lastPartial = new AtomicReference<>("seed");
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.setPartialListener(lastPartial::set);
            c.finalizeForTest("hello world", "en");

            assertEquals(1, input.actions.size(), "caret injection must occur once");
            assertEquals(InputAction.Kind.TYPE_TEXT, input.actions.get(0).kind());
            assertEquals("hello world ", input.actions.get(0).text());
            assertTrue(tr.calls.isEmpty(), "no translation expected for transcript mode/same lang");
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("injected: ")));
            assertEquals("", lastPartial.get(), "partial cleared after finalize (chat UI reset)");

            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("hello world", r.originalText());
            assertEquals("en", r.originalLang());
            assertEquals("hello world", r.translatedText());
            assertEquals("en", r.translatedLang());
            assertEquals("dictation", r.sourceName(), "chat typing records as dictation, not live");
            assertEquals("mic", r.audioSource());
        }
    }

    @Test
    void emptyOrBlankTranscriptInjectsNothingAndClearsPartial() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeInput input = new FakeInput();
        AtomicReference<String> lastPartial = new AtomicReference<>("speaking...");
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, new FakeTranslator(), input, repo)) {
            c.setPartialListener(lastPartial::set);
            c.finalizeForTest("   ", "en");
            assertTrue(input.actions.isEmpty());
            assertEquals(0, repo.count());
            assertEquals("", lastPartial.get());
        }
    }

    @Test
    void commandInterpretedExecutesActionsAndPersistsCommandRecord() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest("select all", "en");

            assertFalse(input.actions.isEmpty());
            assertEquals(InputAction.Kind.SELECT_ALL, input.actions.get(0).kind());
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("command: select-all")));
            assertTrue(tr.calls.isEmpty(), "commands must not be translated/injected as text");
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("select all", r.originalText());
            assertEquals("[select-all]", r.translatedText());
            assertEquals("en", r.translatedLang());
            assertEquals("dictation-cmd", r.sourceName(), "chat command record, never live");
        }
    }

    @Test
    void commandFailureReported() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeInput input = new FakeInput();
        input.result = false;
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, new FakeTranslator(), input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest("undo", "en");
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
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "hello world";
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.finalizeForTest("hola mundo", "es");

            assertEquals(1, tr.calls.size());
            assertArrayEquals(new String[]{"hola mundo", "es", "en"}, tr.calls.get(0));
            assertEquals(1, input.actions.size());
            assertEquals("hello world ", input.actions.get(0).text());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("hola mundo", r.originalText());
            assertEquals("es", r.originalLang());
            assertEquals("hello world", r.translatedText());
            assertEquals("en", r.translatedLang());
            assertEquals("dictation", r.sourceName());
        }
    }

    @Test
    void translatedInsertModeBlankTranslationDoesNotInject() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        s.dictation_insert_mode = "translated";
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "   ";
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest("hola", "es");
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
        FakeTranslator tr = new FakeTranslator();
        tr.throwOnTranslate = new RuntimeException("boom");
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest("hola", "es");
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
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.finalizeForTest("hello", "en");
            assertTrue(tr.calls.isEmpty());
            assertEquals("hello ", input.actions.get(0).text());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("en", r.translatedLang());
            assertEquals("hello", r.translatedText());
            assertEquals("dictation", r.sourceName());
        }
    }

    @Test
    void injectionFailureReportedButHistorySaved() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeInput input = new FakeInput();
        input.result = false;
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, new FakeTranslator(), input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest("hello", "en");
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("not-injected")));
            assertEquals(1, repo.count(), "record persisted even when injection not secured");
            assertEquals("dictation", repo.recent(1).get(0).sourceName());
        }
    }

    @Test
    void textAlreadyEndingWithSpaceNotDoubled() throws Exception {
        AppSettings s = AppSettings.defaults();
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, new FakeTranslator(), input, repo)) {
            c.finalizeForTest("hello ", "en");
            assertEquals("hello ", input.actions.get(0).text());
        }
    }

    @Test
    void autoLangTreatedAsAuto() throws Exception {
        // finalizeForTest normalizes hint; "auto"/"detected" -> null/auto source semantics via nvl
        AppSettings s = AppSettings.defaults();
        s.source_language = "auto";
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, new FakeTranslator(), input, repo)) {
            c.finalizeForTest("hi", "auto");
            assertEquals(1, input.actions.size());
            assertEquals("auto", repo.recent(1).get(0).originalLang());
        }
    }

    @Test
    void controllerRequiresInjectedExecutor() {
        // ChatVoiceTypingController accepts null -> falls back to platform Robot executor;
        // constructor with explicit fake proves decoupled injectable path (no HUD dependency).
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        FakeInput input = new FakeInput();
        try (ChatVoiceTypingController c = new ChatVoiceTypingController(paths, s, new FakeStt(),
                new FakeTranslator(), null, input)) {
            c.finalizeForTest("hello", "en");
            assertEquals(1, input.actions.size());
        }
    }
}
