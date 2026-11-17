package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinuxGlobalHotkeyTest {
    @Test
    void supportedOnLinux() {
        if (PlatformSupport.isLinux()) {
            assertTrue(LinuxGlobalHotkey.availableOnPlatform());
            LinuxGlobalHotkey h = new LinuxGlobalHotkey(() -> {});
            assertDoesNotThrow(h::start);
            assertDoesNotThrow(h::close);
        }
    }
}
