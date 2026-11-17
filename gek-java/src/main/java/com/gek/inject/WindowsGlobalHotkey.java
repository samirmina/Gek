package com.gek.inject;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registers a global hotkey on Windows and invokes callback on WM_HOTKEY.
 * Must run a message loop on its own thread. Validate on Windows.
 */
public class WindowsGlobalHotkey implements GlobalHotkey {
    private static final Logger log = LoggerFactory.getLogger(WindowsGlobalHotkey.class);
    public static final int MOD_ALT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;
    public static final int MOD_SHIFT = 0x0004;
    public static final int MOD_WIN = 0x0008;

    private final int id;
    private final int modifiers;
    private final int vk;
    private final Runnable onHotkey;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger threadId = new AtomicInteger(0);
    private Thread thread;

    public WindowsGlobalHotkey(int id, int modifiers, int vk, Runnable onHotkey) {
        this.id = id; this.modifiers = modifiers; this.vk = vk; this.onHotkey = onHotkey;
    }

    @Override public boolean isSupported() { return System.getProperty("os.name","").toLowerCase().contains("win"); }

    @Override public void start() {
        if (running.get()) return;
        running.set(true);
        thread = new Thread(this::loop, "gek-hotkey");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        threadId.set(Kernel32.INSTANCE.GetCurrentThreadId());
        User32 u = User32.INSTANCE;
        boolean ok = u.RegisterHotKey(null, id, modifiers, vk);
        log.info("RegisterHotKey id={} mod={} vk={} ok={}", id, modifiers, vk, ok);
        if (!ok) { running.set(false); threadId.set(0); return; }
        WinUser.MSG msg = new WinUser.MSG();
        try {
            while (running.get()) {
                int r = u.GetMessage(msg, null, 0, 0);
                if (r == 0 || r == -1) break;
                if (msg.message == WinUser.WM_HOTKEY && msg.wParam.intValue() == id) {
                    try { onHotkey.run(); } catch (Exception e) { log.warn("hotkey callback error: {}", e.getMessage()); }
                }
                u.TranslateMessage(msg);
                u.DispatchMessage(msg);
            }
        } finally {
            u.UnregisterHotKey(null, id);
            threadId.set(0);
            log.info("Hotkey unregistered id={}", id);
        }
    }

    @Override public void stop() {
        if (!running.getAndSet(false)) return;
        int tid = threadId.get();
        if (tid != 0) {
            try { User32.INSTANCE.PostThreadMessage(tid, WinUser.WM_QUIT, new com.sun.jna.platform.win32.WinDef.WPARAM(0), new com.sun.jna.platform.win32.WinDef.LPARAM(0)); }
            catch (Throwable t) { log.debug("PostThreadMessage failed: {}", t.getMessage()); }
        }
    }
    @Override public void close() { stop(); }

    /** Parse simple combos like CTRL+SHIFT+SPACE. */
    public static HotkeySpec parse(String spec) {
        if (spec == null) spec = "CTRL+SHIFT+SPACE";
        int mod = 0; int vk = java.awt.event.KeyEvent.VK_SPACE;
        for (String p : spec.toUpperCase().split("\\+")) {
            String t = p.trim();
            switch (t) {
                case "CTRL", "CONTROL" -> mod |= MOD_CONTROL;
                case "SHIFT" -> mod |= MOD_SHIFT;
                case "ALT" -> mod |= MOD_ALT;
                case "WIN", "META" -> mod |= MOD_WIN;
                case "SPACE" -> vk = java.awt.event.KeyEvent.VK_SPACE;
                default -> {
                    if (t.length() == 1) vk = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(t.charAt(0));
                    else {
                        try { vk = (int) java.awt.event.KeyEvent.class.getField("VK_" + t).get(null); } catch (Exception ignored) {}
                    }
                }
            }
        }
        return new HotkeySpec(mod, vk);
    }
    public record HotkeySpec(int modifiers, int vk) {}
}
