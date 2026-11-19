package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardTextInjectorCharacterizationTest {
    @Test
    void nameIsConsistentWithPlatformDetection() {
        if (PlatformSupport.isWindows()) return; // Windows-specific path needs Windows env
        ClipboardTextInjector inj = new ClipboardTextInjector();
        String name = inj.name();
        assertNotNull(name);
        assertFalse(name.isBlank());
        boolean wlCopy = PlatformSupport.commandExists("wl-copy");
        boolean xclip = PlatformSupport.commandExists("xclip");
        boolean ydotool = PlatformSupport.commandExists("ydotool");
        boolean xdotool = PlatformSupport.commandExists("xdotool");
        if (wlCopy && ydotool) assertEquals("wl-copy+ydotool", name);
        else if (xclip && xdotool) assertEquals("xclip+xdotool", name);
        else if (wlCopy) assertEquals("wl-copy+clipboard-only", name);
        else if (xclip) assertEquals("xclip+clipboard-only", name);
        else assertTrue(name.equals("awt-clipboard") || name.equals("none"), name);
    }

    @Test
    void injectEmptyOrNullReturnsFalse() {
        ClipboardTextInjector inj = new ClipboardTextInjector();
        assertFalse(inj.inject(null));
        assertFalse(inj.inject(""));
    }
}
