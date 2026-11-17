package com.gek.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SidecarClient implements SpeechToTextEngine, TranslationEngine, DiarizationEngine {
    private static final Logger log = LoggerFactory.getLogger(SidecarClient.class);
    private final AppPaths paths;
    private final AppSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private Thread stderrThread;

    public SidecarClient(AppPaths paths, AppSettings settings) {
        this.paths = paths;
        this.settings = settings;
    }

    public synchronized void start() throws IOException {
        if (running.get() && process != null && process.isAlive()) return;
        running.set(false);
        List<String> cmd = buildCommand();
        log.info("Starting inference sidecar: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(paths.projectRoot().toFile());
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        running.set(true);
        readerThread = new Thread(this::readLoop, "gek-sidecar-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        stderrThread = new Thread(this::stderrLoop, "gek-sidecar-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    public boolean isAlive() { return running.get() && process != null && process.isAlive(); }

    public String resolvedPython() {
        String python = (settings.sidecar_python != null && !settings.sidecar_python.isBlank())
                ? settings.sidecar_python : resolvePython();
        return python;
    }

    public Path resolvedScript() {
        Path script = paths.projectRoot().resolve("gek-java").resolve("bridge").resolve("inference_server.py");
        if (!Files.exists(script)) {
            script = paths.projectRoot().resolve("bridge").resolve("inference_server.py");
        }
        return script;
    }

    private List<String> buildCommand() throws IOException {
        String python = resolvedPython();
        Path script = resolvedScript();
        if (!Files.exists(script)) throw new IOException("Inference sidecar script not found: " + script);
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.add(script.toString());
        cmd.add("--models-dir"); cmd.add(paths.modelsDir().toString());
        cmd.add("--whisper-models-dir"); cmd.add(paths.whisperModelsDir().toString());
        cmd.add("--argos-packages-dir"); cmd.add(paths.argosModelsDir().resolve("packages").toString());
        cmd.add("--pyannote-dir"); cmd.add(paths.pyannoteModelsDir().resolve("speaker-diarization-community-1").toString());
        cmd.add("--synonyms-dir"); cmd.add(paths.synonymsDir().toString());
        cmd.add("--whisper-model"); cmd.add(envOr("GEK_WHISPER_MODEL", nvl(settings.whisper_model, "small")));
        cmd.add("--whisper-device"); cmd.add(envOr("GEK_WHISPER_DEVICE", nvl(settings.whisper_device, "cpu")));
        cmd.add("--whisper-compute-type"); cmd.add(envOr("GEK_WHISPER_COMPUTE_TYPE", nvl(settings.whisper_compute_type, "int8")));
        return cmd;
    }

    private String resolvePython() {
        String env = System.getenv("GEK_PYTHON");
        if (env != null && !env.isBlank()) return env;
        String os = System.getProperty("os.name", "").toLowerCase();
        Path embed = paths.projectRoot().resolve("src").resolve("python-3.14.7-embed-amd64").resolve("python.exe");
        if (os.contains("win") && Files.exists(embed)) return embed.toString();
        // In WSL/Linux prefer native python even if Windows embedded exe exists on mounted path.
        return "python3";
    }

    private void readLoop() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = br.readLine()) != null) {
                try {
                    JsonNode resp = mapper.readTree(line);
                    String id = resp.path("id").asText(null);
                    CompletableFuture<JsonNode> f = id != null ? pending.remove(id) : null;
                    if (f != null) f.complete(resp);
                    else log.debug("Unmatched sidecar response: {}", line);
                } catch (Exception e) {
                    log.warn("Bad sidecar response line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running.get()) log.warn("Sidecar reader ended: {}", e.getMessage());
        } finally {
            running.set(false);
            failPending("sidecar closed");
        }
    }

    private void stderrLoop() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.info("[sidecar] {}", line);
            }
        } catch (IOException ignored) { }
    }

    private void failPending(String msg) {
        pending.forEach((id, f) -> f.completeExceptionally(new SidecarException(msg)));
        pending.clear();
    }

    private CompletableFuture<JsonNode> request(ObjectNode req) {
        if (!isAlive()) {
            try { start(); } catch (IOException e) {
                return CompletableFuture.failedFuture(new SidecarException("sidecar start failed: " + e.getMessage(), e));
            }
        }
        String id = UUID.randomUUID().toString();
        req.put("id", id);
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            synchronized (this) {
                writer.write(mapper.writeValueAsString(req));
                writer.write("\n");
                writer.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            f.completeExceptionally(new SidecarException("sidecar write failed", e));
        }
        return f.orTimeout(300, TimeUnit.SECONDS).thenApply(resp -> {
            if (!resp.path("ok").asBoolean(false)) {
                throw new SidecarException(resp.path("error").asText("unknown sidecar error"));
            }
            return resp.path("result");
        });
    }

    /** Warmup and return detailed result (whisper/argos/diarizer booleans and errors map). */
    public CompletableFuture<JsonNode> warmupDetailed() {
        ObjectNode req = mapper.createObjectNode();
        req.put("op", "warmup");
        req.put("whisper_model", envOr("GEK_WHISPER_MODEL", nvl(settings.whisper_model, "small")));
        req.put("whisper_device", envOr("GEK_WHISPER_DEVICE", nvl(settings.whisper_device, "cpu")));
        req.put("whisper_compute_type", envOr("GEK_WHISPER_COMPUTE_TYPE", nvl(settings.whisper_compute_type, "int8")));
        return request(req);
    }

    @Override public CompletableFuture<Void> warmup() {
        return warmupDetailed().thenApply(r -> null);
    }

    @Override public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
        ObjectNode req = mapper.createObjectNode();
        req.put("op", "transcribe");
        req.put("wav_path", wavPath.toAbsolutePath().toString());
        if (languageHint != null) req.put("language", languageHint);
        return request(req).thenApply(r -> new TranscriptionResult(
                r.path("text").asText(""),
                r.path("language").isNull() ? null : r.path("language").asText(null),
                r.path("language_probability").asDouble(0.0),
                r.path("duration").asDouble(0.0)
        ));
    }

    @Override public CompletableFuture<String> translate(String text, String sourceLang, String targetLang) {
        ObjectNode req = mapper.createObjectNode();
        req.put("op", "translate");
        req.put("text", text == null ? "" : text);
        req.put("source_lang", sourceLang);
        req.put("target_lang", targetLang);
        return request(req).thenApply(r -> r.path("text").asText(text == null ? "" : text));
    }

    @Override public CompletableFuture<List<DiarizationEngine.Segment>> diarize(Path wavPath) {
        ObjectNode req = mapper.createObjectNode();
        req.put("op", "diarize");
        req.put("wav_path", wavPath.toAbsolutePath().toString());
        return request(req).thenApply(r -> {
            List<DiarizationEngine.Segment> out = new ArrayList<>();
            for (JsonNode s : r.path("segments")) {
                out.add(new DiarizationEngine.Segment(s.path("start").asDouble(), s.path("end").asDouble(), s.path("speaker").asText()));
            }
            return out;
        });
    }

    public CompletableFuture<Boolean> ping() {
        ObjectNode req = mapper.createObjectNode();
        req.put("op", "ping");
        return request(req).thenApply(r -> r.path("pong").asBoolean(false)).orTimeout(10, TimeUnit.SECONDS).exceptionally(e -> false);
    }

    private static String nvl(String v, String d) { return (v == null || v.isBlank()) ? d : v; }
    private static String envOr(String key, String d) { String v = System.getenv(key); return (v == null || v.isBlank()) ? d : v.trim(); }

    @Override public synchronized void close() {
        if (!running.getAndSet(false)) return;
        try {
            ObjectNode req = mapper.createObjectNode(); req.put("op", "shutdown");
            req.put("id", "shutdown");
            writer.write(mapper.writeValueAsString(req)); writer.write("\n"); writer.flush();
        } catch (Exception ignored) { }
        failPending("sidecar closed");
        if (process != null) {
            process.destroy();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
