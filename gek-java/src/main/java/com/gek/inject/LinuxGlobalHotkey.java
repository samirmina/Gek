package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort Linux "hotkey" fallback: listens for a trigger line on stdin ("toggle") or
 * watches a trigger file if GEK_HOTKEY_TRIGGER_FILE is set (touch/append to trigger).
 * True desktop-global Wayland hotkeys generally require compositor integration; this provides
 * a WSL2-feasible automation hook without native grab libraries.
 */
public class LinuxGlobalHotkey implements GlobalHotkey {
    private static final Logger log = LoggerFactory.getLogger(LinuxGlobalHotkey.class);
    private final Runnable onTrigger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public LinuxGlobalHotkey(Runnable onTrigger) { this.onTrigger = onTrigger; }

    public static boolean availableOnPlatform() {
        // Always available as stdin/file trigger hook; report as limited support
        return PlatformSupport.isLinux();
    }
    @Override public boolean isSupported() { return availableOnPlatform(); }

    @Override public void start() {
        if (running.getAndSet(true)) return;
        String triggerFile = System.getenv("GEK_HOTKEY_TRIGGER_FILE");
        if (triggerFile != null && !triggerFile.isBlank()) {
            thread = new Thread(() -> watchFile(triggerFile), "gek-hotkey-file");
        } else {
            // stdin hook (non-blocking in typical GUI run; useful for scripted control)
            thread = new Thread(this::watchStdin, "gek-hotkey-stdin");
        }
        thread.setDaemon(true);
        thread.start();
        log.info("Linux hotkey fallback started (trigger via {} )", triggerFile != null ? "file:"+triggerFile : "stdin 'toggle' or env GEK_HOTKEY_TRIGGER_FILE");
    }

    private void watchStdin() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (running.get() && (line = br.readLine()) != null) {
                String t = line.trim().toLowerCase();
                if (t.equals("toggle") || t.equals("hotkey") || t.equals("dictate")) fire();
            }
        } catch (Exception ignored) {}
    }

    private void watchFile(String path) {
        java.nio.file.Path p = java.nio.file.Path.of(path);
        try {
            java.nio.file.Files.createDirectories(p.getParent() == null ? java.nio.file.Path.of(".") : p.getParent());
            if (!java.nio.file.Files.exists(p)) java.nio.file.Files.createFile(p);
            long last = java.nio.file.Files.getLastModifiedTime(p).toMillis();
            while (running.get()) {
                Thread.sleep(400);
                if (!java.nio.file.Files.exists(p)) continue;
                long cur = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                long size = java.nio.file.Files.size(p);
                if (cur != last || size > 0) {
                    last = cur;
                    try { if (size > 0) java.nio.file.Files.writeString(p, ""); } catch (Exception ignored) {}
                    fire();
                }
            }
        } catch (Exception ignored) {}
    }

    private void fire() { try { if (onTrigger != null) onTrigger.run(); } catch (Exception e) { log.warn("hotkey trigger failed: {}", e.getMessage()); } }

    @Override public void stop() { running.set(false); }
    @Override public void close() { stop(); if (thread != null) { try { thread.interrupt(); } catch (Exception ignored) {} } }
}
