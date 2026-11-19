package com.gek.inject;

import java.util.List;

/**
 * Executes {@link InputAction}s against the currently focused target (text cursor/field/app).
 * Implementations may use AWT Robot, platform-native APIs, accessibility/UIA, or remote
 * input channels. Designed as an extension point for keyboard/mouse/cursor/remote control.
 */
public interface InputExecutor {
    /** Execute a single action. Returns true on best-effort success. */
    boolean execute(InputAction action);

    /** Execute actions in order, stopping on first hard failure if stopOnFailure is true. */
    default boolean executeAll(List<InputAction> actions, boolean stopOnFailure) {
        if (actions == null || actions.isEmpty()) return true;
        boolean ok = true;
        for (InputAction a : actions) {
            boolean r = execute(a);
            if (!r) {
                ok = false;
                if (stopOnFailure) return false;
            }
        }
        return ok;
    }

    /** Human-readable backend name (e.g. robot, windows-uia, remote). */
    String name();
}
