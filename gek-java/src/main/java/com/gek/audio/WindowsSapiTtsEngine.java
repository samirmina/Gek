package com.gek.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Windows TTS implementation using System.Speech via PowerShell.
 * Best-effort: requires Windows PowerShell and .NET speech assemblies available on the host.
 */
public class WindowsSapiTtsEngine implements TtsEngine {
    private static final Logger log = LoggerFactory.getLogger(WindowsSapiTtsEngine.class);
    private final Object lock = new Object();
    private Process current;

    @Override
    public void speak(String text, String lang) throws Exception {
        if (text == null || text.isBlank()) return;
        if (!isSupported()) {
            log.info("Windows TTS not supported on this platform/runtime.");
            return;
        }
        stop();
        // Encode text to avoid shell quoting issues; decode inside PowerShell.
        String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String script = String.join("; ",
                "Add-Type -AssemblyName System.Speech",
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
                "try { $txt = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + b64 + "'))",
                "$s.Speak($txt) } finally { $s.Dispose() }"
        );
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-WindowStyle");
        cmd.add("Hidden");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-Command");
        cmd.add(script);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        synchronized (lock) { current = p; }
        // Drain output asynchronously to avoid blocking if PowerShell writes.
        Thread drainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.debug("sapi: {}", line);
                }
            } catch (Exception ignored) {}
        }, "gek-sapi-drain");
        drainer.setDaemon(true);
        drainer.start();
        // Do not block UI/caller for long speeches; caller can invoke stop().
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                log.debug("Windows TTS speaking asynchronously (pid={})", p.pid());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        Process p;
        synchronized (lock) { p = current; current = null; }
        if (p == null) return;
        try {
            if (p.isAlive()) p.destroy();
            if (p.isAlive()) p.destroyForcibly();
        } catch (Exception ignored) {}
    }

    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
