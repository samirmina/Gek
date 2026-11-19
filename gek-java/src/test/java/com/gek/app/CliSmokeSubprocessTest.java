package com.gek.app;

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
 * Headless-safe CLI smoke tests (broadens narrow smoke): exercises Main entry/wiring and CLI
 * reporting paths in subprocesses so System.exit behavior cannot affect the test JVM.
 */
class CliSmokeSubprocessTest {
    @TempDir Path tmp;

    private Result runMain(Path root, String... args) throws Exception {
        Files.createDirectories(root.resolve("resources/data"));
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
    void helpExitsZeroAndShowsUsage() throws Exception {
        Result r = runMain(tmp.resolve("help"), "--help");
        assertEquals(0, r.code(), r.out);
        String o = r.out;
        assertTrue(o.contains("Usage") || o.contains("--help"), o);
        assertTrue(o.contains("--self-test") || o.contains("Options"), o);
    }

    @Test
    void platformInfoModelCatalogAndNativeInfoSmoke() throws Exception {
        Result p = runMain(tmp.resolve("pinfo"), "--platform-info");
        assertEquals(0, p.code(), p.out);
        assertTrue(p.out.contains("\"os\"") && p.out.contains("headlessJvm"), p.out);

        Result m = runMain(tmp.resolve("mcatalog"), "--model-catalog");
        assertEquals(0, m.code(), m.out);
        assertTrue(m.out.contains("Model catalog") || m.out.toLowerCase().contains("whisper"), m.out);

        Result n = runMain(tmp.resolve("ninfo"), "--native-info");
        assertEquals(0, n.code(), n.out);
        String lo = n.out.toLowerCase();
        assertTrue(lo.contains("inference") || lo.contains("mode") || lo.contains("native"), n.out);
    }

    @Test
    void diagnosticsAndHistorySmoke() throws Exception {
        Result i = runMain(tmp.resolve("icheck"), "--inference-check");
        assertEquals(0, i.code(), i.out);
        assertTrue(i.out.contains("inference-check completed") || i.out.toLowerCase().contains("readiness"), i.out);

        Result w = runMain(tmp.resolve("wready"), "--wsl2-readiness");
        assertEquals(0, w.code(), w.out);
        assertTrue(w.out.contains("platform readiness completed") || w.out.toLowerCase().contains("readiness"), w.out);

        Result h = runMain(tmp.resolve("hist"), "--history-recent");
        assertEquals(0, h.code(), h.out);
        assertTrue(h.out.contains("history rows=0") || h.out.contains("[OK]"), h.out);
    }
}
