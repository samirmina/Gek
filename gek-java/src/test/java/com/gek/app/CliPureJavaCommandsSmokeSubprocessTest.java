package com.gek.app;

import com.gek.audio.WavWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expands CLI smoke coverage for pure-Java/headless-safe commands and controlled argument failures.
 * Runs in subprocesses to isolate System.exit behavior.
 */
class CliPureJavaCommandsSmokeSubprocessTest {
    @TempDir Path tmp;

    private Result runMain(Path root, String... args) throws Exception {
        Files.createDirectories(root.resolve("resources/data/synonyms"));
        Files.createDirectories(root.resolve("resources/models/whisper"));
        Files.createDirectories(root.resolve("resources/models/argos/packages"));
        Files.createDirectories(root.resolve("resources/models/pyannote"));
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dgek.projectRoot=" + root.toAbsolutePath().normalize());
        cmd.add("com.gek.app.Main");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (out.length() < 30000) out.append(line).append('\n');
            }
        }
        boolean finished = p.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            fail("process timed out: " + String.join(" ", cmd) + "\n" + out);
        }
        return new Result(p.exitValue(), out.toString());
    }

    record Result(int code, String out) {}

    @Test
    void segmentFileAndApplySynonymsAndListAudioDevices() throws Exception {
        Path root = tmp.resolve("pure");
        Files.createDirectories(root.resolve("resources/data/synonyms"));
        Files.writeString(root.resolve("resources/data/synonyms/en.json"), "{\"gonna\":\"going to\"}");

        Path wav = root.resolve("speech.wav");
        float[] speech = sine(4800, 220, 0.3);
        float[] silence = new float[8000];
        float[] all = concat(speech, silence);
        WavWriter.writeFloatMono16k(wav, all);

        Result seg = runMain(root, "--segment-file", "--input", wav.toString());
        assertEquals(0, seg.code(), seg.out);
        assertTrue(seg.out.contains("[OK] segment-file"), seg.out);
        assertTrue(seg.out.contains("segments="), seg.out);

        Result syn = runMain(root, "--apply-synonyms", "--text", "I am gonna go", "--lang", "en");
        assertEquals(0, syn.code(), syn.out);
        assertTrue(syn.out.contains("going to") || syn.out.contains("[OK] apply-synonyms"), syn.out);

        Result dev = runMain(root, "--list-audio-devices");
        assertEquals(0, dev.code(), dev.out);
        assertTrue(dev.out.contains("audio input devices") || dev.out.contains("[OK]"), dev.out);
    }

    @Test
    void controlledFailuresAreReportedCleanly() throws Exception {
        Path root = tmp.resolve("fail");

        // missing required args -> CliRunner catches and exits 1 with [FAIL]
        Result hs = runMain(root, "--history-search");
        assertNotEquals(0, hs.code(), "expected failure without --query: " + hs.out);
        assertTrue(hs.out.contains("[FAIL]") || hs.out.toLowerCase().contains("query"), hs.out);

        Result tr = runMain(root, "--translate-text");
        assertNotEquals(0, tr.code(), "expected failure without --text: " + tr.out);
        assertTrue(tr.out.contains("[FAIL]") || tr.out.toLowerCase().contains("text"), tr.out);

        Result tf = runMain(root, "--transcribe-file");
        assertNotEquals(0, tf.code(), "expected failure without --input: " + tf.out);
        assertTrue(tf.out.contains("[FAIL]") || tf.out.toLowerCase().contains("input"), tf.out);

        Result sf = runMain(root, "--segment-file");
        assertNotEquals(0, sf.code(), "expected failure without --input: " + sf.out);
        assertTrue(sf.out.contains("[FAIL]") || sf.out.toLowerCase().contains("input"), sf.out);

        Result syn = runMain(root, "--apply-synonyms", "--text", "hi");
        assertNotEquals(0, syn.code(), "expected failure without --lang: " + syn.out);
        assertTrue(syn.out.contains("[FAIL]") || syn.out.toLowerCase().contains("lang"), syn.out);
    }

    private static float[] sine(int n, double hz, double amp) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 16000.0));
        return a;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] r = new float[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
