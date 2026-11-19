package com.gek.audio;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShellTtsEngineTest {
    @Test
    void supportedMatchesAvailableCommands() {
        ShellTtsEngine t = new ShellTtsEngine();
        boolean expected = PlatformSupport.commandExists("espeak-ng") || PlatformSupport.commandExists("espeak") || PlatformSupport.commandExists("spd-say");
        assertEquals(expected, t.isSupported());
    }

    @Test
    void speakIgnoresBlankAndNull() {
        ShellTtsEngine t = new ShellTtsEngine();
        assertDoesNotThrow(() -> t.speak(null, "en"));
        assertDoesNotThrow(() -> t.speak("", "en"));
        assertDoesNotThrow(() -> t.speak("   ", "en"));
        assertDoesNotThrow(t::stop);
    }

    @Test
    void speakWhenUnsupportedDoesNotThrow() throws Exception {
        ShellTtsEngine t = new ShellTtsEngine();
        if (!t.isSupported()) {
            assertDoesNotThrow(() -> t.speak("hello", "en"));
        } else {
            // If supported, avoid actually speaking long audio in CI-like env; speak short token and stop.
            t.speak("x", "en");
            t.stop();
        }
    }
}
