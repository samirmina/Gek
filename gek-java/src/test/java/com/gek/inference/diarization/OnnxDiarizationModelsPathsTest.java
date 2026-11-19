package com.gek.inference.diarization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OnnxDiarizationModelsPathsTest {
    @TempDir Path tmp;

    @Test
    void resolveNullAndEmpty() {
        OnnxDiarizationModels.Paths p = OnnxDiarizationModels.resolve(null);
        assertNotNull(p);
        assertFalse(p.ready());
        assertFalse(p.splitEmbedding());
        p = OnnxDiarizationModels.resolve(tmp);
        assertFalse(p.ready());
    }

    @Test
    void monolithicEmbeddingIsReady() throws Exception {
        Path py = tmp.resolve("pyannote");
        Path onnx = py.resolve("onnx");
        Files.createDirectories(onnx);
        Files.writeString(onnx.resolve("segmentation.onnx"), "x");
        Files.writeString(onnx.resolve("embedding.onnx"), "x");
        OnnxDiarizationModels.Paths p = OnnxDiarizationModels.resolve(py);
        assertTrue(p.ready());
        assertFalse(p.splitEmbedding());
        assertNotNull(p.segmentation());
        assertNotNull(p.embedding());
    }

    @Test
    void splitEmbeddingRequiresEncoderWeightBias() throws Exception {
        Path py = tmp.resolve("py");
        Path onnx = py.resolve("onnx");
        Files.createDirectories(onnx);
        Files.writeString(onnx.resolve("segmentation.onnx"), "x");
        Files.writeString(onnx.resolve("embedding_encoder.onnx"), "x");
        OnnxDiarizationModels.Paths missing = OnnxDiarizationModels.resolve(py);
        assertFalse(missing.ready(), "encoder without projection npy not ready");

        Files.writeString(onnx.resolve("resnet_seg_1_weight.npy"), "w");
        Files.writeString(onnx.resolve("resnet_seg_1_bias.npy"), "b");
        OnnxDiarizationModels.Paths p = OnnxDiarizationModels.resolve(py);
        assertTrue(p.ready());
        assertTrue(p.splitEmbedding());
    }

    @Test
    void prefersCommunity1AndSearchesNested() throws Exception {
        Path py = tmp.resolve("p");
        Path onnx = py.resolve("onnx");
        Files.createDirectories(onnx);
        Files.writeString(onnx.resolve("segmentation.onnx"), "old");
        Files.writeString(onnx.resolve("segmentation.community1.onnx"), "new");
        Files.writeString(onnx.resolve("embedding.community1.onnx"), "e");
        OnnxDiarizationModels.Paths p = OnnxDiarizationModels.resolve(py);
        assertTrue(p.ready());
        assertTrue(p.segmentation().getFileName().toString().contains("community1"));

        Path py2 = tmp.resolve("nested");
        Files.createDirectories(py2.resolve("deep/onnx"));
        Files.writeString(py2.resolve("deep/onnx/segmentation_model.onnx"), "s");
        Files.writeString(py2.resolve("deep/onnx/embedding_model.onnx"), "e");
        OnnxDiarizationModels.Paths p2 = OnnxDiarizationModels.resolve(py2);
        assertNotNull(p2.segmentation());
        assertNotNull(p2.embedding());
    }
}
