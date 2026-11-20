package com.gek.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Warm STT backend backed by a persistent whisper.cpp {@code whisper-server} process.
 * Keeps the model loaded in the server process to avoid per-request CLI startup/model reload.
 * Used as a {@link SpeechToTextEngine} (and via {@link WindowedStreamingSession} for streaming partials).
 */
public class WhisperServerSttEngine implements StreamingSpeechToTextEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppPaths paths;
    private final String serverBinary;
    private final Path modelPath;
    private final String reason;
    private final boolean usable;
    private final int port;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Process process;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public WhisperServerSttEngine(AppPaths paths, AppSettings settings, NativeRuntimeProbe.SttRuntime runtime) {
        this.paths = paths;
        String bin = NativeRuntimeProbe.findWhisperServerBinary(paths).orElse(null);
        Path model = runtime != null && runtime.model().isPresent() ? runtime.model().get() : null;
        this.serverBinary = bin;
        this.modelPath = model;
        this.port = (bin != null && model != null) ? findFreePort() : -1;
        this.baseUrl = "http://127.0.0.1:" + port;

        String why = null;
        boolean ok = false;
        if (bin == null) {
            why = "whisper-server binary not found";
        } else if (model == null || !Files.exists(model)) {
            why = "whisper model not available for server backend";
        } else if (port <= 0) {
            why = "no free port available for whisper-server";
        } else {
            try {
                startServer(settings);
                if (waitReady(Duration.ofSeconds(45))) {
                    ok = true;
                    why = "Warm whisper-server ready (" + Path.of(bin).getFileName() + ", port " + port + ", model " + model.getFileName() + ")";
                } else {
                    why = "whisper-server did not become ready on " + baseUrl;
                    stopProcess();
                }
            } catch (Exception e) {
                why = "failed to start whisper-server: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                stopProcess();
            }
        }
        this.usable = ok;
        this.reason = why;
    }

    /** Best-effort factory; returns a usable engine or {@code null}. */
    public static WhisperServerSttEngine tryCreate(AppPaths paths, AppSettings settings, NativeRuntimeProbe.SttRuntime runtime) {
        if (Boolean.getBoolean("gek.stt.disableWarmServer")) return null;
        try {
            WhisperServerSttEngine e = new WhisperServerSttEngine(paths, settings, runtime);
            return e.usable ? e : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void startServer(AppSettings settings) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(serverBinary);
        cmd.add("-m"); cmd.add(modelPath.toAbsolutePath().toString());
        cmd.add("--host"); cmd.add("127.0.0.1");
        cmd.add("--port"); cmd.add(String.valueOf(port));
        cmd.add("-l"); cmd.add("auto");
        cmd.add("-nt");
        // prefer GPU if available (binary may be CUDA build); server auto-detects
        cmd.add("-fa");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (paths != null && paths.projectRoot() != null) pb.directory(paths.projectRoot().toFile());
        pb.redirectErrorStream(true);
        // drain output to avoid blocking
        this.process = pb.start();
        Thread drainer = new Thread(() -> {
            try (InputStream in = process.getInputStream()) { in.readAllBytes(); } catch (Exception ignored) {}
        }, "gek-whisper-server-out");
        drainer.setDaemon(true);
        drainer.start();
    }

    private boolean waitReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        URI uri = URI.create(baseUrl + "/");
        while (System.nanoTime() < deadline) {
            if (closed.get()) return false;
            Process p = process;
            if (p != null && !p.isAlive()) return false;
            try {
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 500) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    @Override
    public CompletableFuture<Void> warmup() {
        if (usable) return CompletableFuture.completedFuture(null);
        return CompletableFuture.failedFuture(new InferenceUnavailableException(reason));
    }

    @Override
    public CompletableFuture<TranscriptionResult> transcribe(Path wavPath, String languageHint) {
        if (!usable || closed.get()) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException(reason == null ? "whisper-server not usable" : reason));
        }
        if (wavPath == null) {
            return CompletableFuture.failedFuture(new InferenceUnavailableException("wavPath is required"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doTranscribe(wavPath, languageHint);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new InferenceUnavailableException("whisper-server transcription failed: " + msg, e);
            }
        });
    }

    private TranscriptionResult doTranscribe(Path wavPath, String languageHint) throws Exception {
        if (!Files.exists(wavPath)) throw new IOException("Audio file not found: " + wavPath);
        String boundary = "----gekboundary" + System.nanoTime();
        byte[] body = buildMultipart(wavPath, boundary, languageHint);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/inference"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("server HTTP " + resp.statusCode() + ": " + trim(resp.body(), 400));
        }
        return parseResult(resp.body(), languageHint);
    }

    private byte[] buildMultipart(Path wavPath, String boundary, String languageHint) throws IOException {
        String fileName = wavPath.getFileName() == null ? "audio.wav" : wavPath.getFileName().toString();
        byte[] fileBytes = Files.readAllBytes(wavPath);
        StringBuilder sb = new StringBuilder();
        // response_format json
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n");
        // temperature 0 for deterministic-ish
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"temperature\"\r\n\r\n0\r\n");
        String lang = normalizeLang(languageHint);
        if (lang != null) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n").append(lang).append("\r\n");
        }
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
        sb.append("Content-Type: audio/wav\r\n\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + fileBytes.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(fileBytes, 0, out, head.length, fileBytes.length);
        System.arraycopy(tail, 0, out, head.length + fileBytes.length, tail.length);
        return out;
    }

    private TranscriptionResult parseResult(String json, String languageHint) {
        String text = "";
        String lang = normalizeLang(languageHint);
        try {
            JsonNode root = MAPPER.readTree(json == null ? "{}" : json);
            if (root.hasNonNull("text")) {
                text = root.get("text").asText("").trim();
            } else if (root.has("transcription") && root.get("transcription").hasNonNull("text")) {
                text = root.get("transcription").get("text").asText("").trim();
            } else {
                // try to extract any "text" field roughly
                text = extractTextField(json);
            }
            if (lang == null && root.hasNonNull("language")) {
                lang = normalizeLang(root.get("language").asText(null));
            }
            if (lang == null && root.hasNonNull("detected_language")) {
                lang = normalizeLang(root.get("detected_language").asText(null));
            }
        } catch (Exception e) {
            text = extractTextField(json);
        }
        return new TranscriptionResult(text == null ? "" : text, lang, 0.0, 0.0);
    }

    private static String extractTextField(String json) {
        if (json == null) return "";
        // naive fallback: "text" : "..."
        int i = json.indexOf("\"text\"");
        if (i < 0) return json.trim();
        int colon = json.indexOf(':', i);
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int k = q1 + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (esc) { sb.append(c); esc = false; }
            else if (c == '\\') esc = true;
            else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String normalizeLang(String hint) {
        if (hint == null) return null;
        String v = hint.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "auto".equals(v) || "detected".equals(v) || "auto-detect".equals(v)) return null;
        return v;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean isUsable() { return usable && !closed.get(); }
    public String reason() { return reason; }

    @Override
    public StreamingSttSession startSession(String languageHint, StreamingTranscriptionListener listener) {
        SpeechToTextEngine backend = this;
        Path tmp = paths != null ? paths.dataDir().resolve("tmp") : null;
        return new WindowedStreamingSession(backend, languageHint, listener, tmp);
    }

    @Override public boolean streamingAvailable() { return isUsable(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopProcess();
    }

    private void stopProcess() {
        Process p = process;
        process = null;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
            try { p.waitFor(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
            try { if (p.isAlive()) p.destroyForcibly(); } catch (Exception ignored) {}
        }
    }
}
