package com.gek.audio;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SimpleAudioComponentsTest {
    @Test
    void noopLoopbackCaptureBasics() {
        NoopLoopbackCapture c = new NoopLoopbackCapture();
        AtomicReference<AudioChunk> seen = new AtomicReference<>();
        c.setConsumer(seen::set);
        assertDoesNotThrow(c::start);
        assertFalse(c.isRunning());
        assertEquals("loopback-noop", c.name());
        assertDoesNotThrow(c::stop);
        assertDoesNotThrow(c::close);
        assertNull(seen.get());
    }

    @Test
    void noopTtsEngineBasics() {
        NoopTtsEngine t = new NoopTtsEngine();
        assertFalse(t.isSupported());
        assertDoesNotThrow(() -> t.speak("hello", "en"));
        assertDoesNotThrow(() -> t.speak(null, null));
        assertDoesNotThrow(t::stop);
    }

    @Test
    void windowsAppDuckerPlaceholder() {
        WindowsAppDucker d = new WindowsAppDucker();
        assertFalse(d.isSupported());
        assertDoesNotThrow(d::duck);
        assertDoesNotThrow(d::unduck);
        assertDoesNotThrow(d::unduck);
    }

    @Test
    void audioDevicesListsWithoutThrowing() {
        assertDoesNotThrow(() -> {
            var names = AudioDevices.inputDeviceNames();
            assertNotNull(names);
            for (String n : names) assertNotNull(n);
        });
    }

    @Test
    void micAudioSourceDelegatesNameAndStateWithoutDevice() {
        MicCapture mic = new MicCapture(16000, 0.2f, null);
        MicAudioSource src = new MicAudioSource(mic);
        assertEquals("mic", src.name());
        assertFalse(src.isRunning());
        src.setConsumer(ch -> {});
        // do not start: requires real/available audio device in many envs; state/name/consumer wiring covered
        assertDoesNotThrow(src::stop);
    }
}
