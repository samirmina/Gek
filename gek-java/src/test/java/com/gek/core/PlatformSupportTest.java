package com.gek.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformSupportTest {
    @Test
    void capabilitiesAreConsistent() {
        PlatformSupport.Capabilities c = PlatformSupport.capabilities();
        if (c.windows()) {
            assertTrue(c.windowsGlobalHotkey(), "Windows global hotkey integration should be wired on Windows");
            assertTrue(c.windowsCaretInjection(), "Windows caret injection integration should be wired on Windows");
            assertTrue(c.windowsSapiTts(), "Windows TTS integration should be wired on Windows");
            assertEquals(PlatformSupport.commandExists("ffmpeg"), c.windowsWasapiLoopback(),
                    "Windows loopback capability should reflect ffmpeg availability (best-effort path)");
            assertFalse(c.windowsCoreAudioDucking(), "Core Audio per-app ducking placeholder must not be reported as active");
        }
        if (c.linux()) {
            assertFalse(c.windowsWasapiLoopback());
            assertFalse(c.windowsCoreAudioDucking());
            assertFalse(c.windowsGlobalHotkey());
            assertFalse(c.windowsCaretInjection());
            assertFalse(c.windowsSapiTts());
        }
        assertEquals(PlatformSupport.isWindows(), c.windows());
        assertEquals(PlatformSupport.isLinux(), c.linux());
        assertEquals(PlatformSupport.isWsl(), c.wsl());
        assertEquals(PlatformSupport.hasWslg(), c.wslg());
        assertNotNull(Boolean.valueOf(c.gui()));
    }

    @Test
    void commandExistsHandlesEdgeCasesAndKnownCommands() {
        assertFalse(PlatformSupport.commandExists(null));
        assertFalse(PlatformSupport.commandExists(""));
        assertFalse(PlatformSupport.commandExists("   "));
        assertFalse(PlatformSupport.commandExists("definitely-not-a-real-command-gek-xyz-123"));

        if (PlatformSupport.isLinux()) {
            assertTrue(PlatformSupport.commandExists("sh"), "sh should be available on Linux");
        }
        if (PlatformSupport.isWindows()) {
            assertTrue(PlatformSupport.commandExists("cmd"), "cmd should be available on Windows");
        }
        assertTrue(PlatformSupport.commandExists("java"), "java should be on PATH in test environment");
    }

    @Test
    void basicFlagsAndGuiConsistentWithEnvironment() {
        boolean win = PlatformSupport.isWindows();
        boolean lin = PlatformSupport.isLinux();
        assertEquals(win, !lin && System.getProperty("os.name", "").toLowerCase().contains("win") || win);
        // isWsl/hasWslg are only meaningful on Linux; on Windows they must be false
        if (win) {
            assertFalse(PlatformSupport.isWsl());
            assertFalse(PlatformSupport.hasWslg());
            assertTrue(PlatformSupport.guiAvailable(), "Windows host should report GUI available");
        }
        // guiAvailable should not throw and be boolean-consistent
        boolean gui = PlatformSupport.guiAvailable();
        PlatformSupport.Capabilities c = PlatformSupport.capabilities();
        assertEquals(gui, c.gui());
        assertEquals(PlatformSupport.isWsl(), c.wsl());
        assertEquals(PlatformSupport.hasWslg(), c.wslg());
    }
}
