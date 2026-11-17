package com.gek.inference;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface DiarizationEngine extends AutoCloseable {
    record Segment(double start, double end, String speaker) {}
    CompletableFuture<List<Segment>> diarize(Path wavPath);
    @Override void close();
}
