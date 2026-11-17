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
        Path root;
        if (override != null && !override.isBlank()) {
            root = Path.of(override);
        } else {
            Path cwd = Path.of("").toAbsolutePath().normalize();
            // support running from gek-java/ or repo root
            if (Files.exists(cwd.resolve("resources")) && Files.exists(cwd.resolve("gek-java"))) {
                root = cwd;
            } else if (Files.exists(cwd.getParent() != null ? cwd.getParent().resolve("resources") : cwd.resolve("resources"))
                    && cwd.getFileName() != null && "gek-java".equals(cwd.getFileName().toString())) {
                root = cwd.getParent();
            } else {
                root = cwd;
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
