package com.gek.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AssetInspector {
    private AssetInspector() {}

    public static AssetReport inspect(AppPaths paths) {
        AssetReport r = new AssetReport();
        r.projectRoot = paths.projectRoot();
        r.resourcesDir = paths.resourcesDir();
        r.modelsDir = paths.modelsDir();
        r.dataDir = paths.dataDir();
        r.settingsExists = Files.isRegularFile(paths.settingsPath());
        r.dbExists = Files.isRegularFile(paths.dbPath());
        r.notesExists = Files.isRegularFile(paths.notesPath());
        r.whisperModels = inspectWhisper(paths.whisperModelsDir());
        r.argosPackages = countSubdirs(paths.argosModelsDir().resolve("packages"));
        r.pyannoteComplete = inspectPyannote(paths.pyannoteModelsDir().resolve("speaker-diarization-community-1"));
        r.synonymFiles = countFiles(paths.synonymsDir(), ".json");
        r.logsDirExists = Files.isDirectory(paths.logsDir());
        return r;
    }

    private static List<WhisperModelStatus> inspectWhisper(Path whisperDir) {
        List<WhisperModelStatus> out = new ArrayList<>();
        if (!Files.isDirectory(whisperDir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(whisperDir, "models--Systran--faster-whisper-*")) {
            for (Path m : ds) {
                String name = m.getFileName().toString().replace("models--Systran--faster-whisper-", "");
                Path snapshots = m.resolve("snapshots");
                boolean complete = false;
                String snapshotHash = null;
                if (Files.isDirectory(snapshots)) {
                    try (DirectoryStream<Path> ss = Files.newDirectoryStream(snapshots)) {
                        for (Path s : ss) {
                            if (Files.isRegularFile(s.resolve("model.bin"))) { complete = true; snapshotHash = s.getFileName().toString(); break; }
                        }
                    } catch (IOException ignored) {}
                }
                out.add(new WhisperModelStatus(name, complete, snapshotHash, m));
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing(w -> w.name));
        return out;
    }

    private static boolean inspectPyannote(Path p) {
        return Files.isRegularFile(p.resolve("config.yaml"))
                && Files.isRegularFile(p.resolve("embedding/pytorch_model.bin"))
                && Files.isRegularFile(p.resolve("segmentation/pytorch_model.bin"))
                && Files.isRegularFile(p.resolve("plda/plda.npz"))
                && Files.isRegularFile(p.resolve("plda/xvec_transform.npz"));
    }

    private static int countSubdirs(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        int c = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (Files.isDirectory(p)) c++;
        } catch (IOException ignored) {}
        return c;
    }
    private static int countFiles(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) return 0;
        int c = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix)) c++;
        } catch (IOException ignored) {}
        return c;
    }

    public static class AssetReport {
        public Path projectRoot, resourcesDir, modelsDir, dataDir;
        public boolean settingsExists, dbExists, notesExists, logsDirExists, pyannoteComplete;
        public int argosPackages, synonymFiles;
        public List<WhisperModelStatus> whisperModels = List.of();

        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("GEK asset check\n");
            sb.append("projectRoot=").append(projectRoot).append("\n");
            sb.append("resourcesDir=").append(resourcesDir).append(" exists=").append(Files.isDirectory(resourcesDir)).append("\n");
            sb.append("settings.json=").append(settingsExists).append(" db=").append(dbExists).append(" notes=").append(notesExists).append(" logsDir=").append(logsDirExists).append("\n");
            sb.append("synonym json files=").append(synonymFiles).append("\n");
            sb.append("argos packages=").append(argosPackages).append("\n");
            sb.append("pyannote complete=").append(pyannoteComplete).append("\n");
            sb.append("whisper models:\n");
            for (WhisperModelStatus w : whisperModels) {
                sb.append("  - ").append(w.name).append(": ").append(w.complete ? "COMPLETE" : "INCOMPLETE").append(w.snapshotHash!=null?" ("+w.snapshotHash+")":"").append("\n");
            }
            return sb.toString();
        }
    }
    public static class WhisperModelStatus {
        public final String name; public final boolean complete; public final String snapshotHash; public final Path path;
        public WhisperModelStatus(String name, boolean complete, String snapshotHash, Path path) {
            this.name=name; this.complete=complete; this.snapshotHash=snapshotHash; this.path=path;
        }
    }
}
