package com.gek.text;

import java.util.regex.Pattern;

/**
 * Cleans STT transcript artifacts (whisper non-speech markers) so they are never shown,
 * injected, or copied into chat/voice-typing output.
 */
public final class TranscriptSanitizer {
    // Whisper/common markers: [BLANK_AUDIO], (blank audio), [silence], (silence), [music], (music),
    // [applause], (applause), [laughter], (laughter), [_BEG_], etc.
    private static final Pattern ARTIFACTS = Pattern.compile(
            "(?i)(\\[(?:\\s*(?:blank_audio|silence|music|applause|laughter|noise|inaudible|_beg_|_tt_\\d*)\\s*)\\]"
            + "|\\((?:\\s*(?:blank audio|silence|music|applause|laughter|noise|inaudible)\\s*)\\))");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private TranscriptSanitizer() {}

    /** Remove non-speech marker tokens and normalize whitespace. Returns empty string for null/marker-only. */
    public static String sanitize(String text) {
        if (text == null) return "";
        String s = ARTIFACTS.matcher(text).replaceAll(" ");
        // also drop standalone marker-like remnants that lost brackets/parens in odd outputs
        s = s.replace("[BLANK_AUDIO]", " ").replace("[ Silence ]", " ").replace("[silence]", " ");
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /** True if text is null/blank or contains only non-speech artifacts. */
    public static boolean isBlankOrArtifact(String text) {
        return sanitize(text).isEmpty();
    }
}