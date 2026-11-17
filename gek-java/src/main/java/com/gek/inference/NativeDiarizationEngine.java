package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.inference.diarization.OnnxOrtDiarizationPipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Java-native diarization engine (Option C).
 * Uses ONNX Runtime + pure-Java pre/post/PLDA clustering when ONNX exports and ORT are available.
 * Local Pyannote assets (torch .bin + plda/config) are present; ONNX artifacts/ORT dep may still be required.
 */
public class NativeDiarizationEngine implements DiarizationEngine {
    private final AppPaths paths;
    private final NativeRuntimeProbe.DiarizationRuntime runtime;

    public NativeDiarizationEngine(AppPaths paths) {
        this.paths = paths;
        this.runtime = NativeRuntimeProbe.resolveDiarizationRuntime(paths);
    }

    @Override public CompletableFuture<List<Segment>> diarize(Path wavPath) {
        if (wavPath == null) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException("wavPath is required"));
        }
        if (!runtime.usable()) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException(runtime.reason()));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path pyannoteDir = NativeRuntimeProbe.resolvePyannoteAssetDir(paths);
                if (pyannoteDir == null) throw new IllegalStateException("Pyannote asset directory not found");
                return OnnxOrtDiarizationPipeline.diarize(wavPath, pyannoteDir);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new RuntimeException(msg, e);
            }
        });
    }

    @Override public void close() {}
    public String reason() { return runtime.reason(); }
    public boolean runtimeUsable() { return runtime.usable(); }
}
