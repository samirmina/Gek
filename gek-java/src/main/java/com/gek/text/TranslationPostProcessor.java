package com.gek.text;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Ports Python Translator heuristics: repetition/hallucination detection. */
public final class TranslationPostProcessor {
    private static final Pattern SYMBOL_FLOOD = Pattern.compile("(\\w{1,6}\\.){4,}");
    private static final Pattern CHAR_FLOOD = Pattern.compile("(.)\\1{5,}");
    private static final Pattern PAIR_FLOOD = Pattern.compile("(.{1,2})\\1{5,}");
    private static final Pattern PUNCT_REPEAT = Pattern.compile("([.,!?;:…。，！？、])\\1{3,}");

    private TranslationPostProcessor() {}

    public static boolean isRepetitive(String text, String sourceText) {
        if (text == null || text.trim().isEmpty()) return false;
        String lower = text.toLowerCase().trim();
        String[] words = lower.split("\\s+");

        if (sourceText != null && !sourceText.trim().isEmpty()) {
            double ratio = (double) text.length() / (double) sourceText.length();
            if (ratio > 5.0) return true;
        }

        if (SYMBOL_FLOOD.matcher(lower).find()) return true;

        String nospace = lower.replace(" ", "");
        if (CHAR_FLOOD.matcher(nospace).find()) return true;
        if (PAIR_FLOOD.matcher(nospace).find()) return true;

        if (words.length >= 5) {
            for (int i = 0; i <= words.length - 5; i++) {
                if (words[i].equals(words[i+1]) && words[i].equals(words[i+2]) && words[i].equals(words[i+3]) && words[i].equals(words[i+4])) return true;
            }
        }

        int maxN = Math.min(4, Math.max(2, words.length / 4 + 1));
        for (int n = 2; n <= maxN; n++) {
            if (words.length < n * 4) continue;
            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i <= words.length - n; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < n; j++) { if (j > 0) sb.append(' '); sb.append(words[i+j]); }
                String phrase = sb.toString();
                int c = counts.getOrDefault(phrase, 0) + 1;
                if (c >= 4) return true;
                counts.put(phrase, c);
            }
        }

        int punctCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (".,!?;:…。，！？、".indexOf(c) >= 0) punctCount++;
        }
        if (punctCount > text.length() * 0.4 && text.length() < 50) return true;

        if (PUNCT_REPEAT.matcher(text).find()) return true;

        return false;
    }
}
