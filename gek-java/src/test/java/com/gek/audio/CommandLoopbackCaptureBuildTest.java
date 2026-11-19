package com.gek.audio;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandLoopbackCaptureBuildTest {

    @Test
    void backendAndSupportConsistency() {
        boolean supported = CommandLoopbackCapture.isSupported();
        String backend = CommandLoopbackCapture.backendName();
        List<String> cmd = CommandLoopbackCapture.buildBaseCommand(16000, null);
        if (supported) {
            assertNotNull(cmd);
            assertFalse(cmd.isEmpty());
            assertNotEquals("none", backend);
            assertTrue(cmd.get(0).contains(backend) || cmd.get(0).endsWith(backend));
        } else {
            assertEquals("none", backend);
        }
    }

    @Test
    void buildBaseCommandRespectsMonitorOnLinuxTools() {
        if (PlatformSupport.isWindows()) return;
        // On linux, if pw-record/parec/parecord/ffmpeg available, custom monitor should appear or be wired
        List<String> cmd = CommandLoopbackCapture.buildBaseCommand(22050, "my.monitor");
        if (cmd == null) return; // environment without tools
        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("22050") || joined.contains("--rate=22050"));
        // for pulse tools/ffmpeg we pass device; for pw-record default monitor handling differs
        assertTrue(joined.contains("my.monitor") || joined.contains("--target") || joined.contains("my.monitor") || joined.contains("-i"));
    }

    @Test
    void toFloatConvertsPcm16Le() throws Exception {
        Method m = CommandLoopbackCapture.class.getDeclaredMethod("toFloat", byte[].class, int.class);
        m.setAccessible(true);
        byte[] pcm = new byte[]{0, 0, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80}; // 0, 32767, -32768
        float[] out = (float[]) m.invoke(null, pcm, pcm.length);
        assertEquals(3, out.length);
        assertEquals(0.0f, out[0], 1e-6);
        assertTrue(out[1] > 0.99f);
        assertTrue(out[2] <= -1.0f);
    }

    @Test
    void readLoopDeliversChunksAndStops() throws Exception {
        CommandLoopbackCapture cap = new CommandLoopbackCapture(16000, 0.001f, null); // bytesPerChunk coerced to >=320
        java.util.List<AudioChunk> got = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        cap.setConsumer(got::add);
        var f = CommandLoopbackCapture.class.getDeclaredField("running");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) f.get(cap)).set(true);

        byte[] pcm = new byte[640]; // >= bytesPerChunk (320), yields 2 chunks
        for (int i = 0; i < pcm.length; i += 2) { pcm[i] = 0; pcm[i + 1] = 0x10; }
        Method readLoop = CommandLoopbackCapture.class.getDeclaredMethod("readLoop", java.io.InputStream.class);
        readLoop.setAccessible(true);
        readLoop.invoke(cap, new java.io.ByteArrayInputStream(pcm));

        assertFalse(got.isEmpty());
        assertEquals("loopback", got.get(0).source());
        assertTrue(got.get(0).samples().length > 0);
        ((java.util.concurrent.atomic.AtomicBoolean) f.get(cap)).set(false);
    }

    @Test
    void stopIsIdempotentAndNameStable() {
        CommandLoopbackCapture cap = new CommandLoopbackCapture(16000, 0.25f, null);
        assertDoesNotThrow(cap::stop);
        assertDoesNotThrow(cap::stop);
        assertFalse(cap.isRunning());
        assertTrue(cap.name().startsWith("loopback-cmd:"));
    }
}
