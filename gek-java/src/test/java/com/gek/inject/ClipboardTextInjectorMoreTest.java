package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardTextInjectorMoreTest {

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ClipboardTextInjector.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test void nullEmptyAndFactory() {
        ClipboardTextInjector inj = new ClipboardTextInjector();
        assertNotNull(inj.name());
        assertFalse(inj.inject(null));
        assertFalse(inj.inject(""));
        TextInjector p = ClipboardTextInjector.forPlatform();
        assertNotNull(p);
    }

    @Test void detectMethodReflectsNoTools() throws Exception {
        Method detect = ClipboardTextInjector.class.getDeclaredMethod("detectMethod");
        detect.setAccessible(true);
        String m = (String) detect.invoke(null);
        if (PlatformSupport.isLinux()) {
            // in this env none of wl-copy/xclip/ydotool/xdotool installed
            boolean hasAny = PlatformSupport.commandExists("wl-copy") || PlatformSupport.commandExists("xclip");
            if (!hasAny) {
                assertEquals(java.awt.GraphicsEnvironment.isHeadless() ? "none" : "awt-clipboard", m);
            }
        }
    }

    @Test void copyLinuxWritesStdinToFile() throws Exception {
        if (PlatformSupport.isWindows()) return;
        ClipboardTextInjector inj = new ClipboardTextInjector();
        Path out = Files.createTempFile("cliptest", ".txt");
        try {
            // sh -c 'cat > file' reads stdin
            invoke(inj, "copyLinux", new Class[]{String.class, List.class}, "hello-copy",
                    List.of("sh", "-c", "cat > " + out.toString()));
            assertEquals("hello-copy", Files.readString(out));
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test void pasteLinuxCopiesThenPastes() throws Exception {
        if (PlatformSupport.isWindows()) return;
        ClipboardTextInjector inj = new ClipboardTextInjector();
        Path copied = Files.createTempFile("clipcopy", ".txt");
        Path pasted = Files.createTempFile("clippaste", ".txt");
        try {
            boolean ok = (Boolean) invoke(inj, "pasteLinux", new Class[]{String.class, List.class, List.class},
                    "payload",
                    List.of("sh", "-c", "cat > " + copied.toString()),
                    List.of("sh", "-c", "echo pasted > " + pasted.toString()));
            assertTrue(ok);
            assertEquals("payload", Files.readString(copied));
            assertEquals("pasted\n", Files.readString(pasted));
        } finally {
            Files.deleteIfExists(copied);
            Files.deleteIfExists(pasted);
        }
    }

    @Test void pasteLinuxReturnsFalseOnPasteFailure() throws Exception {
        if (PlatformSupport.isWindows()) return;
        ClipboardTextInjector inj = new ClipboardTextInjector();
        Path copied = Files.createTempFile("clipcopy2", ".txt");
        try {
            boolean ok = (Boolean) invoke(inj, "pasteLinux", new Class[]{String.class, List.class, List.class},
                    "x",
                    List.of("sh", "-c", "cat > " + copied.toString()),
                    List.of("sh", "-c", "exit 7"));
            assertFalse(ok);
        } finally {
            Files.deleteIfExists(copied);
        }
    }
}
