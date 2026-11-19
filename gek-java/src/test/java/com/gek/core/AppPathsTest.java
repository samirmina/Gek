package com.gek.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppPathsTest {
    @TempDir Path tmp;

    @Test
    void resolveUsesOverrideProperty() throws Exception {
        Files.createDirectories(tmp.resolve("resources/models"));
        String prev = System.getProperty("gek.projectRoot");
        try {
            System.setProperty("gek.projectRoot", tmp.toString());
            AppPaths paths = AppPaths.resolve();
            assertEquals(tmp.toAbsolutePath().normalize(), paths.projectRoot());
            assertEquals(tmp.resolve("resources").toAbsolutePath().normalize(), paths.resourcesDir());
            assertTrue(paths.modelsDir().endsWith(Path.of("resources", "models")));
            assertTrue(paths.whisperModelsDir().endsWith(Path.of("models", "whisper")));
            assertTrue(paths.argosModelsDir().endsWith(Path.of("models", "argos")));
            assertTrue(paths.pyannoteModelsDir().endsWith(Path.of("models", "pyannote")));
            assertTrue(paths.dataDir().endsWith(Path.of("resources", "data")));
            assertTrue(paths.dbPath().endsWith(Path.of("history.db")));
            assertTrue(paths.settingsPath().endsWith(Path.of("settings.json")));
            assertTrue(paths.synonymsDir().endsWith(Path.of("synonyms")));
            assertTrue(paths.logsDir().endsWith(Path.of("logs")));
            assertTrue(paths.notesPath().endsWith(Path.of("notes.txt")));
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void overrideNormalizesRelativePath() {
        String prev = System.getProperty("gek.projectRoot");
        try {
            System.setProperty("gek.projectRoot", "some/../relative-root");
            AppPaths paths = AppPaths.resolve();
            assertEquals(Path.of("relative-root").toAbsolutePath().normalize(), paths.projectRoot());
            assertTrue(paths.resourcesDir().startsWith(paths.projectRoot()));
            assertEquals(paths.projectRoot().resolve("resources/data/history.db").normalize(), paths.dbPath());
        } finally {
            if (prev == null) System.clearProperty("gek.projectRoot"); else System.setProperty("gek.projectRoot", prev);
        }
    }

    @Test
    void resolveFromCwdSupportsRepoRootAndGekJavaDir() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve("resources"));
        Files.createDirectories(repo.resolve("gek-java"));

        AppPaths fromRoot = AppPaths.resolve(repo, null);
        assertEquals(repo.toAbsolutePath().normalize(), fromRoot.projectRoot());

        AppPaths fromGekJava = AppPaths.resolve(repo.resolve("gek-java"), null);
        assertEquals(repo.toAbsolutePath().normalize(), fromGekJava.projectRoot());

        Path other = tmp.resolve("other");
        Files.createDirectories(other);
        AppPaths fallback = AppPaths.resolve(other, null);
        assertEquals(other.toAbsolutePath().normalize(), fallback.projectRoot());
    }

    @Test
    void resolveFromCwdOverrideWins() throws Exception {
        Path cwd = tmp.resolve("cwd");
        Files.createDirectories(cwd.resolve("resources"));
        Files.createDirectories(cwd.resolve("gek-java"));
        Path forced = tmp.resolve("forced");
        AppPaths p = AppPaths.resolve(cwd, forced.toString());
        assertEquals(forced.toAbsolutePath().normalize(), p.projectRoot());
        AppPaths blank = AppPaths.resolve(cwd, "   ");
        assertEquals(cwd.toAbsolutePath().normalize(), blank.projectRoot());
    }
}
