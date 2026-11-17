package com.gek.inference;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class UnavailableSpeechToTextEngine implements SpeechToTextEngine {
    private final String reason;
    public UnavailableSpeechToTextEngine(String reason) { this.reason = reason == null ? "speech-to-text engine unavailable" : reason; }
    @Override public CompletableFuture<Void> warmup() { return CompletableFuture.failedFuture(new SidecarException(reason)); }
    @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
        return CompletableFuture.failedFuture(new SidecarException(reason));
    }
    @Override public void close() {}
}
