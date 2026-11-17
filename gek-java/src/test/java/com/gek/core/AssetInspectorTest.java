package com.gek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AssetInspectorTest {
    @TempDir Path tmp;

    @Test
    void reportsMissingAndCompleteAssets() throws Exception {
        Files.createDirectories(tmp.resolve("resources/models/whisper/models--Systran--faster-whisper-small/snapshots/abc"));
        Files.writeString(tmp.resolve("resources/models/whisper/models--Systran--faster-whisper-small/snapshots/abc/model.bin"), "x");
        Files.createDirectories(tmp.resolve("resources/models/whisper/models--Systran--faster-whisper-large-v3/snapshots/def"));
        Files.createDirectories(tmp.resolve("resources/models/argos/packages/en_ar"));
        Path p = tmp.resolve("resources/models/pyannote/speaker-diarization-community-1");
        Files.createDirectories(p.resolve("embedding"));
        Files.createDirectories(p.resolve("segmentation"));
        Files.createDirectories(p.resolve("plda"));
        for (String f : new String[]{"config.yaml","embedding/pytorch_model.bin","segmentation/pytorch_model.bin","plda/plda.npz","plda/xvec_transform.npz"}) {
            Path fp = p.resolve(f); Files.createDirectories(fp.getParent()); Files.writeString(fp, "x");
        }
        Files.createDirectories(tmp.resolve("resources/data/synonyms"));
        Files.writeString(tmp.resolve("resources/data/synonyms/en.json"), "{}");
        Files.writeString(tmp.resolve("resources/data/settings.json"), "{}");
        Files.createDirectories(tmp.resolve("gek-java")); // help resolver
        System.setProperty("gek.projectRoot", tmp.toString());
        try {
            AppPaths paths = AppPaths.resolve();
            AssetInspector.AssetReport r = AssetInspector.inspect(paths);
            assertTrue(r.settingsExists);
            assertEquals(1, r.synonymFiles);
            assertEquals(1, r.argosPackages);
            assertTrue(r.pyannoteComplete);
            assertEquals(2, r.whisperModels.size());
            assertTrue(r.whisperModels.stream().anyMatch(w -> w.name.equals("small") && w.complete));
            assertTrue(r.whisperModels.stream().anyMatch(w -> w.name.equals("large-v3") && !w.complete));
            String text = r.toText();
            assertTrue(text.contains("small: COMPLETE"));
            assertTrue(text.contains("large-v3: INCOMPLETE"));
        } finally {
            System.clearProperty("gek.projectRoot");
        }
    }
}
