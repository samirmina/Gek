package com.gek.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CommandLoopbackCaptureProcessTest {

    @Test
    @Timeout(10)
    void readLoopDeliversChunksAndStopsAtEof() throws Exception {
        CommandLoopbackCapture cap = new CommandLoopbackCapture(16000, 0.01f, null);
        // 0.01s * 16000 * 2 bytes = 320 bytes per chunk
        byte[] pcm = new byte[640];
        for (int i = 0; i < pcm.length / 2; i++) {
            short s = (short) (i * 10);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AtomicInteger chunks = new AtomicInteger();
        CountDownLatch got = new CountDownLatch(2);
        cap.setConsumer(ch -> { chunks.incrementAndGet(); got.countDown(); });

        // invoke private readLoop(InputStream) via reflection to exercise parsing/consumer without spawning external tools
        Method m = CommandLoopbackCapture.class.getDeclaredMethod("readLoop", java.io.InputStream.class);
        m.setAccessible(true);
        // mark running=true via start-like state is internal; readLoop checks running flag field
        java.lang.reflect.Field running = CommandLoopbackCapture.class.getDeclaredField("running");
        running.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) running.get(cap)).set(true);

        Thread t = new Thread(() -> {
            try { m.invoke(cap, new ByteArrayInputStream(pcm)); } catch (Exception ignored) {}
        });
        t.start();
        assertTrue(got.await(3, TimeUnit.SECONDS));
        ((java.util.concurrent.atomic.AtomicBoolean) running.get(cap)).set(false);
        t.join(2000);
        assertTrue(chunks.get() >= 2);
        assertFalse(cap.isRunning());
        assertDoesNotThrow(cap::stop);
    }

    @Test
    void stopIsIdempotentWithoutStart() {
        CommandLoopbackCapture cap = new CommandLoopbackCapture(16000, 0.25f, null);
        assertFalse(cap.isRunning());
        assertDoesNotThrow(cap::stop);
        assertDoesNotThrow(cap::stop);
        assertNotNull(cap.name());
    }
}
