package com.gek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppPathsMoreTest {
    @TempDir Path tmp;

    @Test
    void overrideWinsAndResolvesDerivedPaths() throws Exception {
        Path root = tmp.resolve("myroot");
        Files.createDirectories(root);
        AppPaths p = AppPaths.resolve(tmp, root.toString());
        assertEquals(root.toAbsolutePath().normalize(), p.projectRoot());
        assertEquals(p.projectRoot().resolve("resources"), p.resourcesDir());
        assertEquals(p.resourcesDir().resolve("models"), p.modelsDir());
        assertEquals(p.modelsDir().resolve("whisper"), p.whisperModelsDir());
        assertEquals(p.modelsDir().resolve("argos"), p.argosModelsDir());
        assertEquals(p.modelsDir().resolve("pyannote"), p.pyannoteModelsDir());
        assertEquals(p.resourcesDir().resolve("data"), p.dataDir());
        assertEquals(p.dataDir().resolve("history.db"), p.dbPath());
        assertEquals(p.dataDir().resolve("settings.json"), p.settingsPath());
        assertEquals(p.dataDir().resolve("synonyms"), p.synonymsDir());
        assertEquals(p.dataDir().resolve("logs"), p.logsDir());
        assertEquals(p.resourcesDir().resolve("notes.txt"), p.notesPath());
    }

    @Test
    void detectsRepoRootWhenRunFromNestedGekJavaDir() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve("resources"));
        Files.createDirectories(repo.resolve("gek-java"));
        Path cwd = repo.resolve("gek-java");
        AppPaths p = AppPaths.resolve(cwd, null);
        assertEquals(repo.toAbsolutePath().normalize(), p.projectRoot());
    }

    @Test
    void blankOverrideFallsBackToCwdHeuristics() throws Exception {
        Path repo = tmp.resolve("repo2");
        Files.createDirectories(repo.resolve("resources"));
        Files.createDirectories(repo.resolve("gek-java"));
        AppPaths p = AppPaths.resolve(repo, "   ");
        assertEquals(repo.toAbsolutePath().normalize(), p.projectRoot());
    }
}
