package com.gek.inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowsGlobalHotkeyParseTest {
    @Test
    void parsesCombo() {
        WindowsGlobalHotkey.HotkeySpec s = WindowsGlobalHotkey.parse("CTRL+SHIFT+SPACE");
        assertEquals(WindowsGlobalHotkey.MOD_CONTROL | WindowsGlobalHotkey.MOD_SHIFT, s.modifiers());
        assertEquals(java.awt.event.KeyEvent.VK_SPACE, s.vk());
    }
}
