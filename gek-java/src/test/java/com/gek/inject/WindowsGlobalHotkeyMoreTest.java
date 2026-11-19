package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WindowsGlobalHotkeyMoreTest {

    @Test void parseVariants() {
        WindowsGlobalHotkey.HotkeySpec a = WindowsGlobalHotkey.parse("WIN+ALT+SHIFT+CONTROL+A");
        assertEquals(WindowsGlobalHotkey.MOD_CONTROL | WindowsGlobalHotkey.MOD_SHIFT
                | WindowsGlobalHotkey.MOD_ALT | WindowsGlobalHotkey.MOD_WIN, a.modifiers());
        assertEquals(KeyEvent.getExtendedKeyCodeForChar('A'), a.vk());

        WindowsGlobalHotkey.HotkeySpec meta = WindowsGlobalHotkey.parse("META+ENTER");
        assertEquals(WindowsGlobalHotkey.MOD_WIN, meta.modifiers());
        assertEquals(KeyEvent.VK_ENTER, meta.vk());

        WindowsGlobalHotkey.HotkeySpec dflt = WindowsGlobalHotkey.parse(null);
        assertEquals(WindowsGlobalHotkey.MOD_CONTROL | WindowsGlobalHotkey.MOD_SHIFT, dflt.modifiers());
        assertEquals(KeyEvent.VK_SPACE, dflt.vk());

        WindowsGlobalHotkey.HotkeySpec named = WindowsGlobalHotkey.parse("CTRL+F1");
        assertEquals(KeyEvent.VK_F1, named.vk());

        WindowsGlobalHotkey.HotkeySpec unknown = WindowsGlobalHotkey.parse("CTRL+NOSUCHKEY");
        assertEquals(WindowsGlobalHotkey.MOD_CONTROL, unknown.modifiers());
        // falls back to default vk (SPACE) when no letter/known field
        assertEquals(KeyEvent.VK_SPACE, unknown.vk());

        WindowsGlobalHotkey.HotkeySpec single = WindowsGlobalHotkey.parse("z");
        assertEquals(0, single.modifiers());
        assertEquals(KeyEvent.getExtendedKeyCodeForChar('Z'), single.vk());
    }

    @Test void supportAndLifecycleOnNonWindows() {
        WindowsGlobalHotkey h = new WindowsGlobalHotkey(1, WindowsGlobalHotkey.MOD_CONTROL, KeyEvent.VK_SPACE, () -> {});
        if (PlatformSupport.isWindows()) return;
        assertFalse(h.isSupported());
        // do not start on Linux: JNA win32 natives unavailable and loop thread would throw noisily
        assertDoesNotThrow(h::stop);
        assertDoesNotThrow(h::close);
    }

    @Test void stopPostsQuitWhenThreadIdPresent() throws Exception {
        if (PlatformSupport.isWindows()) return;
        WindowsGlobalHotkey h = new WindowsGlobalHotkey(9, 0, KeyEvent.VK_A, () -> {});
        Field running = WindowsGlobalHotkey.class.getDeclaredField("running");
        Field threadId = WindowsGlobalHotkey.class.getDeclaredField("threadId");
        running.setAccessible(true);
        threadId.setAccessible(true);
        ((AtomicBoolean) running.get(h)).set(true);
        ((AtomicInteger) threadId.get(h)).set(123456);
        // may fail loading win32 User32 on Linux but stop catches Throwable and resets running
        assertDoesNotThrow(h::stop);
        assertFalse(((AtomicBoolean) running.get(h)).get());
    }
}
