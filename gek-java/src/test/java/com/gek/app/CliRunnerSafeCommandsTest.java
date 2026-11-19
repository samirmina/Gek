package com.gek.app;

import com.gek.audio.WavWriter;
import com.gek.core.AppPaths;
import com.gek.core.SettingsStore;
import com.gek.db.HistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliRunnerSafeCommandsTest {

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

    private static Path makeProject(@TempDir Path tmp, String name) throws Exception {
        Path root = tmp.resolve(name);
        Files.createDirectories(root.resolve("gek-java"));
        Files.createDirectories(root.resolve("resources").resolve("data").resolve("synonyms"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("whisper"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("argos").resolve("packages"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("pyannote").resolve("speaker-diarization-community-1"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("whisper-ggml"));
        Files.writeString(root.resolve("resources").resolve("data").resolve("settings.json"), "{}");
        return root;
    }

    private static String capture(RunnableWithEx r) throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(baos)) {
            System.setOut(ps);
            r.run();
        } finally {
            System.setOut(old);
        }
        return baos.toString();
    }

    @FunctionalInterface interface RunnableWithEx { void run() throws Exception; }

    @Test
    void readinessAndInfoCommandsWithNullEngines(@TempDir Path tmp) throws Exception {
        Path root = makeProject(tmp, "proj");
        AppPaths paths = resolvePaths(root);
        SettingsStore store = new SettingsStore(paths.settingsPath());

        String r1 = capture(() -> invoke("runWsl2Readiness",
                new Class[]{AppPaths.class, SettingsStore.class, com.gek.inference.EngineFactory.Engines.class},
                paths, store, null));
        assertTrue(r1.contains("Platform readiness report") || r1.contains("readiness"));
        assertTrue(r1.contains("[OK] platform readiness completed"));

        String r2 = capture(() -> invoke("runInferenceCheck",
                new Class[]{AppPaths.class, SettingsStore.class, com.gek.inference.EngineFactory.Engines.class},
                paths, store, null));
        assertTrue(r2.contains("Inference readiness check") || r2.contains("effective_mode=unknown"));
        assertTrue(r2.contains("[OK] inference-check completed"));

        String r3 = capture(() -> invoke("runNativeInfo",
                new Class[]{AppPaths.class, SettingsStore.class, com.gek.inference.EngineFactory.Engines.class},
                paths, store, null));
        assertTrue(r3.contains("Effective inference mode") || r3.contains("Configured inference_mode"));
    }

    @Test
    void ensure16kMonoWavAndSegmentFile(@TempDir Path tmp) throws Exception {
        Path root = makeProject(tmp, "proj2");
        AppPaths paths = resolvePaths(root);
        SettingsStore store = new SettingsStore(paths.settingsPath());

        // 8k mono wav-like content won't be true format, but ensure16kMonoWav should handle via Java Sound path or fallback
        Path in = tmp.resolve("in16.wav");
        float[] samples = new float[3200]; // 0.2s at 16k
        for (int i = 0; i < samples.length; i++) {
            double t = i / 16000.0;
            samples[i] = (i % 400 < 200) ? 0.2f : 0.0f; // some voiced/silence pattern
        }
        WavWriter.writeFloatMono16k(in, samples);

        Path out = (Path) invoke("ensure16kMonoWav", new Class[]{Path.class, AppPaths.class}, in, paths);
        assertNotNull(out);
        assertTrue(Files.exists(out));

        String seg = capture(() -> invoke("runSegmentFile",
                new Class[]{String[].class, AppPaths.class, SettingsStore.class},
                new String[]{"--segment-file", "--input", in.toString()}, paths, store));
        assertTrue(seg.contains("[OK] segment-file"));
        assertTrue(seg.contains("segments="));
    }

    @Test
    void handleListAudioAndApplySynonymsBranches(@TempDir Path tmp) throws Exception {
        Path root = makeProject(tmp, "proj3");
        Files.writeString(root.resolve("resources").resolve("data").resolve("synonyms").resolve("en.json"),
                "{\"color\":[\"colour\"]}");
        AppPaths paths = resolvePaths(root);
        SettingsStore store = new SettingsStore(paths.settingsPath());
        try (HistoryRepository history = new HistoryRepository(paths.dbPath())) {
            assertTrue(CliRunner.handleIfRequested(new String[]{"--list-audio-devices"}, paths, store, history, null));
            assertTrue(CliRunner.handleIfRequested(new String[]{"--apply-synonyms", "--text", "my color", "--lang", "en"}, paths, store, history, null));
        }
    }
}
