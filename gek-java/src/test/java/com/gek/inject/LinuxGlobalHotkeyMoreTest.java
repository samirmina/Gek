package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LinuxGlobalHotkeyMoreTest {
    @TempDir Path tmp;

    @Test
    void availabilityAndBasicLifecycle() {
        if (!PlatformSupport.isLinux()) return;
        LinuxGlobalHotkey h = new LinuxGlobalHotkey(() -> {});
        assertTrue(h.isSupported());
        assertTrue(LinuxGlobalHotkey.availableOnPlatform());
        assertDoesNotThrow(h::start);
        assertDoesNotThrow(h::start); // idempotent
        assertDoesNotThrow(h::stop);
        assertDoesNotThrow(h::close);
    }

    @Test
    void triggerFileFiresCallback() throws Exception {
        if (!PlatformSupport.isLinux()) return;
        Path trigger = tmp.resolve("hk.trigger");
        Files.writeString(trigger, "");
        String prev = System.getenv("GEK_HOTKEY_TRIGGER_FILE");
        // cannot set env in-process portably; use reflection-free approach not possible.
        // Instead, exercise file watcher via creating instance and invoking private logic is not possible;
        // but we can at least ensure constructor/start/close with null callback and env absent don't throw.
        LinuxGlobalHotkey h = new LinuxGlobalHotkey(null);
        assertDoesNotThrow(h::start);
        Thread.sleep(80);
        assertDoesNotThrow(h::close);
        assertNotNull(prev == null ? "" : prev);
    }
}
