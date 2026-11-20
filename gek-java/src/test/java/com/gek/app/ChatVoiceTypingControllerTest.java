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
 * Deterministic tests for {@link ChatVoiceTypingController} translation-oriented in-field chat typing.
 * Partials/finals are translated and streamed/replaced into the focused external field (no overlay),
 * finalize leaves text for review (no auto-send/trailing space), and history remains isolated as
 * "dictation"/"dictation-cmd" (never "live"/HUD).
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
    void sameLanguageFinalizeLeavesTextInFieldForReview() throws Exception {
        // Translation-oriented chat typing: when src==tgt no translation; final text is left in field
        // (no auto-send/trailing space) and reported as ready.
        AppSettings s = AppSettings.defaults();
        s.source_language = "en";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        AtomicReference<String> lastPartial = new AtomicReference<>("seed");
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.setPartialListener(lastPartial::set);
            c.finalizeForTest("hello world", "en");

            assertEquals(1, input.actions.size(), "field update must occur once");
            assertEquals(InputAction.Kind.TYPE_TEXT, input.actions.get(0).kind());
            assertEquals("hello world", input.actions.get(0).text(), "left for review (no trailing space)");
            assertTrue(tr.calls.isEmpty(), "no translation when src==tgt");
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("ready: ")), "final left ready for review");
            assertEquals("", lastPartial.get(), "partial cleared after finalize");

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
    void emptyOrBlankTranscriptClearsFieldAndPartial() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.target_language = "en";
        FakeInput input = new FakeInput();
        AtomicReference<String> lastPartial = new AtomicReference<>("speaking...");
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, new FakeTranslator(), input, repo)) {
            c.setPartialListener(lastPartial::set);
            c.finalizeForTest("   ", "en");
            assertTrue(input.actions.isEmpty(), "nothing to replace when field empty");
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
    void translationOrientedFinalizeInjectsTranslatedText() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "hello world";
        FakeInput input = new FakeInput();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.finalizeForTest("hola mundo", "es");

            assertEquals(1, tr.calls.size());
            assertArrayEquals(new String[]{"hola mundo", "es", "en"}, tr.calls.get(0));
            assertEquals(1, input.actions.size());
            assertEquals("hello world", input.actions.get(0).text(), "translated text left for review");
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("hola mundo", r.originalText());
            assertEquals("es", r.originalLang());
            assertEquals("hello world", r.translatedText());
            assertEquals("en", r.translatedLang());
            assertEquals("dictation", r.sourceName());
        }
    }

    @Test
    void translationUnavailableFallsBackToTranscriptInField() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "   ";
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest("hola", "es");
            assertEquals(1, input.actions.size(), "safe fallback: keep transcript instead of dropping speech");
            assertEquals("hola", input.actions.get(0).text());
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("hola", r.originalText());
            assertEquals("hola", r.translatedText(), "fallback persists transcript");
            assertEquals("es", r.translatedLang());
            assertEquals("dictation", r.sourceName());
            assertTrue(statuses.stream().anyMatch(m -> m.contains("translation unavailable/invalid") && m.contains("inserted transcript")));
            assertTrue(statuses.stream().anyMatch(m -> m.contains("ready: ") || m.contains("not-injected")));
        }
    }

    @Test
    void translationExceptionFallsBackToTranscriptInField() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        tr.throwOnTranslate = new RuntimeException("boom");
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest("hola", "es");
            assertEquals(1, input.actions.size(), "safe fallback: keep transcript instead of dropping speech");
            assertEquals("hola", input.actions.get(0).text());
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals("hola", r.originalText());
            assertEquals("hola", r.translatedText(), "fallback persists transcript");
            assertEquals("es", r.translatedLang());
            assertEquals("dictation", r.sourceName());
            assertTrue(statuses.stream().anyMatch(m -> m.contains("translation failed") && m.contains("inserted transcript")));
            assertTrue(statuses.stream().anyMatch(m -> m.contains("ready: ") || m.contains("not-injected")));
        }
    }

    @Test
    void arabicDetectedTranscriptFallsBackToTranscriptWhenTranslationUnavailable() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "auto";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        tr.translated = ""; // unavailable/invalid translation
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        String arabic = "مرحبا كيف حالك";
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.setStatusListener(statuses::add);
            c.finalizeForTest(arabic, "ar");

            assertEquals(1, tr.calls.size(), "translation-oriented: attempts ar->en");
            assertArrayEquals(new String[]{arabic, "ar", "en"}, tr.calls.get(0));
            assertEquals(1, input.actions.size(), "Arabic transcript must be placed in field, not dropped");
            assertEquals(arabic, input.actions.get(0).text());
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals(arabic, r.originalText());
            assertEquals("ar", r.originalLang(), "detected Arabic language preserved");
            assertEquals(arabic, r.translatedText(), "fallback persists transcript when translation unavailable");
            assertEquals("ar", r.translatedLang(), "fallback records transcript language");
            assertEquals("dictation", r.sourceName(), "chat typing isolated from live captions");
            assertTrue(statuses.stream().anyMatch(m -> m.contains("translation unavailable/invalid") && m.contains("inserted transcript")));
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("ready:") || m.contains("ready:")));
        }
    }

    @Test
    void arabicDetectedTranscriptTranslatedWhenAvailable() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "auto";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "hello how are you";
        FakeInput input = new FakeInput();
        String arabic = "مرحبا كيف حالك";
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.finalizeForTest(arabic, "ar");

            assertEquals(1, tr.calls.size(), "should translate detected Arabic to target");
            assertArrayEquals(new String[]{arabic, "ar", "en"}, tr.calls.get(0));
            assertEquals(1, input.actions.size());
            assertEquals("hello how are you", input.actions.get(0).text(), "translated text left in field");
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals(arabic, r.originalText());
            assertEquals("ar", r.originalLang());
            assertEquals("hello how are you", r.translatedText());
            assertEquals("en", r.translatedLang());
            assertEquals("dictation", r.sourceName());
            assertEquals("mic", r.audioSource());
        }
    }

    @Test
    void arabicDetectedTranscriptKeptWhenTargetMatchesSource() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "auto";
        s.target_language = "ar";
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "SHOULD_NOT_BE_USED";
        FakeInput input = new FakeInput();
        String arabic = "مرحبا";
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, tr, input, repo)) {
            c.finalizeForTest(arabic, "ar");

            assertTrue(tr.calls.isEmpty(), "no translation when src==tgt");
            assertEquals(1, input.actions.size());
            assertEquals(arabic, input.actions.get(0).text());
            assertEquals(1, repo.count());
            TranslationRecord r = repo.recent(1).get(0);
            assertEquals(arabic, r.originalText());
            assertEquals("ar", r.originalLang());
            assertEquals(arabic, r.translatedText());
            assertEquals("ar", r.translatedLang());
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
    void autoLangTreatedAsAuto() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "auto";
        s.target_language = "en";
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
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        FakeInput input = new FakeInput();
        try (ChatVoiceTypingController c = new ChatVoiceTypingController(paths, s, new FakeStt(),
                new FakeTranslator(), null, input)) {
            c.finalizeForTest("hello", "en");
            assertEquals(1, input.actions.size());
        }
    }

    @Test
    void chatFlowIsIsolatedFromLiveCaptionsAndHud() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "en";
        s.target_language = "en";
        FakeInput input = new FakeInput();
        List<String> statuses = new CopyOnWriteArrayList<>();
        List<String> partials = new CopyOnWriteArrayList<>();
        try (HistoryRepository repo = new HistoryRepository(AppPaths.resolve().dbPath());
             ChatVoiceTypingController c = newController(s, new FakeTranslator(), input, repo)) {
            c.setStatusListener(statuses::add);
            c.setPartialListener(partials::add);
            c.finalizeForTest("hello there", "en");
            c.finalizeForTest("select all", "en"); // command path
            c.finalizeForTest("   ", "en");         // blank -> no history/injection

            assertEquals(2, repo.count(), "only non-blank utterances/commands persisted");
            for (TranslationRecord r : repo.recent(10)) {
                assertTrue("dictation".equals(r.sourceName()) || "dictation-cmd".equals(r.sourceName()),
                        "chat typing must never write live-caption history: " + r.sourceName());
                assertNotEquals("live", r.sourceName());
                assertEquals("mic", r.audioSource());
            }
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("ready:")), "final text left ready in field");
            assertTrue(statuses.stream().anyMatch(m -> m.startsWith("command:")));
            assertFalse(partials.isEmpty(), "partial listener receives finalize/reset updates");
        }
    }

    @Test
    void streamingPartialDoesNotInjectDuringListening_singleFinalUpdateOnStop() throws Exception {
        // Claude-like single-update flow: no in-field injection while speaking; one sanitized/composed
        // update is written on finalize (hotkey stop).
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        tr.translated = "hello world";
        FakeInput input = new FakeInput();
        try (ChatVoiceTypingController c = newController(s, tr, input, null)) {
            c.partialForTest("hola", "es");
            c.partialForTest("hola mundo", "es");
            assertTrue(input.actions.isEmpty(), "partials must not inject into the field while listening");

            c.finalizeForTest("hola mundo", "es");
            assertEquals(1, input.actions.size(), "single final update on stop");
            assertEquals(InputAction.Kind.TYPE_TEXT, input.actions.get(0).kind());
            assertEquals("hello world", input.actions.get(0).text(), "final translated text left for review");
            assertTrue(tr.calls.stream().anyMatch(a -> "hola mundo".equals(a[0]) && "es".equals(a[1]) && "en".equals(a[2])));
        }
    }

    @Test
    void commonPrefixLengthComputesSharedPrefix() {
        assertEquals(0, ChatVoiceTypingController.commonPrefixLength("", "hello"));
        assertEquals(5, ChatVoiceTypingController.commonPrefixLength("hello", "hello world"));
        assertEquals(6, ChatVoiceTypingController.commonPrefixLength("hello world", "hello there"));
        assertEquals(0, ChatVoiceTypingController.commonPrefixLength("abc", "xyz"));
        assertEquals(0, ChatVoiceTypingController.commonPrefixLength(null, "x"));
    }

    private static boolean hasBackspace(List<InputAction> actions, int count) {
        for (InputAction a : actions) {
            if (a.kind() == InputAction.Kind.BACKSPACE && a.count() == count) return true;
        }
        return false;
    }

    @Test
    void streamingPartialNoInjection_translationFallbackAppliedOnFinalOnly() throws Exception {
        AppSettings s = AppSettings.defaults();
        s.source_language = "es";
        s.target_language = "en";
        FakeTranslator tr = new FakeTranslator();
        tr.throwOnTranslate = new RuntimeException("boom");
        FakeInput input = new FakeInput();
        try (ChatVoiceTypingController c = newController(s, tr, input, null)) {
            c.partialForTest("hola", "es");
            assertTrue(input.actions.isEmpty(), "no injection while listening");

            c.finalizeForTest("hola", "es");
            assertEquals(1, input.actions.size(), "single final update on stop");
            assertEquals("hola", input.actions.get(0).text(), "fallback to transcript on finalize when translation fails");
        }
    }
}
