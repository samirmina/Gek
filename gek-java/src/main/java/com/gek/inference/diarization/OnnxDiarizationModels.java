package com.gek.inference.diarization;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves expected ONNX diarization artifacts under resources/models/pyannote/onnx. */
public final class OnnxDiarizationModels {
    private OnnxDiarizationModels() {}

    public record Paths(Path segmentation, Path embedding, Path embeddingWeight, Path embeddingBias) {
        public boolean ready() {
            boolean segOk = segmentation != null && Files.isRegularFile(segmentation);
            boolean monolithic = embedding != null && Files.isRegularFile(embedding)
                    && !embedding.getFileName().toString().toLowerCase().contains("encoder");
            boolean split = embedding != null && Files.isRegularFile(embedding)
                    && embedding.getFileName().toString().toLowerCase().contains("encoder")
                    && embeddingWeight != null && Files.isRegularFile(embeddingWeight)
                    && embeddingBias != null && Files.isRegularFile(embeddingBias);
            // monolithic embedding.onnx (non-encoder name) OR split encoder + projection npy
            if (!monolithic && !split) {
                // encoder-named file without split assets not usable as monolithic
                monolithic = embedding != null && Files.isRegularFile(embedding)
                        && !embedding.getFileName().toString().toLowerCase().contains("encoder")
                        ? true : false;
            }
            return segOk && (monolithic || split);
        }
        public boolean splitEmbedding() {
            return embedding != null && Files.isRegularFile(embedding)
                    && embedding.getFileName().toString().toLowerCase().contains("encoder")
                    && embeddingWeight != null && Files.isRegularFile(embeddingWeight)
                    && embeddingBias != null && Files.isRegularFile(embeddingBias);
        }
    }

    public static Paths resolve(Path pyannoteDir) {
        if (pyannoteDir == null) return new Paths(null, null, null, null);
        Path base = pyannoteDir.resolve("onnx");
        Path parentOnnx = pyannoteDir.getParent() == null ? null : pyannoteDir.getParent().resolve("onnx");
        // Prefer community1-matched exports when present
        Path seg = firstExisting(
                base.resolve("segmentation.community1.onnx"),
                base.resolve("segmentation.onnx"),
                base.resolve("segmentation_model.onnx"),
                pyannoteDir.resolve("segmentation.community1.onnx"),
                pyannoteDir.resolve("segmentation.onnx"),
                parentOnnx == null ? null : parentOnnx.resolve("segmentation.community1.onnx"),
                parentOnnx == null ? null : parentOnnx.resolve("segmentation.onnx"));
        Path emb = firstExisting(
                base.resolve("embedding.community1.onnx"),
                base.resolve("embedding.onnx"),
                base.resolve("embedding_encoder.onnx"),
                base.resolve("embedding_model.onnx"),
                pyannoteDir.resolve("embedding.community1.onnx"),
                pyannoteDir.resolve("embedding.onnx"),
                pyannoteDir.resolve("embedding_encoder.onnx"),
                parentOnnx == null ? null : parentOnnx.resolve("embedding.community1.onnx"),
                parentOnnx == null ? null : parentOnnx.resolve("embedding.onnx"),
                parentOnnx == null ? null : parentOnnx.resolve("embedding_encoder.onnx"));
        Path w = firstExisting(base.resolve("resnet_seg_1_weight.npy"), pyannoteDir.resolve("resnet_seg_1_weight.npy"),
                parentOnnx == null ? null : parentOnnx.resolve("resnet_seg_1_weight.npy"));
        Path b = firstExisting(base.resolve("resnet_seg_1_bias.npy"), pyannoteDir.resolve("resnet_seg_1_bias.npy"),
                parentOnnx == null ? null : parentOnnx.resolve("resnet_seg_1_bias.npy"));
        if (seg == null || emb == null || w == null || b == null) {
            java.util.List<Path> roots = new java.util.ArrayList<>();
            roots.add(pyannoteDir);
            if (pyannoteDir.getParent() != null) roots.add(pyannoteDir.getParent());
            for (Path r : roots) {
                try (var s = Files.walk(r, 4)) {
                    var list = s.filter(Files::isRegularFile).toList();
                    if (seg == null) seg = list.stream().filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".onnx") && n.contains("segment") && n.contains("community1");
                    }).findFirst().orElse(null);
                    if (seg == null) seg = list.stream().filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".onnx") && n.contains("segment");
                    }).findFirst().orElse(null);
                    if (emb == null) emb = list.stream().filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".onnx") && n.contains("embed") && n.contains("community1");
                    }).findFirst().orElse(null);
                    if (emb == null) emb = list.stream().filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".onnx") && (n.contains("embedding_encoder") || n.equals("embedding.onnx") || n.contains("embed"));
                    }).findFirst().orElse(null);
                    if (w == null) w = list.stream().filter(p -> p.getFileName().toString().equals("resnet_seg_1_weight.npy")).findFirst().orElse(null);
                    if (b == null) b = list.stream().filter(p -> p.getFileName().toString().equals("resnet_seg_1_bias.npy")).findFirst().orElse(null);
                } catch (Exception ignored) {}
            }
        }
        return new Paths(seg, emb, w, b);
    }

    private static Path firstExisting(Path... ps) {
        for (Path p : ps) if (p != null && Files.isRegularFile(p)) return p;
        return null;
    }

    public static boolean ortAvailable() {
        return classPresent("ai.onnxruntime.OrtEnvironment") && classPresent("ai.onnxruntime.OnnxTensor");
    }

    private static boolean classPresent(String name) {
        try { Class.forName(name, false, OnnxDiarizationModels.class.getClassLoader()); return true; } catch (Throwable t) { return false; }
    }
}
