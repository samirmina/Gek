package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SidecarProtocolSmokeTest {

    @Test
    void pingAndGracefulErrorsWithoutMlDeps() throws Exception {
        // Requires a usable python3 and bridge script in repo; verifiable in environments with Python installed.
        String py = "python3";
        boolean pythonUsable = false;
        try {
            Process p = new ProcessBuilder(py, "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            pythonUsable = finished && p.exitValue() == 0 && out.toLowerCase().contains("python");
        } catch (Exception ignored) {
            pythonUsable = false;
        }
        Assumptions.assumeTrue(pythonUsable, "Skipping: usable python3 runtime not available (required for sidecar smoke test)");

        AppPaths paths = AppPaths.resolve();
        Path script = paths.projectRoot().resolve("gek-java/bridge/inference_server.py");
        Assumptions.assumeTrue(Files.exists(script), "Skipping: sidecar bridge script not found: " + script);

        AppSettings s = AppSettings.defaults();
        s.sidecar_python = py;
        s.whisper_device = "cpu";
        s.whisper_compute_type = "int8";
        s.whisper_model = "small";

        try (SidecarClient client = new SidecarClient(paths, s)) {
            assertTrue(client.ping().join(), "sidecar ping should succeed");
            assertTrue(client.isAlive(), "sidecar should be alive after ping");
            assertNotNull(client.resolvedPython());
            assertTrue(Files.exists(client.resolvedScript()), "bridge script should exist");

            // Warmup returns structured result even when ML deps are missing/unavailable
            com.fasterxml.jackson.databind.JsonNode warm = client.warmupDetailed().get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(warm);
            assertTrue(warm.has("whisper") || warm.has("errors"), "warmup result should be structured");

            // Transcribe without usable loaded model -> protocol-level error expected
            Path tmp = Files.createTempFile("gek-smoke", ".wav");
            try {
                Exception ex = assertThrows(Exception.class, () -> client.transcribe(tmp, null).join());
                String msg = root(ex);
                assertNotNull(msg);
                assertTrue(msg.toLowerCase().contains("whisper") || msg.toLowerCase().contains("model") || msg.toLowerCase().contains("load"),
                        "expected whisper/model/load related error, got: " + msg);
            } finally { Files.deleteIfExists(tmp); }

            // translate without argos ready may fail gracefully; just ensure call completes exceptionally or returns
            try {
                String out = client.translate("hello", "en", "ar").get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(out);
            } catch (Exception ignored) {
                // acceptable when argos deps/packages not loaded in sidecar env
            }

            assertTrue(client.ping().join(), "sidecar should remain responsive after errors");
        }
    }

    private static String root(Throwable t) {
        Throwable c = t; while (c != null && c.getCause() != null) c = c.getCause();
        return c == null ? "" : (c.getMessage() == null ? c.toString() : c.getMessage());
    }
}
