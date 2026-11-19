package com.gek.inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardTextInjectorTest {
    @Test
    void forPlatformReturnsInjectorWithName() {
        TextInjector inj = ClipboardTextInjector.forPlatform();
        assertNotNull(inj);
        assertNotNull(inj.name());
        assertFalse(inj.name().isBlank());
    }
}
