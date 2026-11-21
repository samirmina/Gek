package com.gek.inject;

import com.gek.core.PlatformSupport;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows foreground target capture/restore for voice typing injection.
 * <p>
 * Tracks the intended foreground/caret target so dictated text/keys are delivered to the
 * user's focused field. Injection is permitted in GEK's own environment and in any other
 * application (any valid focused window) while overlays remain non-activating; callers must
 * treat a failed restore as "target not secured". Works within Windows foreground-lock
 * restrictions (best-effort restore).
 */
public final class WindowsFocusManager {
    private static final Logger log = LoggerFactory.getLogger(WindowsFocusManager.class);
    private static final long SELF_PID = ProcessHandle.current().pid();

    private interface User32Ex extends StdCallLibrary, User32 {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);
        int GetWindowThreadProcessId(WinDef.HWND hWnd, IntByReference lpdwProcessId);
        boolean IsWindow(WinDef.HWND hWnd);
        boolean IsWindowVisible(WinDef.HWND hWnd);
        boolean BringWindowToTop(WinDef.HWND hWnd);
        boolean AttachThreadInput(int idAttach, int idAttachTo, boolean fAttach);
        boolean AllowSetForegroundWindow(int dwProcessId);
    }

    /** Session target: last captured foreground/caret window for voice typing. */
    private static volatile WinDef.HWND savedForeground;

    private WindowsFocusManager() {}

    /** Capture current foreground window if it is a valid target (GEK or any other app). */
    public static synchronized void captureForeground() {
        if (!PlatformSupport.isWindows()) return;
        try {
            WinDef.HWND hwnd = User32Ex.INSTANCE.GetForegroundWindow();
            if (isValidWindow(hwnd)) {
                savedForeground = hwnd;
            }
        } catch (Throwable t) {
            log.debug("captureForeground failed: {}", t.getMessage());
        }
    }

    /** Capture only if there is no valid target yet (hotkey-first sessions remain authoritative). */
    public static synchronized void captureForegroundIfUseful() {
        if (!PlatformSupport.isWindows()) return;
        if (isValidWindow(savedForeground)) return;
        captureForeground();
    }

    /**
     * Secure an injection target and allow caret delivery.
     * Injection is allowed into the focused/caret target in any environment (GEK or any other app).
     * @return true if a valid foreground target is secured for injection.
     */
    public static synchronized boolean restoreForeground() {
        if (!PlatformSupport.isWindows()) return true; // non-Windows: no HWND target management
        try {
            WinDef.HWND current = User32Ex.INSTANCE.GetForegroundWindow();
            WinDef.HWND saved = savedForeground;

            // Already on the intended target -> inject in place.
            if (isValidWindow(saved) && sameWindow(current, saved)) return true;

            // Current focused window is valid (GEK or any other app): allow injecting at current caret.
            // This fulfills "inject in GEK environment and any other environment/app".
            if (isValidWindow(current)) {
                savedForeground = current;
                return true;
            }

            // No current foreground; best-effort restore last known valid target.
            if (!isValidWindow(saved)) {
                savedForeground = null;
                log.debug("No valid injection target to restore");
                return false;
            }
            boolean attached = false;
            int curTid = 0, tgtTid = 0;
            try {
                IntByReference curPid = new IntByReference();
                IntByReference tgtPid = new IntByReference();
                curTid = current != null ? User32Ex.INSTANCE.GetWindowThreadProcessId(current, curPid) : 0;
                tgtTid = User32Ex.INSTANCE.GetWindowThreadProcessId(saved, tgtPid);
                if (curTid != 0 && tgtTid != 0 && curTid != tgtTid) {
                    attached = User32Ex.INSTANCE.AttachThreadInput(curTid, tgtTid, true);
                }
                try { User32Ex.INSTANCE.AllowSetForegroundWindow(-1); } catch (Throwable ignored) {}
                User32Ex.INSTANCE.ShowWindow(saved, 9 /* SW_RESTORE */);
                User32Ex.INSTANCE.BringWindowToTop(saved);
                User32Ex.INSTANCE.SetForegroundWindow(saved);
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                WinDef.HWND after = User32Ex.INSTANCE.GetForegroundWindow();
                if (sameWindow(after, saved) || isValidWindow(after)) {
                    if (isValidWindow(after)) savedForeground = after;
                    return true;
                }
                log.debug("Could not restore injection target (foreground lock)");
                return false;
            } finally {
                if (attached) {
                    try { User32Ex.INSTANCE.AttachThreadInput(curTid, tgtTid, false); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            log.debug("restoreForeground failed: {}", t.getMessage());
            return false;
        }
    }

    /** True if a valid captured target is currently known for this session. */
    public static synchronized boolean hasTarget() {
        return isValidWindow(savedForeground);
    }

    /** Back-compat: previously meant a valid external (non-GEK) target; now any valid captured target. */
    public static synchronized boolean hasExternalTarget() {
        return hasTarget();
    }

    /** Clear captured target (e.g. when voice typing stops). */
    public static synchronized void clear() { savedForeground = null; }

    private static boolean isExternalTarget(WinDef.HWND hwnd) {
        return isValidWindow(hwnd) && !isSelf(hwnd);
    }

    private static boolean isValidWindow(WinDef.HWND hwnd) {
        try {
            if (hwnd == null || hwnd.getPointer() == null) return false;
            if (Pointer.nativeValue(hwnd.getPointer()) == 0) return false;
            return User32Ex.INSTANCE.IsWindow(hwnd);
        } catch (Throwable t) { return false; }
    }

    private static boolean isSelf(WinDef.HWND hwnd) {
        if (!isValidWindow(hwnd)) return false;
        try {
            IntByReference pidRef = new IntByReference();
            User32Ex.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            return pidRef.getValue() == (int) SELF_PID;
        } catch (Throwable t) { return false; }
    }

    private static boolean sameWindow(WinDef.HWND a, WinDef.HWND b) {
        if (a == null || b == null) return false;
        if (a.getPointer() == null || b.getPointer() == null) return false;
        return Pointer.nativeValue(a.getPointer()) == Pointer.nativeValue(b.getPointer());
    }
}
