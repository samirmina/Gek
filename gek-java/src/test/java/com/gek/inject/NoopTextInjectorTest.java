package com.gek.inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoopTextInjectorTest {
    @Test void noop() {
        NoopTextInjector inj = new NoopTextInjector();
        assertEquals("noop", inj.name());
        assertFalse(inj.inject("hello"));
        assertFalse(inj.inject(null));
        assertFalse(inj.inject(""));
    }
}
