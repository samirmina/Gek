package com.gek.inference;

import java.util.concurrent.CompletableFuture;

public class UnavailableTranslationEngine implements TranslationEngine {
    private final String reason;
    public UnavailableTranslationEngine(String reason) { this.reason = reason == null ? "translation engine unavailable" : reason; }
    @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
        return CompletableFuture.failedFuture(new SidecarException(reason));
    }
    @Override public void close() {}
}
