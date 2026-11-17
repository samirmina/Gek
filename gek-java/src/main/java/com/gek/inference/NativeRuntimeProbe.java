package com.gek.inference;

import com.gek.core.AppPaths;
import com.gek.core.ModelCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class NativeRuntimeProbe {
    private NativeRuntimeProbe() {}

    public record SttRuntime(Optional<String> binary, Optional<Path> model, String reason) {
        public boolean usable() { return binary.isPresent() && model.isPresent(); }
    }

    public static boolean onPath(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-lc", "command -v " + cmd + " >/dev/null 2>&1").start();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    public static Optional<String> findOnPath(String... cmds) {
        for (String c : cmds) if (onPath(c)) return Optional.of(c);
        return Optional.empty();
    }

    public static Optional<String> findWhisperCppBinary(AppPaths paths) {
        String[] names = {"whisper-cli", "whisper-cpp", "main"};
        for (String n : names) {
            if (onPath(n)) return Optional.of(n);
        }
        if (paths != null) {
            Path root = paths.projectRoot();
            String[] rels = {
                    "gek-java/native/bin/whisper-cli",
                    "gek-java/native/whisper.cpp/build/bin/whisper-cli",
                    "gek-java/native/whisper.cpp/build/bin/main",
                    "gek-java/native/whisper.cpp/main",
                    "native/bin/whisper-cli",
                    "native/whisper.cpp/build/bin/whisper-cli"
            };
            for (String r : rels) {
                Path p = root.resolve(r);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p.toAbsolutePath().toString());
            }
        }
        return Optional.empty();
    }

    /** GGML/whisper.cpp models (*.bin) that are not CTranslate2 faster-whisper snapshots. */
    public static List<Path> findGgmlWhisperModels(AppPaths paths) {
        List<Path> out = new ArrayList<>();
        if (paths == null) return out;
        List<Path> roots = List.of(
                paths.modelsDir().resolve("whisper-ggml"),
                paths.modelsDir().resolve("whisper").resolve("ggml"),
                paths.projectRoot().resolve("resources").resolve("models").resolve("whisper-ggml"),
                paths.projectRoot().resolve("gek-java").resolve("native").resolve("models")
        );
        for (Path r : roots) addGgmlBins(r, out);
        // de-duplicate by filename preferring shortest/real paths
        java.util.Map<String, Path> byName = new java.util.LinkedHashMap<>();
        for (Path p : out) {
            String key = p.getFileName().toString().toLowerCase(Locale.ROOT);
            Path prev = byName.get(key);
            if (prev == null) { byName.put(key, p); continue; }
            try {
                Path a = prev.toRealPath();
                Path b = p.toRealPath();
                if (a.equals(b)) continue;
            } catch (Exception ignored) {}
            // prefer path under resources/models/whisper-ggml or shorter string
            String ps = prev.toString(); String cs = p.toString();
            boolean cPref = cs.contains("/resources/models/whisper-ggml/") && !ps.contains("/resources/models/whisper-ggml/");
            if (cPref || cs.length() < ps.length()) byName.put(key, p);
        }
        List<Path> dedup = new ArrayList<>(byName.values());
        dedup.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return dedup;
    }

    private static void addGgmlBins(Path dir, List<Path> out) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var ds = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(n.startsWith("ggml-") || n.contains("whisper"))) continue;
                if (!looksLikeValidGgml(p, n)) continue;
                out.add(p.toAbsolutePath().normalize());
            }
        } catch (Exception ignored) {}
    }

    private static boolean looksLikeValidGgml(Path p, String nameLower) {
        try {
            long size = Files.size(p);
            // reject tiny/corrupt files (e.g. partial downloads). Known minima for common whisper ggml models.
            if (nameLower.contains("tiny")) return size >= 70L * 1024 * 1024;
            if (nameLower.contains("base")) return size >= 130L * 1024 * 1024;
            if (nameLower.contains("small")) return size >= 430L * 1024 * 1024;
            if (nameLower.contains("medium")) return size >= 1400L * 1024 * 1024;
            if (nameLower.contains("large-v3-turbo")) return size >= 1400L * 1024 * 1024;
            if (nameLower.contains("large")) return size >= 2800L * 1024 * 1024;
            return size >= 50L * 1024 * 1024;
        } catch (Exception e) {
            return false;
        }
    }

    public static SttRuntime resolveSttRuntime(AppPaths paths, String requestedModel) {
        return resolveProcessSttRuntime(paths, requestedModel);
    }

    public static SttRuntime resolveProcessSttRuntime(AppPaths paths, String requestedModel) {
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        String wanted = requestedModel == null ? "small" : requestedModel.toLowerCase(Locale.ROOT);
        String complete = c.whisper.stream().filter(w -> w.complete).map(w -> w.name).reduce((a,b)->a+","+b).orElse("none");
        boolean hasWanted = c.whisper.stream().anyMatch(w -> w.name.equalsIgnoreCase(wanted) && w.complete);
        Optional<String> cpp = findWhisperCppBinary(paths);
        List<Path> ggml = findGgmlWhisperModels(paths);
        Optional<Path> model = pickGgmlModel(ggml, wanted);

        StringBuilder sb = new StringBuilder();
        sb.append("Native Java STT runtime (Option C, no Python) ");
        if (cpp.isPresent() && model.isPresent()) {
            sb.append("READY via whisper.cpp backend. binary=").append(cpp.get())
              .append(" model=").append(model.get()).append(". ");
            String mn = model.get().getFileName().toString().toLowerCase(Locale.ROOT);
            boolean match = wanted.isEmpty() || mn.contains(wanted.toLowerCase(Locale.ROOT));
            sb.append("Requested model '").append(requestedModel).append("' -> GGML model '")
              .append(model.get().getFileName()).append("'").append(match?"":" (fallback; no exact GGML for requested name)").append(". ");
        } else {
            sb.append("NOT READY for transcription. ");
        }
        sb.append("Local faster-whisper/CTranslate2 snapshots complete=[").append(complete).append("], requested=").append(requestedModel)
          .append(hasWanted?"(present, CTranslate2 format)":"(missing)").append(". ");
        sb.append("whisper.cpp binary=").append(cpp.orElse("not found")).append("; ");
        sb.append("GGML whisper models=").append(ggml.isEmpty()?"none":ggml.stream().map(p->p.getFileName().toString()).reduce((a,b)->a+","+b).orElse("none")).append(". ");
        if (!(cpp.isPresent() && model.isPresent())) {
            if (ggml.isEmpty()) {
                sb.append("Blocker: no GGML Whisper model found for whisper.cpp. Local STT assets are faster-whisper/CTranslate2 snapshots (not loadable by whisper.cpp/GGML), and no in-process Java CTranslate2 Whisper runtime is installed. ");
                sb.append("To run native (no-Python) STT in WSL2: provide/build whisper.cpp and place a matching GGML model (e.g. ggml-small.bin) in resources/models/whisper-ggml/ or gek-java/native/models/. ");
            } else {
                sb.append("Blocker: whisper.cpp binary not found (build or place on PATH/gek-java/native/bin). GGML model(s) are present. ");
            }
            sb.append("Temporary fallback only if explicitly enabled: --use-sidecar with Python ML deps (faster-whisper).");
        }
        return new SttRuntime(cpp, model, sb.toString());
    }

    public static Optional<String> findWhisperShimLibrary(AppPaths paths) {
        if (paths == null) return Optional.empty();
        String[] rels = {
                "gek-java/native/libgek_whisper_shim.so",
                "gek-java/native/bin/libgek_whisper_shim.so",
                "gek-java/native/lib/libgek_whisper_shim.so",
                "native/libgek_whisper_shim.so"
        };
        for (String r : rels) {
            Path p = paths.projectRoot().resolve(r);
            if (Files.isRegularFile(p)) return Optional.of(p.toAbsolutePath().toString());
        }
        // common library name on path
        try {
            java.io.File f = new java.io.File("libgek_whisper_shim.so");
            if (f.isFile()) return Optional.of(f.getAbsolutePath());
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    /** In-JVM STT via libwhisper (JNA shim). binary() carries shim library path, model() GGML model. */
    public static SttRuntime resolveInJvmSttRuntime(AppPaths paths, String requestedModel) {
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        String wanted = requestedModel == null ? "small" : requestedModel.toLowerCase(Locale.ROOT);
        String complete = c.whisper.stream().filter(w -> w.complete).map(w -> w.name).reduce((a,b)->a+","+b).orElse("none");
        boolean hasWanted = c.whisper.stream().anyMatch(w -> w.name.equalsIgnoreCase(wanted) && w.complete);
        Optional<String> shim = findWhisperShimLibrary(paths);
        List<Path> ggml = findGgmlWhisperModels(paths);
        Optional<Path> model = pickGgmlModel(ggml, wanted);

        StringBuilder sb = new StringBuilder();
        sb.append("Native Java in-JVM STT runtime (Option C, no Python, no subprocess) ");
        if (shim.isPresent() && model.isPresent()) {
            sb.append("READY via libwhisper JNA shim. shim=").append(shim.get())
              .append(" model=").append(model.get()).append(". ");
            String mn = model.get().getFileName().toString().toLowerCase(Locale.ROOT);
            boolean match = wanted.isEmpty() || mn.contains(wanted.toLowerCase(Locale.ROOT));
            sb.append("Requested model '").append(requestedModel).append("' -> GGML model '")
              .append(model.get().getFileName()).append("'").append(match?"":" (fallback; no exact GGML for requested name)").append(". ");
        } else {
            sb.append("NOT READY. ");
        }
        sb.append("Local faster-whisper/CTranslate2 snapshots complete=[").append(complete).append("], requested=").append(requestedModel)
          .append(hasWanted?"(present, CTranslate2 format)":"(missing)").append(". ");
        sb.append("whisper shim=").append(shim.orElse("not found (expected gek-java/native/libgek_whisper_shim.so)")).append("; ");
        sb.append("GGML whisper models=").append(ggml.isEmpty()?"none":ggml.stream().map(p->p.getFileName().toString()).reduce((a,b)->a+","+b).orElse("none")).append(". ");
        if (!(shim.isPresent() && model.isPresent())) {
            if (ggml.isEmpty()) {
                sb.append("Blocker: no GGML Whisper model found. ");
            }
            if (shim.isEmpty()) {
                sb.append("Blocker: libwhisper JNA shim/native library not found (in-process STT requires shim + libwhisper). ");
            }
        }
        return new SttRuntime(shim, model, sb.toString());
    }

    private static Optional<Path> pickGgmlModel(List<Path> ggml, String wanted) {
        if (ggml == null || ggml.isEmpty()) return Optional.empty();
        String w = wanted == null ? "" : wanted.toLowerCase(Locale.ROOT).trim();
        // exact/preferred names
        String[] exact = {
                "ggml-" + w + ".bin", "ggml-" + w + ".en.bin",
                "ggml-" + w.replace("_","-") + ".bin", "ggml-" + w.replace("-","_") + ".bin"
        };
        for (String e : exact) {
            for (Path p : ggml) { if (p.getFileName().toString().equalsIgnoreCase(e)) return Optional.of(p); }
        }
        // contains wanted token
        if (!w.isEmpty()) {
            for (Path p : ggml) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.contains("-" + w + ".") || n.contains("-" + w + "-") || n.contains("." + w + ".") || n.endsWith("-" + w) || n.contains(w)) return Optional.of(p);
            }
        }
        // aliases: large-v3/large
        if (w.contains("large")) {
            for (String key : List.of("large-v3-turbo","large-v3","large-v2","large")) {
                if (!w.contains(key) && !("large".equals(w) && key.startsWith("large"))) {
                    // still try best large match
                }
                for (Path p : ggml) { String n=p.getFileName().toString().toLowerCase(Locale.ROOT); if (n.contains(key)) return Optional.of(p); }
            }
        }
        if (w.contains("medium")) {
            for (Path p : ggml) { String n=p.getFileName().toString().toLowerCase(Locale.ROOT); if (n.contains("medium")) return Optional.of(p); }
        }
        // fallback: prefer closest/best available when requested missing (base > small > tiny)
        List<String> pref = List.of("base", "small", "medium", "large-v3", "large", "tiny");
        for (String key : pref) {
            for (Path p : ggml) { String n=p.getFileName().toString().toLowerCase(Locale.ROOT); if (n.contains(key)) return Optional.of(p); }
        }
        return Optional.of(ggml.get(0));
    }

    public static String describeSttStatus(AppPaths paths, String requestedModel) {
        return resolveSttRuntime(paths, requestedModel).reason();
    }

    public record TranslationRuntime(Optional<String> python, Optional<Path> script, Optional<String> shimLibrary,
                                      Optional<String> pythonHome, Optional<String> sitePackages, String reason, boolean inJvm) {
        public TranslationRuntime(Optional<String> python, Optional<Path> script, String reason) {
            this(python, script, Optional.empty(), Optional.empty(), Optional.empty(), reason, false);
        }
        public static TranslationRuntime inJvm(Optional<String> shimLibrary, Optional<String> pythonHome,
                                               Optional<String> sitePackages, String reason) {
            return new TranslationRuntime(Optional.empty(), Optional.empty(), shimLibrary, pythonHome, sitePackages, reason, true);
        }
        public boolean usable() { return inJvm ? (shimLibrary.isPresent() && pythonHome.isPresent() && sitePackages.isPresent()) : (python.isPresent() && script.isPresent()); }
    }

    public static Optional<Path> findArgosPackage(AppPaths paths, String from, String to) {
        if (paths == null || from == null || to == null) return Optional.empty();
        String f = from.toLowerCase(Locale.ROOT).trim();
        String t = to.toLowerCase(Locale.ROOT).trim();
        if (f.isEmpty() || t.isEmpty() || "auto".equals(f) || "detected".equals(f)) return Optional.empty();
        Path dir = paths.argosModelsDir().resolve("packages");
        if (!Files.isDirectory(dir)) return Optional.empty();
        ObjectMapper om = new ObjectMapper();
        try (var ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                Path model = p.resolve("model").resolve("model.bin");
                if (!Files.isRegularFile(model)) continue;
                // metadata
                Path meta = p.resolve("metadata.json");
                boolean langMatch = false;
                if (Files.isRegularFile(meta)) {
                    try {
                        JsonNode n = om.readTree(meta.toFile());
                        String mf = text(n, "from_code", "from", "source", "source_code");
                        String mt = text(n, "to_code", "to", "target", "target_code");
                        if (f.equalsIgnoreCase(mf) && t.equalsIgnoreCase(mt)) langMatch = true;
                    } catch (Exception ignored) {}
                }
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!langMatch) {
                    langMatch = name.equals(f + "_" + t) || name.equals("translate-" + f + "_" + t) || name.equals("translate_" + f + "_" + t)
                            || name.startsWith("translate-" + f + "_" + t + "-") || name.startsWith("translate_" + f + "_" + t + "-")
                            || name.contains("-" + f + "_" + t + "-") || name.endsWith("-" + f + "_" + t);
                }
                if (langMatch) {
                    // require a tokenizer the local helper can load (sentencepiece proto); BPE-only packages are unusable here
                    if (Files.isRegularFile(p.resolve("sentencepiece.model"))
                            || Files.isRegularFile(p.resolve("source.spm"))
                            || Files.isRegularFile(p.resolve("spm.model"))) {
                        return Optional.of(p);
                    }
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static String text(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull() && v.isTextual()) return v.asText();
            // nested common shapes
            if ("from_code".equals(k) || "from".equals(k)) {
                JsonNode from = n.get("from"); if (from!=null && from.isObject()) { JsonNode c=from.get("code"); if(c!=null&&c.isTextual()) return c.asText(); }
            }
            if ("to_code".equals(k) || "to".equals(k)) {
                JsonNode to = n.get("to"); if (to!=null && to.isObject()) { JsonNode c=to.get("code"); if(c!=null&&c.isTextual()) return c.asText(); }
            }
        }
        return null;
    }

    public static Optional<String> findVenvPython(AppPaths paths) {
        if (paths == null) return Optional.empty();
        List<Path> cands = List.of(
                paths.projectRoot().resolve("gek-java/native/.venv/bin/python"),
                paths.projectRoot().resolve("gek-java/native/.venv/bin/python3"),
                paths.projectRoot().resolve("gek-java/.venv/bin/python"),
                paths.projectRoot().resolve(".venv/bin/python")
        );
        for (Path p : cands) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p.toAbsolutePath().toString());
        }
        // fallback: system python3 only if it can import ctranslate2+sentencepiece (checked lazily by caller) - skip
        return Optional.empty();
    }

    public static Optional<Path> findTranslationScript(AppPaths paths) {
        if (paths == null) return Optional.empty();
        List<Path> cands = List.of(
                paths.projectRoot().resolve("gek-java/native/translate_argos.py"),
                paths.projectRoot().resolve("gek-java/bridge/translate_argos.py"),
                paths.projectRoot().resolve("native/translate_argos.py")
        );
        for (Path p : cands) if (Files.isRegularFile(p)) return Optional.of(p);
        return Optional.empty();
    }

    public static Optional<String> findTranslationShimLibrary(AppPaths paths) {
        if (paths == null) return Optional.empty();
        String[] rels = {
                "gek-java/native/libgek_translate_shim.so",
                "gek-java/native/bin/libgek_translate_shim.so",
                "native/libgek_translate_shim.so"
        };
        for (String r : rels) {
            Path p = paths.projectRoot().resolve(r);
            if (Files.isRegularFile(p)) return Optional.of(p.toAbsolutePath().toString());
        }
        return Optional.empty();
    }

    public static Optional<String> findVenvHome(AppPaths paths) {
        if (paths == null) return Optional.empty();
        List<Path> cands = List.of(
                paths.projectRoot().resolve("gek-java/native/.venv"),
                paths.projectRoot().resolve("gek-java/.venv"),
                paths.projectRoot().resolve(".venv")
        );
        for (Path p : cands) {
            if (Files.isDirectory(p.resolve("lib"))) return Optional.of(p.toAbsolutePath().toString());
        }
        return Optional.empty();
    }

    public static Optional<String> findVenvSitePackages(AppPaths paths) {
        if (paths == null) return Optional.empty();
        Path base = paths.projectRoot().resolve("gek-java/native/.venv/lib");
        if (!Files.isDirectory(base)) return Optional.empty();
        try (var ds = Files.newDirectoryStream(base)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!n.startsWith("python")) continue;
                Path sp = p.resolve("site-packages");
                if (Files.isDirectory(sp)) return Optional.of(sp.toAbsolutePath().toString());
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    public static TranslationRuntime resolveInJvmTranslationRuntime(AppPaths paths) {
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        java.util.TreeSet<String> allPairs = new java.util.TreeSet<>();
        java.util.TreeSet<String> usablePairs = new java.util.TreeSet<>();
        java.util.TreeSet<String> bpePairs = new java.util.TreeSet<>();
        if (c.argosPairs != null) {
            for (ModelCatalog.ArgosPair p : c.argosPairs) {
                if (p == null || !p.hasModel) continue;
                String pair = (p.from == null ? "?" : p.from) + "->" + (p.to == null ? "?" : p.to);
                allPairs.add(pair);
                Path dir = paths != null ? paths.argosModelsDir().resolve("packages").resolve(p.dirName == null ? "" : p.dirName) : null;
                boolean hasSpm = dir != null && (Files.isRegularFile(dir.resolve("sentencepiece.model"))
                        || Files.isRegularFile(dir.resolve("source.spm"))
                        || Files.isRegularFile(dir.resolve("spm.model")));
                boolean hasBpe = dir != null && Files.isRegularFile(dir.resolve("bpe.model"));
                if (hasSpm) usablePairs.add(pair); else if (hasBpe) bpePairs.add(pair);
            }
        }
        String pairs = String.join(",", allPairs);
        String usable = String.join(",", usablePairs);
        String bpe = String.join(",", bpePairs);
        Optional<String> shim = findTranslationShimLibrary(paths);
        Optional<String> home = findVenvHome(paths);
        Optional<String> site = findVenvSitePackages(paths);
        StringBuilder sb = new StringBuilder();
        if (shim.isPresent() && home.isPresent() && site.isPresent()) {
            sb.append("Native Java in-JVM translation runtime (Option C, embedded ctranslate2+SentencePiece, no subprocess/Python process) READY. ");
            sb.append("shim=").append(shim.get()).append(" python_home=").append(home.get()).append(" site=").append(site.get()).append(". ");
        } else {
            sb.append("Native Java in-JVM translation runtime (Option C) NOT READY. ");
            sb.append("shim=").append(shim.orElse("not found (expected gek-java/native/libgek_translate_shim.so)"));
            sb.append("; venv_home=").append(home.orElse("not found"));
            sb.append("; site_packages=").append(site.orElse("not found")).append(". ");
        }
        sb.append("Usable pairs (SentencePiece)=").append(usable.isEmpty() ? "none" : usable).append(". ");
        if (!bpe.isEmpty()) sb.append("Present but unusable by helper (BPE-only tokenizer, not supported here)=").append(bpe).append(". ");
        sb.append("All packages with model=[").append(pairs.isEmpty() ? "none" : pairs).append("].");
        return TranslationRuntime.inJvm(shim, home, site, sb.toString());
    }

    public static TranslationRuntime resolveTranslationRuntime(AppPaths paths) {
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        java.util.TreeSet<String> allPairs = new java.util.TreeSet<>();
        java.util.TreeSet<String> usablePairs = new java.util.TreeSet<>();
        java.util.TreeSet<String> bpePairs = new java.util.TreeSet<>();
        if (c.argosPairs != null) {
            for (ModelCatalog.ArgosPair p : c.argosPairs) {
                if (p == null || !p.hasModel) continue;
                String pair = (p.from == null ? "?" : p.from) + "->" + (p.to == null ? "?" : p.to);
                allPairs.add(pair);
                // heuristic: packages with usable sentencepiece tokenizer are supported by helper
                Path dir = paths != null ? paths.argosModelsDir().resolve("packages").resolve(p.dirName == null ? "" : p.dirName) : null;
                boolean hasSpm = dir != null && (Files.isRegularFile(dir.resolve("sentencepiece.model"))
                        || Files.isRegularFile(dir.resolve("source.spm"))
                        || Files.isRegularFile(dir.resolve("spm.model")));
                boolean hasBpe = dir != null && Files.isRegularFile(dir.resolve("bpe.model"));
                if (hasSpm) usablePairs.add(pair); else if (hasBpe) bpePairs.add(pair);
            }
        }
        String pairs = String.join(",", allPairs);
        String usable = String.join(",", usablePairs);
        String bpe = String.join(",", bpePairs);
        Optional<String> py = findVenvPython(paths);
        Optional<Path> script = findTranslationScript(paths);
        StringBuilder sb = new StringBuilder();
        if (py.isPresent() && script.isPresent()) {
            sb.append("Native Java translation runtime (Option C, no argostranslate sidecar server) READY via local helper using ctranslate2+SentencePiece on Argos packages. ");
            sb.append("python=").append(py.get()).append(" script=").append(script.get()).append(". ");
        } else {
            sb.append("Native Java translation runtime (Option C) NOT READY. ");
            sb.append("helper_python=").append(py.orElse("not found (expected gek-java/native/.venv with ctranslate2+sentencepiece)"))
              .append("; helper_script=").append(script.map(Path::toString).orElse("not found (expected gek-java/native/translate_argos.py)")).append(". ");
            sb.append("Blocker: no in-process Java CTranslate2/SentencePiece binding integrated; Java orchestrator needs the local ctranslate2+sentencepiece helper and venv. ");
            sb.append("Temporary fallback only if explicitly enabled: --use-sidecar with Python ML deps if installed. ");
        }
        sb.append("Usable pairs (SentencePiece)=").append(usable.isEmpty() ? "none" : usable).append(". ");
        if (!bpe.isEmpty()) sb.append("Present but unusable by helper (BPE-only tokenizer, not supported here)=").append(bpe).append(". ");
        sb.append("All packages with model=[").append(pairs.isEmpty() ? "none" : pairs).append("].");
        return new TranslationRuntime(py, script, sb.toString());
    }

    public static Optional<String> findCtranslate2Cli() {
        return findOnPath("ct2-translator", "ctranslate2");
    }

    public static String describeTranslationStatus(AppPaths paths) {
        return resolveTranslationRuntime(paths).reason();
    }

    public record DiarizationRuntime(boolean onnxModelsPresent, boolean onnxRuntimePresent, boolean pipelineIntegrated, String reason) {
        public boolean usable() { return onnxModelsPresent && onnxRuntimePresent && pipelineIntegrated; }
    }

    public static DiarizationRuntime resolveDiarizationRuntime(AppPaths paths) {
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        boolean torchAssets = c.pyannote != null && c.pyannote.complete;
        Path pyannoteDir = resolvePyannoteAssetDir(paths);
        boolean onnxModels = false;
        com.gek.inference.diarization.OnnxDiarizationModels.Paths m = null;
        if (pyannoteDir != null) {
            m = com.gek.inference.diarization.OnnxDiarizationModels.resolve(pyannoteDir);
            onnxModels = m != null && m.ready();
        }
        boolean onnxRt = classPresent("ai.onnxruntime.OrtEnvironment") || classPresent("ai.onnxruntime.OnnxTensor");
        boolean pipeline = true; // pure-Java ORT pipeline classes integrated (reflection-based)
        boolean plda = pyannoteDir != null
                && Files.isRegularFile(pyannoteDir.resolve("plda").resolve("plda.npz"))
                && Files.isRegularFile(pyannoteDir.resolve("plda").resolve("xvec_transform.npz"))
                && Files.isRegularFile(pyannoteDir.resolve("config.yaml"));
        StringBuilder sb = new StringBuilder();
        if (onnxModels && onnxRt && pipeline && plda) {
            sb.append("Native Java diarization runtime (Option C) READY (ONNX Runtime + Java pipeline + PLDA/config present). ");
        } else {
            sb.append("Native Java diarization runtime (Option C) NOT READY. ");
        }
        sb.append("asset_dir=").append(pyannoteDir == null ? "not found" : pyannoteDir.toString()).append(". ");
        sb.append("Local pyannote assets: torch=").append(torchAssets ? "present" : "incomplete/missing")
          .append(", config/plda=").append(plda ? "present" : "missing").append(". ");
        sb.append("ONNX diarization models: ").append(onnxModels ? ("present (seg=" + (m!=null?m.segmentation():"?") + ", emb=" + (m!=null?m.embedding():"?") + ")") : "not found (expected segmentation.onnx + embedding.onnx under <pyannote>/onnx/)").append("; ");
        sb.append("Java ONNX Runtime: ").append(onnxRt ? "present" : "not present (add com.microsoft.onnxruntime:onnxruntime on classpath)").append("; ");
        sb.append("Java ORT diarization pipeline: ").append(pipeline ? "integrated" : "missing").append(". ");
        if (!(onnxModels && onnxRt && pipeline && plda)) {
            if (!plda) sb.append("Blocker: missing config.yaml/plda assets in resolved pyannote dir. ");
            if (!onnxModels) sb.append("Blocker: needs compatible ONNX exports (segmentation.onnx, embedding.onnx) for local checkpoints. ");
            if (!onnxRt) sb.append("Blocker: onnxruntime not on classpath. ");
            sb.append("WSL2-feasible: place exports in <pyannote>/onnx/ and run --diarize-file (pure-Java pre/post/PLDA using local config/plda). ");
            sb.append("Temporary fallback only if explicitly enabled: --use-sidecar with Python ML deps (torch/pyannote.audio) if installed.");
        } else {
            sb.append("In-JVM diarization available via --diarize-file.");
        }
        return new DiarizationRuntime(onnxModels, onnxRt, pipeline, sb.toString());
    }

    public static Path resolvePyannoteAssetDir(AppPaths paths) {
        if (paths == null) return null;
        Path base = paths.modelsDir().resolve("pyannote");
        // preferred: speaker-diarization-community-1 (has config/plda)
        Path comm = base.resolve("speaker-diarization-community-1");
        if (Files.isDirectory(comm)) return comm;
        if (Files.isDirectory(base)) return base;
        return null;
    }

    public static String describeDiarizationStatus(AppPaths paths) {
        return resolveDiarizationRuntime(paths).reason();
    }

    private static boolean classPresent(String name) {
        try { Class.forName(name, false, NativeRuntimeProbe.class.getClassLoader()); return true; } catch (Throwable t) { return false; }
    }
}
