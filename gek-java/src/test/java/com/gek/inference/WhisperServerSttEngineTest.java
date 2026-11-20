package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic isolation tests for WhisperServerSttEngine (guard, helpers, parsing,
 * unusable-engine state) without spawning a real whisper-server process.
 */
class WhisperServerSttEngineTest {

    @TempDir Path tempDir;

    @AfterEach
    void clearProp() {
        System.clearProperty("gek.stt.disableWarmServer");
    }

    private AppPaths paths() {
        String prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", tempDir.toString());
        try {
            return AppPaths.resolve();
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot");
            else System.setProperty("gek.projectRoot", prev);
        }
    }

    /** Unusable engine (no server binary/model) without spawning any process. */
    private WhisperServerSttEngine unusableEngine(AppPaths p) {
        NativeRuntimeProbe.SttRuntime rt = new NativeRuntimeProbe.SttRuntime(
                java.util.Optional.empty(), java.util.Optional.empty(), "nope");
        String prev = System.getProperty("gek.stt.disableWarmServer");
        System.clearProperty("gek.stt.disableWarmServer");
        try {
            return new WhisperServerSttEngine(p, new AppSettings(), rt);
        } finally {
            if (prev == null) System.clearProperty("gek.stt.disableWarmServer");
            else System.setProperty("gek.stt.disableWarmServer", prev);
        }
    }

    @Test
    void tryCreateReturnsNullWhenDisabledByProperty() {
        System.setProperty("gek.stt.disableWarmServer", "true");
        assertNull(WhisperServerSttEngine.tryCreate(null, null, null));
        assertNull(WhisperServerSttEngine.tryCreate(com.gek.core.AppPaths.resolve(),
                com.gek.core.AppSettings.defaults(), null));
    }

    @Test
    void tryCreateReturnsNullWhenServerBinaryUnavailable() {
        AppPaths p = paths();
        NativeRuntimeProbe.SttRuntime rt = new NativeRuntimeProbe.SttRuntime(
                java.util.Optional.empty(), java.util.Optional.empty(), "nope");
        System.clearProperty("gek.stt.disableWarmServer");
        assertNull(WhisperServerSttEngine.tryCreate(p, new AppSettings(), rt));
    }

    @Test
    void unusableEngineReportsStateAndRejectsOperations() throws Exception {
        AppPaths p = paths();
        Files.createDirectories(p.dataDir().resolve("tmp"));
        WhisperServerSttEngine eng = unusableEngine(p);
        try {
            assertFalse(eng.isUsable());
            assertFalse(eng.streamingAvailable());
            assertNotNull(eng.reason());
            assertTrue(eng.reason().toLowerCase(Locale.ROOT).contains("whisper-server binary not found"),
                    "reason: " + eng.reason());

            ExecutionException warm = assertThrows(ExecutionException.class,
                    () -> eng.warmup().get(5, TimeUnit.SECONDS));
            assertTrue(warm.getCause() instanceof InferenceUnavailableException);

            Path wav = tempDir.resolve("x.wav");
            Files.writeString(wav, "x");
            ExecutionException tr = assertThrows(ExecutionException.class,
                    () -> eng.transcribe(wav, "auto").get(5, TimeUnit.SECONDS));
            assertTrue(tr.getCause() instanceof InferenceUnavailableException);

            StreamingSttSession session = eng.startSession("auto", new StreamingTranscriptionListener() {});
            assertNotNull(session);
            assertDoesNotThrow(session::close);
        } finally {
            assertDoesNotThrow(eng::close); // idempotent/safe
            assertDoesNotThrow(eng::close);
        }
    }

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = WhisperServerSttEngine.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void normalizeLangFiltersAutoAndBlanks() throws Exception {
        assertNull(invokeStatic("normalizeLang", new Class[]{String.class}, (Object) null));
        assertNull(invokeStatic("normalizeLang", new Class[]{String.class}, ""));
        assertNull(invokeStatic("normalizeLang", new Class[]{String.class}, "  "));
        assertNull(invokeStatic("normalizeLang", new Class[]{String.class}, "auto"));
        assertNull(invokeStatic("normalizeLang", new Class[]{String.class}, "detected"));
        assertNull(invokeStatic("normalizeLang", new Class[]{String.class}, "auto-detect"));
        assertEquals("en", invokeStatic("normalizeLang", new Class[]{String.class}, " EN "));
        assertEquals("fr", invokeStatic("normalizeLang", new Class[]{String.class}, "fr"));
    }

    @Test
    void extractTextFieldParsesJsonLikeContent() throws Exception {
        assertEquals("hello world",
                invokeStatic("extractTextField", new Class[]{String.class}, "{\"text\":\"hello world\"}"));
        // naive fallback preserves escaped char value (\\n -> n)
        assertEquals("line1nline2",
                invokeStatic("extractTextField", new Class[]{String.class}, "{\"text\":\"line1\\nline2\"}"));
        // fallback returns trimmed input when no "text" field
        assertEquals("{\"nope\":1}",
                invokeStatic("extractTextField", new Class[]{String.class}, "{\"nope\":1}"));
        assertEquals("plain text",
                invokeStatic("extractTextField", new Class[]{String.class}, "plain text"));
        assertEquals("", invokeStatic("extractTextField", new Class[]{String.class}, (Object) null));
    }

    @Test
    void parseResultExtractsTextAndLanguageVariants() throws Exception {
        AppPaths p = paths();
        WhisperServerSttEngine eng = unusableEngine(p);
        try {
            Method m = WhisperServerSttEngine.class.getDeclaredMethod("parseResult", String.class, String.class);
            m.setAccessible(true);

            TranscriptionResult r1 = (TranscriptionResult) m.invoke(eng, "{\"text\":\"hello\",\"language\":\"en\"}", "auto");
            assertEquals("hello", r1.text());
            assertEquals("en", r1.language());

            TranscriptionResult r2 = (TranscriptionResult) m.invoke(eng, "{\"transcription\":{\"text\":\"nested\"}}", "auto");
            assertEquals("nested", r2.text());

            TranscriptionResult r3 = (TranscriptionResult) m.invoke(eng, "{\"text\":\"x\",\"detected_language\":\"FR\"}", "auto");
            assertEquals("x", r3.text());
            assertEquals("fr", r3.language());

            // explicit hint wins/normalizes
            TranscriptionResult r4 = (TranscriptionResult) m.invoke(eng, "{\"text\":\"hola\",\"language\":\"en\"}", " ES ");
            assertEquals("hola", r4.text());
            assertEquals("es", r4.language());

            // malformed JSON falls back to naive extractor
            TranscriptionResult r5 = (TranscriptionResult) m.invoke(eng, "not-json {\"text\":\"ok\"} tail", "auto");
            assertEquals("ok", r5.text());
        } finally {
            eng.close();
        }
    }

    @Test
    void trimClampsAndNormalizesWhitespace() throws Exception {
        assertEquals("abc", invokeStatic("trim", new Class[]{String.class, int.class}, "abc", 10));
        assertEquals("abcde...", invokeStatic("trim", new Class[]{String.class, int.class}, "abcdef", 5));
        assertEquals("", invokeStatic("trim", new Class[]{String.class, int.class}, null, 5));
        assertEquals("a b", invokeStatic("trim", new Class[]{String.class, int.class}, "a\nb", 10));
    }

    @Test
    void buildMultipartContainsFieldsFileAndOptionalLanguage() throws Exception {
        AppPaths p = paths();
        WhisperServerSttEngine eng = unusableEngine(p);
        try {
            Path wav = tempDir.resolve("sample.wav");
            Files.write(wav, new byte[]{1, 2, 3, 4, 5});
            Method m = WhisperServerSttEngine.class.getDeclaredMethod("buildMultipart", Path.class, String.class, String.class);
            m.setAccessible(true);

            byte[] body = (byte[]) m.invoke(eng, wav, "BOUND", " EN ");
            String s = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(s.contains("--BOUND\r\n"));
            assertTrue(s.contains("name=\"response_format\"\r\n\r\njson\r\n"));
            assertTrue(s.contains("name=\"temperature\"\r\n\r\n0\r\n"));
            assertTrue(s.contains("name=\"language\"\r\n\r\nen\r\n"), s);
            assertTrue(s.contains("name=\"file\"; filename=\"sample.wav\""));
            assertTrue(s.contains("Content-Type: audio/wav"));
            assertTrue(s.endsWith("\r\n--BOUND--\r\n"));
            // file bytes present
            boolean hasBytes = false;
            for (int i = 0; i < body.length - 4; i++) {
                if (body[i] == 1 && body[i + 1] == 2 && body[i + 2] == 3 && body[i + 3] == 4 && body[i + 4] == 5) { hasBytes = true; break; }
            }
            assertTrue(hasBytes, "multipart should embed file bytes");

            byte[] bodyAuto = (byte[]) m.invoke(eng, wav, "B2", "auto");
            String sa = new String(bodyAuto, java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(sa.contains("name=\"language\""), "auto language should be omitted");
        } finally {
            eng.close();
        }
    }
}
