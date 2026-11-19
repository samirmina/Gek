package com.gek.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppModeTest {
    @Test
    void hasExpectedValues() {
        assertEquals(AppMode.LIVE_TRANSLATE, AppMode.valueOf("LIVE_TRANSLATE"));
        assertEquals(AppMode.CURSOR_DICTATE, AppMode.valueOf("CURSOR_DICTATE"));
        assertEquals(2, AppMode.values().length);
    }
}
