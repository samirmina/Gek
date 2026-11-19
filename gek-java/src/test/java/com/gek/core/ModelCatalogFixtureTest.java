package com.gek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogFixtureTest {
    @TempDir Path tmp;

    @Test
    void inspectHandlesMissingDirectories() throws Exception {
        Files.createDirectories(tmp.resolve("resources"));
        String prev = System.getProperty("gek.projectRoot");
        try {
            System.setProperty("gek.projectRoot", tmp.toString());
            AppPaths paths = AppPaths.resolve();
            ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
            assertNotNull(c);
            assertNotNull(c.whisper);
            assertTrue(c.whisper.isEmpty());
            assertNotNull(c.argosPairs);
            assertTrue(c.argosPairs.isEmpty());
            assertNotNull(c.synonymLangs);
            assertTrue(c.synonymLangs.isEmpty());
            assertNotNull(c.pyannote);
            assertFalse(c.pyannote.complete);
            assertTrue(c.toText().contains("Model catalog"));
            assertTrue(c.toText().contains("INCOMPLETE"));
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot");
            else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void parsesWhisperSnapshotsArgosPairsAndSynonyms() throws Exception {
        Path w = tmp.resolve("resources/models/whisper");
        Path snapOk = w.resolve("models--Systran--faster-whisper-base/snapshots/h1");
        Files.createDirectories(snapOk);
        Files.writeString(snapOk.resolve("model.bin"), "x");
        Files.writeString(snapOk.resolve("tokenizer.json"), "{}");
        Files.writeString(snapOk.resolve("config.json"), "{\"a\":1,\"b\":\"x\",\"c\":true}");
        Path snapBad = w.resolve("models--Systran--faster-whisper-small/snapshots/h2");
        Files.createDirectories(snapBad);

        Path pkgs = tmp.resolve("resources/models/argos/packages");
        Path p1 = pkgs.resolve("translate-en_de-1_0");
        Files.createDirectories(p1.resolve("model"));
        Files.writeString(p1.resolve("model/model.bin"), "x");
        Files.writeString(p1.resolve("sentencepiece.model"), "x");
        Files.writeString(p1.resolve("metadata.json"),
                "{\"from_code\":\"en\",\"to_code\":\"de\",\"from_name\":\"English\",\"to_name\":\"German\",\"package_version\":\"1.0\"}");
        Path p2 = pkgs.resolve("translate-fr_en-1_9");
        Files.createDirectories(p2.resolve("model"));
        Files.writeString(p2.resolve("model/model.bin"), "x");
        Path p3 = pkgs.resolve("ar_en");
        Files.createDirectories(p3);

        Path py = tmp.resolve("resources/models/pyannote/speaker-diarization-community-1");
        Files.createDirectories(py.resolve("embedding"));
        Files.createDirectories(py.resolve("segmentation"));
        Files.createDirectories(py.resolve("plda"));
        for (String f : new String[]{"config.yaml", "embedding/pytorch_model.bin", "segmentation/pytorch_model.bin",
                "plda/plda.npz", "plda/xvec_transform.npz"}) {
            Path fp = py.resolve(f);
            Files.createDirectories(fp.getParent());
            Files.writeString(fp, "x");
        }

        Files.createDirectories(tmp.resolve("resources/data/synonyms"));
        Files.writeString(tmp.resolve("resources/data/synonyms/en.json"), "{}");
        Files.writeString(tmp.resolve("resources/data/synonyms/fr.json"), "{}");

        String prev = System.getProperty("gek.projectRoot");
        try {
            System.setProperty("gek.projectRoot", tmp.toString());
            AppPaths paths = AppPaths.resolve();
            ModelCatalog.Catalog c = ModelCatalog.inspect(paths);

            assertEquals(2, c.whisper.size());
            assertTrue(c.whisper.stream().anyMatch(i -> "base".equals(i.name) && i.complete && i.hasTokenizer
                    && i.config != null && !i.config.isEmpty()));
            assertTrue(c.whisper.stream().anyMatch(i -> "small".equals(i.name) && !i.complete));

            assertEquals(3, c.argosPairs.size());
            assertTrue(c.argosPairs.stream().anyMatch(a -> "en".equals(a.from) && "de".equals(a.to) && a.hasModel && a.hasSentencepiece));
            assertTrue(c.argosPairs.stream().anyMatch(a -> "fr".equals(a.from) && "en".equals(a.to)));
            assertTrue(c.argosPairs.stream().anyMatch(a -> "ar".equals(a.from) && "en".equals(a.to)));

            assertTrue(c.pyannote.complete);
            assertTrue(c.synonymLangs.containsAll(List.of("en", "fr")));

            String text = c.toText();
            assertTrue(text.contains("base"));
            assertTrue(text.contains("COMPLETE"));
            assertTrue(text.contains("en->de"));
            assertTrue(text.contains("Synonym langs"));
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot");
            else System.setProperty("gek.projectRoot", prev);
        }
    }
}
