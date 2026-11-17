package com.gek.inference;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface SpeechToTextEngine extends AutoCloseable {
    CompletableFuture<Void> warmup();
    /** Transcribe 16kHz mono PCM/float audio bytes or a wav path depending on impl; sidecar contract TBD. */
    CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint);
    @Override void close();
}
