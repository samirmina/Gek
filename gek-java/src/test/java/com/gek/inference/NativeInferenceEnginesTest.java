package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativeInferenceEnginesTest {

    @TempDir Path tmp;

    @Test
    void nativeEnginesProvideActionableStatus() throws Exception {
        AppPaths paths = AppPaths.resolve();
        AppSettings s = AppSettings.defaults();
        NativeSpeechToTextEngine stt = new NativeSpeechToTextEngine(paths, s);
        NativeTranslationEngine tr = new NativeTranslationEngine(paths);
        NativeDiarizationEngine di = new NativeDiarizationEngine(paths);

        assertNotNull(stt.reason());
        assertFalse(stt.reason().isBlank());
        assertTrue(stt.reason().toLowerCase().contains("whisper") || stt.reason().toLowerCase().contains("stt") || stt.reason().toLowerCase().contains("ready"));
        assertNotNull(tr.reason());
        assertTrue(tr.reason().toLowerCase().contains("translation") || tr.reason().toLowerCase().contains("argos") || tr.reason().toLowerCase().contains("ctranslate"));
        assertNotNull(di.reason());
        assertTrue(di.reason().toLowerCase().contains("diarization") || di.reason().toLowerCase().contains("pyannote"));

        Path tmp = Files.createTempFile("gek-native", ".wav");
        try {
            // write minimal valid wav header+silence so whisper.cpp can read it when runtime is usable
            writeSilentWav(tmp, 16000, 1.0);
            if (!stt.runtimeUsable()) {
                ExecutionException ex1 = assertThrows(ExecutionException.class, () -> stt.transcribe(tmp, null).get(30, TimeUnit.SECONDS));
                assertFalse(root(ex1).isBlank());
            } else {
                var res = stt.transcribe(tmp, null).get(60, TimeUnit.SECONDS);
                assertNotNull(res);
                assertNotNull(res.text());
            }
            if (!tr.runtimeUsable()) {
                ExecutionException ex2 = assertThrows(ExecutionException.class, () -> tr.translate("hi", "en", "es").get(10, TimeUnit.SECONDS));
                assertFalse(root(ex2).isBlank());
            } else {
                String out = tr.translate("hello world", "en", "es").get(30, TimeUnit.SECONDS);
                assertNotNull(out);
                assertFalse(out.isBlank());
            }
            if (!di.runtimeUsable()) {
                ExecutionException ex3 = assertThrows(ExecutionException.class, () -> di.diarize(tmp).get(10, TimeUnit.SECONDS));
                assertFalse(root(ex3).isBlank());
            } else {
                assertNotNull(di.diarize(tmp).get(30, TimeUnit.SECONDS));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void translationRejectsInvalidOrUnavailableSourceWithoutSidecar() throws Exception {
        // synthetic project with BPE-only argos package (unusable by sentencepiece helper) should not be selected
        Files.createDirectories(tmp.resolve("resources/models/argos/packages/translate-es_en-1_9/model"));
        Files.createDirectories(tmp.resolve("resources/data"));
        Files.writeString(tmp.resolve("resources/models/argos/packages/translate-es_en-1_9/metadata.json"),
                "{\n  \"from_code\": \"es\", \"to_code\": \"en\"\n}\n");
        Files.writeString(tmp.resolve("resources/models/argos/packages/translate-es_en-1_9/model/model.bin"), "x");
        Files.writeString(tmp.resolve("resources/models/argos/packages/translate-es_en-1_9/bpe.model"), "version: 0.2\nx\n");

        String prev = System.getProperty("gek.projectRoot");
        System.setProperty("gek.projectRoot", tmp.toString());
        try {
            AppPaths p = AppPaths.resolve();
            assertTrue(NativeRuntimeProbe.findArgosPackage(p, "es", "en").isEmpty(),
                    "BPE-only package without sentencepiece/source.spm should not be treated as usable");

            NativeTranslationEngine tr = new NativeTranslationEngine(p);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> tr.translate("hola", "es", "en").get(5, TimeUnit.SECONDS));
            String msg = root(ex).toLowerCase();
            assertTrue(msg.contains("no local argos package") || msg.contains("not ready") || msg.contains("unavailable"),
                    "unexpected: " + root(ex));
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void translationRequiresExplicitSourceLanguage() {
        AppPaths paths = AppPaths.resolve();
        NativeTranslationEngine tr = new NativeTranslationEngine(paths);
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> tr.translate("hi", "auto", "es").get(5, TimeUnit.SECONDS));
        assertTrue(root(ex).toLowerCase().contains("explicit source language"),
                "unexpected: " + root(ex));
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c != null && c.getCause() != null) c = c.getCause();
        return c == null ? "" : String.valueOf(c.getMessage());
    }

    private static void writeSilentWav(Path path, int sampleRate, double seconds) throws Exception {
        int n = (int) (sampleRate * seconds);
        try (var out = Files.newOutputStream(path); var dos = new java.io.DataOutputStream(out)) {
            int dataBytes = n * 2;
            dos.writeBytes("RIFF");
            writeLE32(dos, 36 + dataBytes);
            dos.writeBytes("WAVEfmt ");
            writeLE32(dos, 16);
            writeLE16(dos, 1);  // PCM
            writeLE16(dos, 1);  // mono
            writeLE32(dos, sampleRate);
            writeLE32(dos, sampleRate * 2);
            writeLE16(dos, 2);
            writeLE16(dos, 16);
            dos.writeBytes("data");
            writeLE32(dos, dataBytes);
            for (int i = 0; i < n; i++) writeLE16(dos, 0);
            dos.flush();
        }
    }
    private static void writeLE16(java.io.DataOutputStream dos, int v) throws Exception { dos.write(v & 0xff); dos.write((v >> 8) & 0xff); }
    private static void writeLE32(java.io.DataOutputStream dos, int v) throws Exception { dos.write(v & 0xff); dos.write((v >> 8) & 0xff); dos.write((v >> 16) & 0xff); dos.write((v >> 24) & 0xff); }
}
