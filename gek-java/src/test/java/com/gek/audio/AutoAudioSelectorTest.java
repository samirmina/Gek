package com.gek.audio;

import com.gek.core.AppSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoAudioSelectorTest {

    @Test
    void resolveMicHintReturnsNullForDefaultsAndAuto() {
        assertNull(AutoAudioSelector.resolveMicHint(null));

        AppSettings s = AppSettings.defaults();
        s.audio_mic = null;
        assertNull(AutoAudioSelector.resolveMicHint(s));

        s.audio_mic = "";
        assertNull(AutoAudioSelector.resolveMicHint(s));
        s.audio_mic = "   ";
        assertNull(AutoAudioSelector.resolveMicHint(s));
        s.audio_mic = "auto";
        assertNull(AutoAudioSelector.resolveMicHint(s));
        s.audio_mic = "AUTO";
        assertNull(AutoAudioSelector.resolveMicHint(s));
        s.audio_mic = "default";
        assertNull(AutoAudioSelector.resolveMicHint(s));
        s.audio_mic = "system";
        assertNull(AutoAudioSelector.resolveMicHint(s));

        s.audio_mic = "Microphone (USB)";
        assertEquals("Microphone (USB)", AutoAudioSelector.resolveMicHint(s));
        s.audio_mic = "  Line In  ";
        assertEquals("Line In", AutoAudioSelector.resolveMicHint(s));
    }

    @Test
    void pickBestDshowAudioDeviceParsesAndPrefersLoopback() {
        assertNull(AutoAudioSelector.pickBestDshowAudioDevice(null));
        assertNull(AutoAudioSelector.pickBestDshowAudioDevice(""));
        assertNull(AutoAudioSelector.pickBestDshowAudioDevice("no devices section"));

        String onlyMic = "[dshow @ 0] DirectShow video devices\n" +
                "[dshow @ 0] DirectShow audio devices\n" +
                "[dshow @ 0]  \"Microphone (Realtek Audio)\"\n" +
                "[dshow @ 0]     Alternative name \"@device_cm_x\"\n" +
                "[dshow @ 0] DirectShow video devices\n";
        // no preferred loopback keyword => null (avoid guessing mic as loopback)
        assertNull(AutoAudioSelector.pickBestDshowAudioDevice(onlyMic));

        String withStereoMix = "[dshow @ 0] DirectShow audio devices\n" +
                "[dshow @ 0]  \"Microphone (Realtek Audio)\"\n" +
                "[dshow @ 0]  \"Stereo Mix (Realtek Audio)\"\n";
        assertEquals("Stereo Mix (Realtek Audio)", AutoAudioSelector.pickBestDshowAudioDevice(withStereoMix));

        String withCable = "[dshow @ 0] DirectShow audio devices\n" +
                "[dshow @ 0]  \"Microphone\"\n" +
                "[dshow @ 0]  \"CABLE Output (VB-Audio Virtual Cable)\"\n";
        assertEquals("CABLE Output (VB-Audio Virtual Cable)", AutoAudioSelector.pickBestDshowAudioDevice(withCable));

        String withLoopback = "[dshow @ 0] DirectShow audio devices\n" +
                "[dshow @ 0]  \"Speakers (loopback)\"\n";
        assertEquals("Speakers (loopback)", AutoAudioSelector.pickBestDshowAudioDevice(withLoopback));

        String preferredOrder = "[dshow @ 0] DirectShow audio devices\n" +
                "[dshow @ 0]  \"VB-Audio Point\"\n" +
                "[dshow @ 0]  \"Stereo Mix\"\n";
        // "stereo mix" appears earlier in preference list than "vb-audio"
        assertEquals("Stereo Mix", AutoAudioSelector.pickBestDshowAudioDevice(preferredOrder));

        String altNameIgnored = "[dshow @ 0] DirectShow audio devices\n" +
                "[dshow @ 0]  \"Alternative name \"@device_cm\"\n";
        assertNull(AutoAudioSelector.pickBestDshowAudioDevice(altNameIgnored));
    }

    @Test
    void resolveLoopbackSourceHonorsExplicitAndIgnoresMicAsLoopback() {
        AppSettings s = AppSettings.defaults();

        // explicit non-mic loopback is honored regardless of platform probe outcome
        s.audio_loopback = "Stereo Mix (Realtek Audio)";
        assertEquals("Stereo Mix (Realtek Audio)", AutoAudioSelector.resolveLoopbackSource(s));

        s.audio_loopback = "wasapi:default";
        assertEquals("wasapi:default", AutoAudioSelector.resolveLoopbackSource(s));

        s.audio_loopback = "  CABLE Output  ";
        assertEquals("CABLE Output", AutoAudioSelector.resolveLoopbackSource(s));

        // mic configured as loopback is not honored as explicit loopback device
        s.audio_loopback = "Microphone (Logitech G733 Gaming Headset)";
        String resolved = AutoAudioSelector.resolveLoopbackSource(s);
        // on non-Windows CI/WSL without detected dshow this becomes default monitor; on Windows may be detected/null
        assertNotEquals("Microphone (Logitech G733 Gaming Headset)", resolved);

        // auto/default fall through platform logic
        s.audio_loopback = "auto";
        String autoResolved = AutoAudioSelector.resolveLoopbackSource(s);
        // Linux/Pulse path returns default monitor when not Windows; Windows may return null/detected
        if (!com.gek.core.PlatformSupport.isWindows()) {
            assertEquals("@DEFAULT_MONITOR@", autoResolved);
        }

        s.audio_loopback = "default";
        String defResolved = AutoAudioSelector.resolveLoopbackSource(s);
        if (!com.gek.core.PlatformSupport.isWindows()) {
            assertEquals("@DEFAULT_MONITOR@", defResolved);
        }
    }
}
