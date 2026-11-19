package com.gek.audio;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MicCaptureTest {

    @Test
    void toFloatAndStateWithoutDeviceStart() throws Exception {
        MicCapture mic = new MicCapture(16000, 0.25f, "nonexistent-device-hint");
        assertFalse(mic.isRunning());
        mic.setConsumer(c -> {});
        // stop/close safe before start
        assertDoesNotThrow(mic::stop);
        assertDoesNotThrow(mic::close);

        Method m = MicCapture.class.getDeclaredMethod("toFloat", byte[].class, int.class);
        m.setAccessible(true);
        byte[] pcm = new byte[]{0, 0, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80};
        float[] out = (float[]) m.invoke(mic, pcm, pcm.length);
        assertEquals(3, out.length);
        assertEquals(0.0f, out[0], 1e-6);
        assertTrue(out[1] > 0.99f);
        assertTrue(out[2] <= -1.0f);
    }

    @Test
    void findMixerReturnsNullForUnknownHint() throws Exception {
        MicCapture mic = new MicCapture(16000, 0.25f, null);
        Method m = MicCapture.class.getDeclaredMethod("findMixer", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(mic, (Object) null));
        assertNull(m.invoke(mic, ""));
        assertNull(m.invoke(mic, "definitely-no-such-mixer-xyz-123"));
        mic.close();
    }
}
