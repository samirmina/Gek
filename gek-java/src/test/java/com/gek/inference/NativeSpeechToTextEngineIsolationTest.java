package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Component-isolation tests for NativeSpeechToTextEngine backend/streaming selection
 * logic. Deterministic and portable: no real whisper server/shim/CLI is executed.
 */
class NativeSpeechToTextEngineIsolationTest {

    @TempDir Path tempDir;

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

    private Path nativeDir(AppPaths p) {
        // Probe resolves binaries under <projectRoot>/gek-java/native
        return p.projectRoot().resolve("gek-java").resolve("native");
    }

    private AppSettings settings(String model) {
        AppSettings s = new AppSettings();
        s.inference_mode = "native";
        s.whisper_model = model;
        return s;
    }

    private StreamingTranscriptionListener noOpListener() {
        return new StreamingTranscriptionListener() {};
    }

    @Test
    void unusableRuntimeReportsNotReadyAndFailsWarmupAndTranscribe() throws Exception {
        AppPaths p = paths();
        Files.createDirectories(p.dataDir().resolve("tmp"));
        System.setProperty("gek.stt.disableWarmServer", "true");
        try (NativeSpeechToTextEngine eng = new NativeSpeechToTextEngine(p, settings("small"))) {
            assertFalse(eng.runtimeUsable(), "no assets => runtime not usable");
            assertFalse(eng.inJvm(), "no shim => not in-JVM");
            assertFalse(eng.streamingAvailable(), "streaming unavailable without usable runtime");
            assertNotNull(eng.reason());
            String reason = eng.reason().toLowerCase(Locale.ROOT);
            assertTrue(reason.contains("not ready") || reason.contains("unavailable") || reason.contains("missing")
                    || reason.contains("no ") || reason.contains("model"),
                    "reason should explain unavailability: " + eng.reason());

            ExecutionException warm = assertThrows(ExecutionException.class,
                    () -> eng.warmup().get(5, TimeUnit.SECONDS));
            assertTrue(warm.getCause() instanceof InferenceUnavailableException);

            Path wav = tempDir.resolve("x.wav");
            Files.writeString(wav, "x");
            ExecutionException tr = assertThrows(ExecutionException.class,
                    () -> eng.transcribe(wav, "auto").get(5, TimeUnit.SECONDS));
            assertTrue(tr.getCause() instanceof InferenceUnavailableException);

            // Session object can be created (windowed); backend unusable but API is stable.
            StreamingSttSession session = eng.startSession("auto", noOpListener());
            assertNotNull(session);
            assertDoesNotThrow(session::close);
        } finally {
            System.clearProperty("gek.stt.disableWarmServer");
        }
    }

    @Test
    void cliRuntimeSelectedWhenWarmServerDisabledAndNoShim() throws Exception {
        AppPaths p = paths();
        Files.createDirectories(p.dataDir().resolve("tmp"));
        Path nd = nativeDir(p);
        Path bin = nd.resolve("bin");
        Files.createDirectories(bin);
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "whisper-cli.exe" : "whisper-cli";
        Path cli = Files.writeString(bin.resolve(exe), "stub");
        Path modelDir = nd.resolve("models");
        Files.createDirectories(modelDir);
        // Probe requires ggml-small.bin >= 430MB; create sparse file (fast, no real data written).
        Path model = modelDir.resolve("ggml-small.bin");
        try (RandomAccessFile raf = new RandomAccessFile(model.toFile(), "rw")) {
            raf.setLength(430L * 1024 * 1024);
        }

        // Also provide a server binary: guard must prevent warm-server selection.
        String srvExe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "whisper-server.exe" : "whisper-server";
        Files.writeString(bin.resolve(srvExe), "stub-server");

        System.setProperty("gek.stt.disableWarmServer", "true");
        try (NativeSpeechToTextEngine eng = new NativeSpeechToTextEngine(p, settings("small"))) {
            assertTrue(eng.runtimeUsable(), "CLI+model present => runtime usable");
            assertTrue(eng.streamingAvailable(), "usable runtime => streaming available");
            assertFalse(eng.inJvm(), "no shim => subprocess/CLI path, not in-JVM");
            String reason = eng.reason() == null ? "" : eng.reason().toLowerCase(Locale.ROOT);
            assertFalse(reason.contains("warm whisper-server stt backend active"),
                    "warm server must not be selected when disabled: " + eng.reason());
            assertFalse(reason.isBlank());

            assertTrue(Files.exists(cli));
            assertTrue(Files.exists(model));

            StreamingSttSession session = eng.startSession("en", noOpListener());
            assertNotNull(session);
            assertDoesNotThrow(session::close);
            // Do NOT call transcribe/warmup here: CLI backend would try to exec the stub.
        } finally {
            System.clearProperty("gek.stt.disableWarmServer");
        }
    }

    @Test
    void transcribeRejectsNullWavPath() throws Exception {
        AppPaths p = paths();
        Files.createDirectories(p.dataDir().resolve("tmp"));
        System.setProperty("gek.stt.disableWarmServer", "true");
        try (NativeSpeechToTextEngine eng = new NativeSpeechToTextEngine(p, settings("small"))) {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> eng.transcribe(null, "auto").get(5, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof InferenceUnavailableException);
            assertTrue(String.valueOf(ex.getCause().getMessage()).contains("wavPath"));
        } finally {
            System.clearProperty("gek.stt.disableWarmServer");
        }
    }
}