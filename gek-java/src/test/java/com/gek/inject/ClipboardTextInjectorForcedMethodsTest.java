package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardTextInjectorForcedMethodsTest {

    private static void setMethod(ClipboardTextInjector inj, String method) throws Exception {
        Field f = ClipboardTextInjector.class.getDeclaredField("method");
        f.setAccessible(true);
        f.set(inj, method);
    }

    @Test void forcedInjectBranches() throws Exception {
        ClipboardTextInjector inj = new ClipboardTextInjector();
        assertFalse(inj.inject(null));
        assertFalse(inj.inject(""));

        setMethod(inj, "none");
        assertFalse(inj.inject("text"));

        setMethod(inj, "unknown-method");
        assertFalse(inj.inject("text"));

        if (!PlatformSupport.isWindows()) {
            // windows-robot-clipboard path: will attempt robot/clipboard, fail in headless but exercise branch
            setMethod(inj, "windows-robot-clipboard");
            assertFalse(inj.inject("text"));

            // awt-clipboard needs headful toolkit; in headless getSystemClipboard throws -> caught -> false
            setMethod(inj, "awt-clipboard");
            assertDoesNotThrow(() -> inj.inject("text"));
        }

        // linux clipboard-only branches try to run missing commands -> exception -> false
        setMethod(inj, "wl-copy+clipboard-only");
        assertDoesNotThrow(() -> inj.inject("text"));
        setMethod(inj, "xclip+clipboard-only");
        assertDoesNotThrow(() -> inj.inject("text"));
        setMethod(inj, "wl-copy+ydotool");
        assertDoesNotThrow(() -> inj.inject("text"));
        setMethod(inj, "xclip+xdotool");
        assertDoesNotThrow(() -> inj.inject("text"));
    }
}
