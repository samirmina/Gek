package com.gek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AssetInspectorPathTest {
    @TempDir Path tmp;

    @Test
    void inspectHandlesEmptyProjectTree() throws Exception {
        Files.createDirectories(tmp.resolve("resources"));
        Files.createDirectories(tmp.resolve("gek-java"));
        String prev = System.getProperty("gek.projectRoot");
        try {
            System.setProperty("gek.projectRoot", tmp.toString());
            AppPaths paths = AppPaths.resolve();
            AssetInspector.AssetReport r = AssetInspector.inspect(paths);
            assertNotNull(r.toText());
            assertFalse(r.settingsExists);
            assertFalse(r.dbExists);
            assertFalse(r.notesExists);
            assertFalse(r.pyannoteComplete);
            assertEquals(0, r.argosPackages);
            assertEquals(0, r.synonymFiles);
            assertNotNull(r.whisperModels);
            assertTrue(r.whisperModels.isEmpty());
            String text = r.toText();
            assertTrue(text.contains("GEK asset check"));
            assertTrue(text.contains("whisper models:"));
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void inspectCountsNestedAssetsAndNotesLogs() throws Exception {
        Files.createDirectories(tmp.resolve("resources/data/synonyms"));
        Files.writeString(tmp.resolve("resources/data/synonyms/en.json"), "{}");
        Files.writeString(tmp.resolve("resources/data/synonyms/uk.json"), "{}");
        Files.writeString(tmp.resolve("resources/data/settings.json"), "{}");
        Files.createDirectories(tmp.resolve("resources/data/logs"));
        Files.writeString(tmp.resolve("resources/notes.txt"), "n");
        Files.createDirectories(tmp.resolve("resources/models/argos/packages/p1"));
        Files.createDirectories(tmp.resolve("resources/models/argos/packages/p2"));
        Files.createDirectories(tmp.resolve("resources/models/whisper/models--Systran--faster-whisper-tiny/snapshots/s1"));
        Files.writeString(tmp.resolve("resources/models/whisper/models--Systran--faster-whisper-tiny/snapshots/s1/model.bin"), "x");
        String prev = System.getProperty("gek.projectRoot");
        try {
            System.setProperty("gek.projectRoot", tmp.toString());
            AppPaths paths = AppPaths.resolve();
            AssetInspector.AssetReport r = AssetInspector.inspect(paths);
            assertTrue(r.settingsExists);
            assertTrue(r.notesExists);
            assertTrue(r.logsDirExists);
            assertEquals(2, r.synonymFiles);
            assertEquals(2, r.argosPackages);
            assertEquals(1, r.whisperModels.size());
            assertTrue(r.whisperModels.get(0).complete);
            assertTrue(r.toText().contains("tiny: COMPLETE"));
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }
}
