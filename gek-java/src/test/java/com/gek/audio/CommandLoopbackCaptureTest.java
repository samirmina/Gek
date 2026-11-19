package com.gek.audio;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandLoopbackCaptureTest {
    @Test
    void backendNameMatchesBuildCommand() {
        List<String> cmd = CommandLoopbackCapture.buildBaseCommand(16000, null);
        String name = CommandLoopbackCapture.backendName();
        assertNotNull(name);
        assertFalse(name.isBlank());
        if (cmd == null || cmd.isEmpty()) {
            assertEquals("none", name);
            assertFalse(CommandLoopbackCapture.isSupported());
        } else {
            assertEquals(cmd.get(0).contains("/") ? cmd.get(0).substring(cmd.get(0).lastIndexOf('/') + 1) : cmd.get(0), name);
            assertTrue(CommandLoopbackCapture.isSupported());
        }
    }

    @Test
    void buildCommandContainsSampleRateOrStdoutWhenPresent() {
        List<String> c = CommandLoopbackCapture.buildBaseCommand(16000, null);
        if (c != null) {
            assertFalse(c.isEmpty());
            assertTrue(c.stream().anyMatch(s -> s.contains("16000") || s.equals("-")));
            assertNotNull(CommandLoopbackCapture.backendName());
        }
    }

    @Test
    void windowsRequiresExplicitLoopbackDevice() {
        if (!PlatformSupport.isWindows()) return;
        assertNull(CommandLoopbackCapture.buildBaseCommand(16000, null),
                "Windows loopback should not guess default monitor");
        assertNull(CommandLoopbackCapture.buildBaseCommand(16000, "@DEFAULT_MONITOR@"));
        List<String> dshow = CommandLoopbackCapture.buildBaseCommand(16000, "Stereo Mix (Realtek)");
        List<String> wasapi = CommandLoopbackCapture.buildBaseCommand(16000, "wasapi:default");
        if (PlatformSupport.commandExists("ffmpeg")) {
            assertNotNull(dshow);
            assertTrue(dshow.contains("dshow"));
            assertNotNull(wasapi);
            assertTrue(wasapi.contains("wasapi"));
        } else {
            assertNull(dshow, "ffmpeg unavailable: dshow command cannot be built");
            assertNull(wasapi, "ffmpeg unavailable: wasapi command cannot be built");
        }
    }
}
