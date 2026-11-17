package com.gek.inference;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class UnavailableDiarizationEngine implements DiarizationEngine {
    private final String reason;
    public UnavailableDiarizationEngine(String reason) { this.reason = reason == null ? "diarization engine unavailable" : reason; }
    @Override public CompletableFuture<List<Segment>> diarize(Path wavPath) {
        return CompletableFuture.failedFuture(new SidecarException(reason));
    }
    @Override public void close() {}
}
