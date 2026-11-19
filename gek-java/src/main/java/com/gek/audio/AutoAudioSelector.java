package com.gek.audio;

import com.gek.core.AppSettings;
import com.gek.core.PlatformSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort automatic selection of input/loopback capture sources.
 * - Mic: prefer system default (Java Sound) unless an explicit hint is required.
 * - Loopback/playback capture: on Windows try to discover a DirectShow audio capture device via ffmpeg;
 *   fall back to configured setting; on Linux prefer default monitor sources.
 */
public final class AutoAudioSelector {
    private static final Logger log = LoggerFactory.getLogger(AutoAudioSelector.class);

    private AutoAudioSelector() {}

    public static String resolveMicHint(AppSettings settings) {
        // null/blank => let Java Sound choose the system default input line
        if (settings == null) return null;
        String v = settings.audio_mic;
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || "auto".equalsIgnoreCase(v) || "default".equalsIgnoreCase(v) || "system".equalsIgnoreCase(v)) return null;
        return v;
    }

    public static String resolveLoopbackSource(AppSettings settings) {
        String configured = settings == null ? null : trimToNull(settings.audio_loopback);
        boolean configuredLooksLikeMic = configured != null && looksLikeMicConfiguredAsLoopback(configured);
        if (configured != null && !"auto".equalsIgnoreCase(configured) && !"default".equalsIgnoreCase(configured) && !configuredLooksLikeMic) {
            // honor explicit non-mic loopback device
            return configured;
        }

        if (PlatformSupport.isWindows()) {
            String detected = detectWindowsDshowAudioDevice();
            if (detected != null) {
                log.info("Auto-selected loopback capture device: {}", detected);
                return detected;
            }
            // common names as last-ditch hints (may or may not exist)
            return null;
        }

        // Linux/Pulse/PipeWire: default monitor works for many setups
        return "@DEFAULT_MONITOR@";
    }

    private static boolean looksLikeMicConfiguredAsLoopback(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        return s.contains("microphone") && !s.contains("stereo mix") && !s.contains("loopback") && !s.contains("cable");
    }

    private static String detectWindowsDshowAudioDevice() {
        if (!PlatformSupport.commandExists("ffmpeg")) return null;
        List<String> cmd = List.of("ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor(4, TimeUnit.SECONDS);
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            return pickBestDshowAudioDevice(out.toString());
        } catch (Exception e) {
            log.debug("dshow device probe failed: {}", e.getMessage());
            return null;
        }
    }

    static String pickBestDshowAudioDevice(String ffmpegOutput) {
        if (ffmpegOutput == null || ffmpegOutput.isBlank()) return null;
        List<String> devices = new ArrayList<>();
        boolean inAudio = false;
        for (String raw : ffmpegOutput.split("\\R")) {
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("directshow audio devices")) { inAudio = true; continue; }
            if (inAudio && lower.contains("directshow video devices")) break;
            if (!inAudio) continue;
            // example: [dshow @ ...]  "Microphone (Logitech G733 Gaming Headset)"
            int q1 = line.indexOf('"');
            int q2 = line.lastIndexOf('"');
            if (q1 >= 0 && q2 > q1) {
                String name = line.substring(q1 + 1, q2).trim();
                if (!name.isEmpty() && !lower.contains("alternative name")) devices.add(name);
            }
        }
        if (devices.isEmpty()) return null;
        // prefer loopback-capable capture sources
        String[] preferred = {"stereo mix", "loopback", "what u hear", "wave out", "cable output", "virtual audio", "vb-audio", "voicemeeter out"};
        for (String pref : preferred) {
            for (String d : devices) {
                if (d.toLowerCase(Locale.ROOT).contains(pref)) return d;
            }
        }
        // avoid picking plain microphones if a better capture might exist later; but return first non-obvious? none found
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
