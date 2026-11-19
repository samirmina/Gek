package com.gek.app;

import com.gek.audio.WavWriter;
import com.gek.core.AppPaths;
import com.gek.core.SettingsStore;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.DiarizationEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliRunnerMoreTest {

    private static AppPaths resolvePaths(Path cwd) throws Exception {
        Method m = AppPaths.class.getDeclaredMethod("resolve", Path.class, String.class);
        m.setAccessible(true);
        return (AppPaths) m.invoke(null, cwd, null);
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = CliRunner.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void speakerForIndexPicksContainingAndNearestAndFallbacks() throws Exception {
        Class<?> segClass = Class.forName("com.gek.inference.DiarizationEngine$Segment");
        var ctor = segClass.getDeclaredConstructor(double.class, double.class, String.class);
        ctor.setAccessible(true);
        Object s0 = ctor.newInstance(0.0, 1.0, "A");
        Object s1 = ctor.newInstance(2.0, 3.0, "B");
        @SuppressWarnings("unchecked")
        List<DiarizationEngine.Segment> segs = (List<DiarizationEngine.Segment>) (List<?>) List.of(s0, s1);

        String spA = (String) invoke("speakerForIndex",
                new Class[]{List.class, int.class, int.class, double.class}, segs, 0, 4, 4.0);
        assertEquals("A", spA);
        String spB = (String) invoke("speakerForIndex",
                new Class[]{List.class, int.class, int.class, double.class}, segs, 3, 4, 4.0);
        assertEquals("B", spB);

        String empty = (String) invoke("speakerForIndex",
                new Class[]{List.class, int.class, int.class, double.class}, List.of(), 0, 2, 2.0);
        assertEquals("SPEAKER_00", empty);

        Object sNull = ctor.newInstance(0.0, 1.0, null);
        @SuppressWarnings("unchecked")
        List<DiarizationEngine.Segment> oneNull = (List<DiarizationEngine.Segment>) (List<?>) List.of(sNull);
        String n = (String) invoke("speakerForIndex",
                new Class[]{List.class, int.class, int.class, double.class}, oneNull, 0, 1, 1.0);
        assertEquals("SPEAKER_00", n);
    }

    @Test
    void ensure16kMonoWavReturnsUsablePath(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("in.wav");
        float[] samples = new float[1600];
        for (int i = 0; i < samples.length; i++) samples[i] = (float) Math.sin(i * 0.1) * 0.2f;
        WavWriter.writeFloatMono16k(in, samples);

        Files.createDirectories(tmp.resolve("gek-java"));
        Files.createDirectories(tmp.resolve("resources").resolve("data"));
        AppPaths paths = resolvePaths(tmp);
        Path out = (Path) invoke("ensure16kMonoWav", new Class[]{Path.class, AppPaths.class}, in, paths);
        assertNotNull(out);
        assertTrue(Files.exists(out));
    }

    @Test
    void runModelCatalogAndApplySynonymsAndPlatformInfoDoNotThrow(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root.resolve("gek-java"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("whisper"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("argos").resolve("packages"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("pyannote"));
        Files.createDirectories(root.resolve("resources").resolve("data").resolve("synonyms"));
        Files.createDirectories(root.resolve("resources").resolve("data"));
        Files.writeString(root.resolve("resources").resolve("data").resolve("synonyms").resolve("en.json"),
                "{\"hello\":[\"hi\",\"hey\"]}");

        AppPaths paths = resolvePaths(root);
        assertDoesNotThrow(() -> invoke("runModelCatalog", new Class[]{AppPaths.class}, paths));
        assertDoesNotThrow(() -> invoke("runPlatformInfo", new Class[]{}, new Object[]{}));
        assertDoesNotThrow(() -> invoke("runApplySynonyms", new Class[]{String[].class, AppPaths.class},
                new String[]{"--apply-synonyms", "--text", "hello world", "--lang", "en"}, paths));
    }

    @Test
    void handleIfRequestedReturnsFalseForUnknownAndSomeSafeCommands(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root.resolve("gek-java"));
        Files.createDirectories(root.resolve("resources").resolve("data"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("whisper"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("argos").resolve("packages"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("pyannote"));
        AppPaths paths = resolvePaths(root);
        SettingsStore store = new SettingsStore(paths.settingsPath());
        try (HistoryRepository history = new HistoryRepository(paths.dbPath())) {
            assertFalse(CliRunner.handleIfRequested(new String[]{"--definitely-not-a-flag"}, paths, store, history, null));
            assertTrue(CliRunner.handleIfRequested(new String[]{"--platform-info"}, paths, store, history, null));
            assertTrue(CliRunner.handleIfRequested(new String[]{"--model-catalog"}, paths, store, history, null));
        }
    }

    @Test
    void runHistoryRecentAndSearchOnEmptyDb(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root.resolve("gek-java"));
        Files.createDirectories(root.resolve("resources").resolve("data"));
        AppPaths paths = resolvePaths(root);
        try (HistoryRepository history = new HistoryRepository(paths.dbPath())) {
            invoke("runHistoryRecent", new Class[]{String[].class, HistoryRepository.class}, new String[]{"--history-recent", "--limit", "5"}, history);
            invoke("runHistorySearch", new Class[]{String[].class, HistoryRepository.class}, new String[]{"--history-search", "--query", "none", "--limit", "5"}, history);
            history.save(TranslationRecord.ofNew("hello world", "en", "hola mundo", "es", "cli", "x", "file"));
            invoke("runHistoryRecent", new Class[]{String[].class, HistoryRepository.class}, new String[]{"--history-recent"}, history);
            invoke("runHistorySearch", new Class[]{String[].class, HistoryRepository.class}, new String[]{"--history-search", "--query", "hello"}, history);
        }
    }
}
