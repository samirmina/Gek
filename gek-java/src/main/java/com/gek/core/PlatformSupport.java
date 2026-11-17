package com.gek.core;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class PlatformSupport {
    private PlatformSupport() {}

    public static boolean isWindows() { return System.getProperty("os.name","" ).toLowerCase(Locale.ROOT).contains("win"); }
    public static boolean isLinux() { return System.getProperty("os.name","" ).toLowerCase(Locale.ROOT).contains("linux"); }
    public static boolean isWsl() {
        if (!isLinux()) return false;
        if (Files.exists(Path.of("/proc/sys/fs/binfmt_misc/WSLInterop")) || Files.exists(Path.of("/mnt/wslg"))) return true;
        try {
            String v = Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT);
            return v.contains("microsoft") || v.contains("wsl");
        } catch (Exception ignored) { return false; }
    }
    public static boolean hasWslg() {
        String disp = System.getenv("DISPLAY");
        String way = System.getenv("WAYLAND_DISPLAY");
        return isWsl() && (Files.exists(Path.of("/mnt/wslg")) || (disp != null && !disp.isBlank()) || (way != null && !way.isBlank()));
    }
    public static boolean guiAvailable() {
        if (hasWslg()) return true;
        if (isWindows()) return true;
        String disp = System.getenv("DISPLAY");
        String way = System.getenv("WAYLAND_DISPLAY");
        if ((disp == null || disp.isBlank()) && (way == null || way.isBlank())) return false;
        try { return !GraphicsEnvironment.isHeadless(); } catch (Throwable t) { return false; }
    }

    public record Capabilities(boolean windows, boolean linux, boolean wsl, boolean wslg, boolean gui,
                               boolean windowsGlobalHotkey, boolean windowsCaretInjection,
                               boolean windowsWasapiLoopback, boolean windowsCoreAudioDucking, boolean windowsSapiTts,
                               boolean linuxTextInjectionPossible, boolean linuxLocalTtsPossible) {}

    public static Capabilities capabilities() {
        boolean win = isWindows(), lin = isLinux(), wsl = isWsl(), wslg = hasWslg();
        boolean gui = guiAvailable();
        boolean linuxInject = lin && (commandExists("wl-copy") || commandExists("xclip") || commandExists("xsel") || commandExists("ydotool") || commandExists("xdotool"));
        boolean linuxTts = lin && (commandExists("espeak-ng") || commandExists("espeak") || commandExists("spd-say") || commandExists("piper"));
        return new Capabilities(win, lin, wsl, wslg, gui,
                win, win, win, win, win,
                linuxInject, linuxTts);
    }

    public static boolean commandExists(String name) {
        try {
            Process p = new ProcessBuilder("sh", "-lc", "command -v " + name).redirectErrorStream(true).start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) { return false; }
    }
}
