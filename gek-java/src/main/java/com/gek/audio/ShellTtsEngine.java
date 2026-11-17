package com.gek.audio;

import com.gek.core.PlatformSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShellTtsEngine implements TtsEngine {
    private static final Logger log = LoggerFactory.getLogger(ShellTtsEngine.class);
    private final ProcessHandle[] current = new ProcessHandle[1];

    @Override public void speak(String text, String lang) throws Exception {
        if (text == null || text.isBlank()) return;
        stop();
        List<String> cmd = buildCmd(text, lang);
        if (cmd == null || cmd.isEmpty()) {
            log.info("No Linux TTS command found (install espeak-ng or spd-say). text={}", text);
            return;
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        current[0] = p.toHandle();
    }

    private List<String> buildCmd(String text, String lang) {
        if (PlatformSupport.commandExists("espeak-ng")) {
            List<String> c = new ArrayList<>();
            c.add("espeak-ng");
            if (lang != null && !lang.isBlank() && !"auto".equalsIgnoreCase(lang)) c.addAll(List.of("-v", lang));
            c.add(text);
            return c;
        }
        if (PlatformSupport.commandExists("espeak")) {
            List<String> c = new ArrayList<>();
            c.add("espeak");
            if (lang != null && !lang.isBlank() && !"auto".equalsIgnoreCase(lang)) c.addAll(List.of("-v", lang));
            c.add(text);
            return c;
        }
        if (PlatformSupport.commandExists("spd-say")) {
            List<String> c = new ArrayList<>();
            c.add("spd-say");
            if (lang != null && !lang.isBlank() && !"auto".equalsIgnoreCase(lang)) c.addAll(List.of("-l", lang));
            c.add("--");
            c.add(text);
            return c;
        }
        if (PlatformSupport.commandExists("piper")) {
            log.info("piper detected but requires model args; skipping for now.");
        }
        return null;
    }

    @Override public void stop() {
        try {
            ProcessHandle h = current[0];
            if (h != null && h.isAlive()) {
                h.descendants().forEach(ph -> { try { ph.destroyForcibly(); } catch (Exception ignored) {} });
                try { h.destroyForcibly(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        current[0] = null;
    }

    @Override public boolean isSupported() {
        return PlatformSupport.commandExists("espeak-ng") || PlatformSupport.commandExists("espeak") || PlatformSupport.commandExists("spd-say");
    }
}
