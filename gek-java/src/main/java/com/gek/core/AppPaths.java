package com.gek.core;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AppPaths {
    private final Path projectRoot;
    private final Path resourcesDir;
    private final Path modelsDir;
    private final Path whisperModelsDir;
    private final Path argosModelsDir;
    private final Path pyannoteModelsDir;
    private final Path dataDir;
    private final Path dbPath;
    private final Path settingsPath;
    private final Path synonymsDir;
    private final Path logsDir;
    private final Path notesPath;

    private AppPaths(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.resourcesDir = this.projectRoot.resolve("resources");
        this.modelsDir = resourcesDir.resolve("models");
        this.whisperModelsDir = modelsDir.resolve("whisper");
        this.argosModelsDir = modelsDir.resolve("argos");
        this.pyannoteModelsDir = modelsDir.resolve("pyannote");
        this.dataDir = resourcesDir.resolve("data");
        this.dbPath = dataDir.resolve("history.db");
        this.settingsPath = dataDir.resolve("settings.json");
        this.synonymsDir = dataDir.resolve("synonyms");
        this.logsDir = dataDir.resolve("logs");
        this.notesPath = resourcesDir.resolve("notes.txt");
    }

    public static AppPaths resolve() {
        String override = System.getProperty("gek.projectRoot", System.getenv("GEK_PROJECT_ROOT"));
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return resolve(cwd, override);
    }

    /** Testable resolution seam: applies override or cwd-based heuristics. */
    static AppPaths resolve(Path cwd, String projectRootOverride) {
        Path root;
        if (projectRootOverride != null && !projectRootOverride.isBlank()) {
            root = Path.of(projectRootOverride);
        } else {
            Path c = cwd.toAbsolutePath().normalize();
            // support running from gek-java/ or repo root
            if (Files.exists(c.resolve("resources")) && Files.exists(c.resolve("gek-java"))) {
                root = c;
            } else if (Files.exists(c.getParent() != null ? c.getParent().resolve("resources") : c.resolve("resources"))
                    && c.getFileName() != null && "gek-java".equals(c.getFileName().toString())) {
                root = c.getParent();
            } else {
                root = c;
            }
        }
        return new AppPaths(root);
    }

    public Path projectRoot() { return projectRoot; }
    public Path resourcesDir() { return resourcesDir; }
    public Path modelsDir() { return modelsDir; }
    public Path whisperModelsDir() { return whisperModelsDir; }
    public Path argosModelsDir() { return argosModelsDir; }
    public Path pyannoteModelsDir() { return pyannoteModelsDir; }
    public Path dataDir() { return dataDir; }
    public Path dbPath() { return dbPath; }
    public Path settingsPath() { return settingsPath; }
    public Path synonymsDir() { return synonymsDir; }
    public Path logsDir() { return logsDir; }
    public Path notesPath() { return notesPath; }
}
