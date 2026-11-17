package com.gek.db;

import java.time.Instant;

public record TranslationRecord(
        long id,
        String originalText,
        String originalLang,
        String translatedText,
        String translatedLang,
        String sourceName,
        String sourcePath,
        String audioSource,
        String timestamp
) {
    public static TranslationRecord ofNew(String originalText, String originalLang,
                                          String translatedText, String translatedLang,
                                          String sourceName, String sourcePath, String audioSource) {
        return new TranslationRecord(0, originalText, originalLang, translatedText, translatedLang,
                sourceName, sourcePath, audioSource, Instant.now().toString());
    }
}
