package com.gek.inference;

public record TranscriptionResult(
        String text,
        String language,
        double confidence,
        double durationSec
) {}
