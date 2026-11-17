package com.gek.core;

import com.gek.audio.CommandLoopbackCapture;
import com.gek.inject.LinuxGlobalHotkey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeToolingCheckTest {
    @Test
    void reportsCapabilitiesWithoutThrowing() {
        PlatformSupport.Capabilities c = PlatformSupport.capabilities();
        assertNotNull(c);
        assertNotNull(CommandLoopbackCapture.backendName());
        if (c.linux()) assertTrue(LinuxGlobalHotkey.availableOnPlatform());
    }
}
