package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NativeSpeechToTextEngineInternalsTest {

    private static AppPaths resolvePaths(Path cwd) throws Exception {
        Method m = AppPaths.class.getDeclaredMethod("resolve", Path.class, String.class);
        m.setAccessible(true);
        return (AppPaths) m.invoke(null, cwd, null);
    }

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = NativeSpeechToTextEngine.class.getDeclaredMethod(name, types);
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
        assertEquals("es", invokeStatic("normalizeLang", new Class[]{String.class}, "es"));
    }

    @Test
    void extractDetectedLanguageParsesWhisperLine() throws Exception {
        String log = "noise\nwhisper_full_with_state: auto-detected language: en (p = 0.958783)\nmore";
        assertEquals("en", invokeStatic("extractDetectedLanguage", new Class[]{String.class}, log));
        assertNull(invokeStatic("extractDetectedLanguage", new Class[]{String.class}, "nope"));
        assertNull(invokeStatic("extractDetectedLanguage", new Class[]{String.class}, (Object) null));
    }

    @Test
    void cleanTranscriptStripsNoiseAndTimestamps() throws Exception {
        String in = "whisper_init: x\n[00:00:00.000 --> 00:00:02.000] Hello world\n[BLANK_AUDIO]\nsystem_info: y\nSecond line";
        String out = (String) invokeStatic("cleanTranscript", new Class[]{String.class}, in);
        assertTrue(out.contains("Hello world"));
        assertTrue(out.contains("Second line"));
        assertFalse(out.contains("whisper_init"));
        assertFalse(out.contains("BLANK_AUDIO"));
        assertEquals("", invokeStatic("cleanTranscript", new Class[]{String.class}, "  \nwhisper_model_load: x\n"));
    }

    @Test
    void extractTextPrefersStdout() throws Exception {
        String out = (String) invokeStatic("extractText", new Class[]{String.class, String.class}, "hello", "stderr hello");
        assertEquals("hello", out);
        String out2 = (String) invokeStatic("extractText", new Class[]{String.class, String.class}, "whisper_init: x", "from err");
        assertEquals("from err", out2);
    }

    @Test
    void trimAndFailureOutputBehavior() throws Exception {
        assertEquals("abc", invokeStatic("trim", new Class[]{String.class, int.class}, "abc", 10));
        // implementation truncates to max chars then appends "..." (no in-place shortening before suffix)
        assertEquals("abcde...", invokeStatic("trim", new Class[]{String.class, int.class}, "abcdef", 5));
        assertEquals("", invokeStatic("trim", new Class[]{String.class, int.class}, null, 5));

        assertTrue((boolean) invokeStatic("isFailureOutput", new Class[]{String.class, String.class},
                "failed to initialize whisper context", ""));
        assertTrue((boolean) invokeStatic("isFailureOutput", new Class[]{String.class, String.class},
                "ok", "error: failed to initialize whisper context"));
        assertFalse((boolean) invokeStatic("isFailureOutput", new Class[]{String.class, String.class},
                "all good", "hello transcript"));
    }

    @Test
    void constructorAndReasonWhenRuntimeUnavailable(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root.resolve("gek-java"));
        Files.createDirectories(root.resolve("resources").resolve("data"));
        AppPaths paths = resolvePaths(root);
        AppSettings settings = new AppSettings();
        settings.whisper_model = "small";
        NativeSpeechToTextEngine eng = new NativeSpeechToTextEngine(paths, settings);
        assertNotNull(eng.reason());
        eng.runtimeUsable();
        eng.inJvm();
        eng.close();
        assertNotNull(eng.transcribe(null, "en"));
    }
}
