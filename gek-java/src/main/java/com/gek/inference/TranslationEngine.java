package com.gek.inference;

import java.util.concurrent.CompletableFuture;

public interface TranslationEngine extends AutoCloseable {
    CompletableFuture<String> translate(String text, String sourceLang, String targetLang);
    @Override void close();
}
