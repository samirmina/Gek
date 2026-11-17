package com.gek.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformSupportTest {
    @Test
    void capabilitiesAreConsistent() {
        PlatformSupport.Capabilities c = PlatformSupport.capabilities();
        if (c.windows()) {
            assertTrue(c.windowsGlobalHotkey());
            assertTrue(c.windowsWasapiLoopback());
            assertTrue(c.windowsCoreAudioDucking());
        }
        if (c.linux()) {
            assertFalse(c.windowsWasapiLoopback());
        }
        assertNotNull(Boolean.valueOf(c.gui()));
    }
}
