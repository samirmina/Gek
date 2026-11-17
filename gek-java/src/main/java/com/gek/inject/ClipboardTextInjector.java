package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ClipboardTextInjector implements TextInjector {
    private static final Logger log = LoggerFactory.getLogger(ClipboardTextInjector.class);
    private final String method;

    public ClipboardTextInjector() { this.method = detectMethod(); }

    @Override public String name() { return method; }

    @Override public boolean inject(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            if ("windows-robot-clipboard".equals(method)) return pasteWindowsRobot(text);
            if ("wl-copy+ydotool".equals(method)) return pasteLinux(text, List.of("wl-copy"), List.of("ydotool","key","29:1","47:1","47:0","29:0"));
            if ("wl-copy+clipboard-only".equals(method)) { copyLinux(text, List.of("wl-copy")); return true; }
            if ("xclip+xdotool".equals(method)) return pasteLinux(text, List.of("xclip","-selection","clipboard"), List.of("xdotool","key","--clearmodifiers","ctrl+v"));
            if ("xclip+clipboard-only".equals(method)) { copyLinux(text, List.of("xclip","-selection","clipboard")); return true; }
            if ("awt-clipboard".equals(method)) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); return true; }
            return false;
        } catch (Exception e) {
            log.warn("inject failed ({}): {}", method, e.getMessage());
            return false;
        }
    }

    private static String detectMethod() {
        if (PlatformSupport.isWindows()) return "windows-robot-clipboard";
        if (PlatformSupport.commandExists("wl-copy") && PlatformSupport.commandExists("ydotool")) return "wl-copy+ydotool";
        if (PlatformSupport.commandExists("xclip") && PlatformSupport.commandExists("xdotool")) return "xclip+xdotool";
        if (PlatformSupport.commandExists("wl-copy")) return "wl-copy+clipboard-only";
        if (PlatformSupport.commandExists("xclip")) return "xclip+clipboard-only";
        if (!GraphicsEnvironment.isHeadless()) return "awt-clipboard";
        return "none";
    }

    private boolean pasteWindowsRobot(String text) throws Exception {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable old = null; try { old = cb.getContents(null); } catch (Exception ignored) {}
        String oldStr = null;
        if (old != null) try { if (old.isDataFlavorSupported(DataFlavor.stringFlavor)) oldStr = (String) old.getTransferData(DataFlavor.stringFlavor); } catch (Exception ignored) {}
        cb.setContents(new StringSelection(text), null);
        Thread.sleep(30);
        Robot r = new Robot(); r.setAutoDelay(10);
        r.keyPress(KeyEvent.VK_CONTROL); r.keyPress(KeyEvent.VK_V); r.keyRelease(KeyEvent.VK_V); r.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(70);
        try { if (oldStr != null) cb.setContents(new StringSelection(oldStr), null); } catch (Exception ignored) {}
        return true;
    }

    private boolean pasteLinux(String text, List<String> copyCmd, List<String> pasteCmd) throws Exception {
        copyLinux(text, copyCmd);
        Thread.sleep(40);
        Process p = new ProcessBuilder(pasteCmd).redirectErrorStream(true).start();
        int code = p.waitFor();
        return code == 0;
    }
    private void copyLinux(String text, List<String> copyCmd) throws Exception {
        Process p = new ProcessBuilder(copyCmd).redirectErrorStream(true).start();
        p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        p.waitFor();
    }

    public static TextInjector bestEffort() { return new ClipboardTextInjector(); }
}
