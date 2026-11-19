package com.gek.inject;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InputExecutorTest {
    static class Fake implements InputExecutor {
        final List<InputAction> seen = new ArrayList<>();
        final boolean[] results;
        int idx = 0;
        Fake(boolean... results) { this.results = results; }
        @Override public boolean execute(InputAction action) {
            seen.add(action);
            if (results.length == 0) return true;
            boolean r = results[Math.min(idx, results.length - 1)];
            idx++;
            return r;
        }
        @Override public String name() { return "fake"; }
    }

    @Test void executeAllEmptyOrNull() {
        Fake f = new Fake();
        assertTrue(f.executeAll(null, true));
        assertTrue(f.executeAll(List.of(), true));
        assertTrue(f.seen.isEmpty());
        assertEquals("fake", f.name());
    }

    @Test void executeAllStopsOnFailureWhenRequested() {
        Fake f = new Fake(true, false, true);
        boolean ok = f.executeAll(List.of(InputAction.enter(), InputAction.tab(), InputAction.escape()), true);
        assertFalse(ok);
        assertEquals(2, f.seen.size());
    }

    @Test void executeAllContinuesOnFailureWhenNotStopping() {
        Fake f = new Fake(true, false, true);
        boolean ok = f.executeAll(List.of(InputAction.enter(), InputAction.tab(), InputAction.escape()), false);
        assertFalse(ok);
        assertEquals(3, f.seen.size());
    }

    @Test void executeAllAllSuccess() {
        Fake f = new Fake(true, true);
        assertTrue(f.executeAll(List.of(InputAction.copy(), InputAction.paste()), true));
        assertEquals(2, f.seen.size());
    }
}
