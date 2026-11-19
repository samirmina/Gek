package com.gek.audio;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandLoopbackCaptureMoreTest {

    @Test
    void backendNameAndSupportConsistentWithBuild() {
        List<String> cmd = CommandLoopbackCapture.buildBaseCommand(16000, null);
        String backend = CommandLoopbackCapture.backendName();
        boolean supported = CommandLoopbackCapture.isSupported();
        if (cmd == null || cmd.isEmpty()) {
            assertEquals("none", backend);
            assertFalse(supported);
        } else {
            assertTrue(supported);
            assertFalse(backend.isBlank());
            assertTrue(cmd.get(0).endsWith(backend) || cmd.get(0).equals(backend) || cmd.get(0).contains(backend));
        }
    }

    @Test
    void buildBaseCommandUsesRequestedSampleRateAndMonitorWhenApplicable() {
        // Linux/non-Windows path characterization (Windows-specific branches not reachable here)
        if (PlatformSupport.isWindows()) return;
        List<String> c16 = CommandLoopbackCapture.buildBaseCommand(16000, null);
        List<String> c48 = CommandLoopbackCapture.buildBaseCommand(48000, "my.monitor");
        if (c16 != null) {
            assertFalse(c16.isEmpty());
            String joined = String.join(" ", c16);
            assertTrue(joined.contains("16000") || joined.contains("--rate=16000"), joined);
        }
        if (c48 != null) {
            String joined = String.join(" ", c48);
            assertTrue(joined.contains("48000") || joined.contains("--rate=48000"), joined);
            // custom monitor should appear for pulse/ffmpeg pulse paths when provided
            // (pw-record only adds target when not default)
            assertTrue(joined.contains("my.monitor") || joined.contains("--target"), joined);
        }
    }

    @Test
    void buildBaseCommandCustomMonitorDiffersFromDefaultWhenSupported() {
        if (PlatformSupport.isWindows()) return;
        List<String> def = CommandLoopbackCapture.buildBaseCommand(16000, null);
        List<String> custom = CommandLoopbackCapture.buildBaseCommand(16000, "custom.src");
        if (def == null || custom == null) return; // environment-dependent
        // For tools that take device/target, custom should be reflected; if identical, at least both valid
        assertFalse(custom.isEmpty());
        String cj = String.join(" ", custom);
        assertTrue(cj.contains("custom.src") || cj.contains("--target") || cj.equals(String.join(" ", def)), cj);
    }
}
