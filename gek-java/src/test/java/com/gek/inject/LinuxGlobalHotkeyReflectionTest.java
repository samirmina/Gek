package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LinuxGlobalHotkeyReflectionTest {
    @TempDir Path tmp;

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = LinuxGlobalHotkey.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test void fireRunsCallbackAndSwallowsExceptions() throws Exception {
        if (!PlatformSupport.isLinux()) return;
        AtomicInteger n = new AtomicInteger(0);
        LinuxGlobalHotkey h = new LinuxGlobalHotkey(n::incrementAndGet);
        invoke(h, "fire", new Class[]{});
        assertEquals(1, n.get());

        LinuxGlobalHotkey h2 = new LinuxGlobalHotkey(null);
        assertDoesNotThrow(() -> invoke(h2, "fire", new Class[]{}));

        LinuxGlobalHotkey h3 = new LinuxGlobalHotkey(() -> { throw new RuntimeException("boom"); });
        assertDoesNotThrow(() -> invoke(h3, "fire", new Class[]{}));
    }

    @Test void watchFileTriggersOnModificationAndStops() throws Exception {
        if (!PlatformSupport.isLinux()) return;
        Path f = tmp.resolve("trigger.txt");
        Files.writeString(f, "");
        CountDownLatch latch = new CountDownLatch(1);
        LinuxGlobalHotkey h = new LinuxGlobalHotkey(latch::countDown);
        Field running = LinuxGlobalHotkey.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(h)).set(true);

        Thread t = new Thread(() -> {
            try { invoke(h, "watchFile", new Class[]{String.class}, f.toString()); } catch (Exception ignored) {}
        }, "test-watch");
        t.setDaemon(true);
        t.start();
        Thread.sleep(500);
        // modify file to trigger
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertTrue(latch.await(4, TimeUnit.SECONDS));
        ((AtomicBoolean) running.get(h)).set(false);
        t.interrupt();
        t.join(1500);
    }

    @Test void watchFileTriggersOnContentAndClears() throws Exception {
        if (!PlatformSupport.isLinux()) return;
        Path f = tmp.resolve("trigger2.txt");
        Files.writeString(f, "");
        CountDownLatch latch = new CountDownLatch(1);
        LinuxGlobalHotkey h = new LinuxGlobalHotkey(latch::countDown);
        Field running = LinuxGlobalHotkey.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(h)).set(true);

        Thread t = new Thread(() -> {
            try { invoke(h, "watchFile", new Class[]{String.class}, f.toString()); } catch (Exception ignored) {}
        }, "test-watch2");
        t.setDaemon(true);
        t.start();
        Thread.sleep(500);
        Files.writeString(f, "x");
        assertTrue(latch.await(4, TimeUnit.SECONDS));
        ((AtomicBoolean) running.get(h)).set(false);
        t.interrupt();
        t.join(1500);
    }

    @Test void startStopCloseWithDefaultStdinPath() throws Exception {
        if (!PlatformSupport.isLinux()) return;
        // Ensure env var not set (cannot unset, but usually absent). Start uses stdin watcher thread.
        LinuxGlobalHotkey h = new LinuxGlobalHotkey(() -> {});
        h.start();
        Thread.sleep(80);
        h.stop();
        h.close();
    }
}
