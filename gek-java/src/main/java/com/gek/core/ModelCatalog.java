package com.gek.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Pure-Java inventory of locally available Whisper/Argos/Pyannote assets (no Python deps). */
public final class ModelCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelCatalog() {}

    public static Catalog inspect(AppPaths paths) {
        Catalog c = new Catalog();
        c.whisper = whisperModels(paths.whisperModelsDir());
        c.argosPairs = argosPairs(paths.argosModelsDir().resolve("packages"));
        c.pyannote = pyannote(paths.pyannoteModelsDir().resolve("speaker-diarization-community-1"));
        c.synonymLangs = synonymLangs(paths.synonymsDir());
        return c;
    }

    public static List<WhisperInfo> whisperModels(Path whisperDir) {
        List<WhisperInfo> out = new ArrayList<>();
        if (!Files.isDirectory(whisperDir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(whisperDir, "models--Systran--faster-whisper-*")) {
            for (Path m : ds) {
                String name = m.getFileName().toString().replace("models--Systran--faster-whisper-", "");
                Path snapshots = m.resolve("snapshots");
                boolean complete = false; String hash = null; Path snap = null;
                if (Files.isDirectory(snapshots)) {
                    try (DirectoryStream<Path> ss = Files.newDirectoryStream(snapshots)) {
                        for (Path s : ss) {
                            if (Files.isRegularFile(s.resolve("model.bin"))) { complete = true; hash = s.getFileName().toString(); snap = s; break; }
                        }
                    } catch (IOException ignored) {}
                }
                Map<String, Object> cfg = new LinkedHashMap<>();
                boolean hasTokenizer = false;
                if (snap != null) {
                    cfg = readFlatJson(snap.resolve("config.json"));
                    hasTokenizer = Files.isRegularFile(snap.resolve("tokenizer.json")) || Files.isRegularFile(snap.resolve("vocabulary.txt"));
                }
                out.add(new WhisperInfo(name, complete, hash, snap, hasTokenizer, cfg));
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing(w -> w.name));
        return out;
    }

    public static List<ArgosPair> argosPairs(Path packagesDir) {
        List<ArgosPair> out = new ArrayList<>();
        if (!Files.isDirectory(packagesDir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                Path meta = p.resolve("metadata.json");
                String from = null, to = null, fromName = null, toName = null, ver = null;
                if (Files.isRegularFile(meta)) {
                    try {
                        JsonNode n = MAPPER.readTree(meta.toFile());
                        from = text(n, "from_code"); to = text(n, "to_code");
                        fromName = text(n, "from_name"); toName = text(n, "to_name");
                        ver = text(n, "package_version");
                    } catch (Exception ignored) {}
                }
                if (from == null || to == null) {
                    // fallback parse dir name like translate-en_fr-1_9 or ar_en
                    String name = p.getFileName().toString();
                    String[] ft = parsePairFromName(name);
                    from = from != null ? from : ft[0]; to = to != null ? to : ft[1];
                }
                boolean hasModel = Files.isRegularFile(p.resolve("model/model.bin"));
                boolean hasSpm = Files.isRegularFile(p.resolve("sentencepiece.model"));
                out.add(new ArgosPair(from, to, fromName, toName, ver, p.getFileName().toString(), hasModel, hasSpm));
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing((ArgosPair a) -> a.from == null ? "" : a.from).thenComparing(a -> a.to == null ? "" : a.to).thenComparing(a -> a.dirName));
        return out;
    }

    private static String[] parsePairFromName(String name) {
        if (name == null) return new String[]{null,null};
        if (name.startsWith("translate-")) {
            String rest = name.substring("translate-".length());
            int us = rest.indexOf('_'); int dash = rest.lastIndexOf('-');
            if (us > 0 && dash > us) return new String[]{rest.substring(0, us), rest.substring(us+1, dash)};
        }
        int us = name.indexOf('_');
        if (us > 0 && us < name.length()-1) return new String[]{name.substring(0, us), name.substring(us+1)};
        return new String[]{null,null};
    }

    public static PyannoteInfo pyannote(Path dir) {
        boolean config = Files.isRegularFile(dir.resolve("config.yaml"));
        boolean emb = Files.isRegularFile(dir.resolve("embedding/pytorch_model.bin"));
        boolean seg = Files.isRegularFile(dir.resolve("segmentation/pytorch_model.bin"));
        boolean plda = Files.isRegularFile(dir.resolve("plda/plda.npz"));
        boolean xvec = Files.isRegularFile(dir.resolve("plda/xvec_transform.npz"));
        return new PyannoteInfo(dir, config && emb && seg && plda && xvec, config, emb, seg, plda, xvec);
    }

    public static List<String> synonymLangs(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                int i = n.lastIndexOf('.');
                out.add(i>0 ? n.substring(0,i) : n);
            }
        } catch (IOException ignored) {}
        Collections.sort(out);
        return out;
    }

    private static Map<String,Object> readFlatJson(Path p) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(p)) return out;
        try {
            JsonNode n = MAPPER.readTree(p.toFile());
            n.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (v.isNumber() || v.isBoolean() || v.isTextual()) out.put(e.getKey(), v.asText());
            });
        } catch (Exception ignored) {}
        return out;
    }
    private static String text(JsonNode n, String f){ JsonNode v=n.path(f); return v.isMissingNode()||v.isNull()?null:v.asText(); }

    public static class Catalog {
        public List<WhisperInfo> whisper = List.of();
        public List<ArgosPair> argosPairs = List.of();
        public PyannoteInfo pyannote;
        public List<String> synonymLangs = List.of();

        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Model catalog (Java, local assets)\n");
            sb.append("Whisper models (complete snapshots):\n");
            for (WhisperInfo w : whisper) {
                sb.append("  - ").append(w.name).append(w.complete?" [COMPLETE]":" [INCOMPLETE]")
                  .append(w.snapshotHash!=null?" hash="+w.snapshotHash:"")
                  .append(" tokenizer=").append(w.hasTokenizer);
                if (w.config != null && !w.config.isEmpty()) sb.append(" configKeys=").append(w.config.size());
                sb.append("\n");
            }
            sb.append("Argos translation pairs: ").append(argosPairs.size()).append("\n");
            for (ArgosPair a : argosPairs) {
                sb.append("  - ").append(nz(a.from,"?")).append("->").append(nz(a.to,"?"))
                  .append(a.version!=null?" v"+a.version:"")
                  .append(" dir=").append(a.dirName)
                  .append(" model=").append(a.hasModel).append(" spm=").append(a.hasSentencepiece)
                  .append("\n");
            }
            sb.append("Pyannote: ").append(pyannote!=null && pyannote.complete?"COMPLETE":"INCOMPLETE").append("\n");
            sb.append("Synonym langs: ").append(synonymLangs).append("\n");
            sb.append("Note (P0 baseline locked): STT runs in-process via libwhisper JNA shim when available (fallback: whisper.cpp subprocess), translation via local CTranslate2+SentencePiece helper over Argos packages (zero argostranslate server). ")
              .append("Argos pairs without a SentencePiece tokenizer in the package (BPE-only format) are reported as unusable by the current helper; faster-whisper/CTranslate2 snapshots and optional GGML assets are inventoried as-is. ")
              .append("Diarization: Java ORT pipeline integrated (P5). Requires compatible segmentation.onnx+embedding.onnx in resources/models/pyannote/onnx/ and onnxruntime on classpath; uses local plda/config. Python sidecar is explicit fallback only.\n");
            return sb.toString();
        }
        private static String nz(String s,String d){return s==null?d:s;}
    }

    public static class WhisperInfo {
        public final String name; public final boolean complete; public final String snapshotHash; public final Path snapshotDir;
        public final boolean hasTokenizer; public final Map<String,Object> config;
        public WhisperInfo(String name, boolean complete, String snapshotHash, Path snapshotDir, boolean hasTokenizer, Map<String,Object> config) {
            this.name=name; this.complete=complete; this.snapshotHash=snapshotHash; this.snapshotDir=snapshotDir; this.hasTokenizer=hasTokenizer; this.config=config;
        }
    }
    public static class ArgosPair {
        public final String from,to,fromName,toName,version,dirName; public final boolean hasModel,hasSentencepiece;
        public ArgosPair(String from,String to,String fromName,String toName,String version,String dirName,boolean hasModel,boolean hasSentencepiece){
            this.from=from;this.to=to;this.fromName=fromName;this.toName=toName;this.version=version;this.dirName=dirName;this.hasModel=hasModel;this.hasSentencepiece=hasSentencepiece;
        }
    }
    public static class PyannoteInfo {
        public final Path dir; public final boolean complete,config,embedding,segmentation,plda,xvec;
        public PyannoteInfo(Path dir,boolean complete,boolean config,boolean embedding,boolean segmentation,boolean plda,boolean xvec){
            this.dir=dir;this.complete=complete;this.config=config;this.embedding=embedding;this.segmentation=segmentation;this.plda=plda;this.xvec=xvec;
        }
    }
}
