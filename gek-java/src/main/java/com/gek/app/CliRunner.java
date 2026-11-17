package com.gek.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gek.audio.AudioChunk;
import com.gek.audio.SpeechSegmenter;
import com.gek.audio.WavReader;
import com.gek.audio.WavWriter;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.core.ModelCatalog;
import com.gek.core.PlatformSupport;
import com.gek.core.SettingsStore;
import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import com.gek.inference.EngineFactory;
import com.gek.inference.SidecarClient;
import com.gek.inference.SpeechToTextEngine;
import com.gek.inference.TranscriptionResult;
import com.gek.inference.TranslationEngine;
import com.gek.inject.TextInjector;
import com.gek.inject.WindowsTextInjector;
import com.gek.text.SynonymReplacer;
import com.gek.text.TranslationPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless/CLI operations feasible in WSL2: file transcription/translation, history ops, smoke tests.
 */
public final class CliRunner {
    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);
    private CliRunner() {}

    public static boolean handleIfRequested(String[] args, AppPaths paths, SettingsStore store, HistoryRepository history, EngineFactory.Engines engines) {
        if (args == null) return false;
        try {
            if (has(args, "--apply-synonyms")) { runApplySynonyms(args, paths); return true; }
            if (has(args, "--list-audio-devices")) { runListAudioDevices(); return true; }
            if (has(args, "--native-info")) { runNativeInfo(paths, store, engines); return true; }
            if (has(args, "--inference-check")) { runInferenceCheck(paths, store, engines); return true; }
            if (has(args, "--wsl2-readiness")) { runWsl2Readiness(paths, store, engines); return true; }
            if (has(args, "--model-catalog")) { runModelCatalog(paths); return true; }
            if (has(args, "--segment-file")) { runSegmentFile(args, paths, store); return true; }
            if (has(args, "--live-simulate-file")) { runLiveSimulateFile(args, paths, store, history, engines); return true; }
            if (has(args, "--history-recent")) { runHistoryRecent(args, history); return true; }
            if (has(args, "--history-search")) { runHistorySearch(args, history); return true; }
            if (has(args, "--platform-info")) { runPlatformInfo(); return true; }
            // inference commands: prefer native runtime when usable, else sidecar fallback
            SidecarClient sc = engines != null ? engines.sidecarOrNull() : null;
            if (has(args, "--doctor")) { runDoctor(args, paths, store, history, engines); return true; }
            if (has(args, "--transcribe-file")) { runTranscribeFile(args, paths, store, history, engines); return true; }
            if (has(args, "--diarize-file")) { runDiarizeFile(args, paths, engines); return true; }
            if (has(args, "--translate-text")) { runTranslateText(args, paths, store, engines); return true; }
            if (has(args, "--inference-smoke")) { runInferenceSmoke(args, paths, store, engines); return true; }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (e instanceof com.gek.inference.InferenceUnavailableException) {
                System.out.println("[UNAVAILABLE] " + msg);
                System.exit(2);
            }
            System.out.println("[FAIL] " + msg);
            System.exit(1);
        }
        return false;
    }

    private static boolean has(String[] args, String name) { for (String a : args) if (name.equalsIgnoreCase(a)) return true; return false; }
    private static String argVal(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) if (name.equalsIgnoreCase(args[i])) return args[i+1];
        return def;
    }


    private static void requireSidecar(SidecarClient sc, String cmd) {
        if (sc == null) {
            throw new com.gek.inference.InferenceUnavailableException(cmd + " requires inference. No usable native runtime is available in this environment (see --inference-check/--native-info). "
                    + "To use the temporary Python sidecar fallback (only if its ML deps are installed), run with --use-sidecar and/or set inference_mode=sidecar. "
                    + "Pure-Java now: --model-catalog, --segment-file, --live-simulate-file, --apply-synonyms, --list-audio-devices, --history-*, --platform-info, --native-info, --inference-check, --wsl2-readiness, --self-test.");
        }
    }


    private static void runWsl2Readiness(AppPaths paths, SettingsStore store, EngineFactory.Engines engines) throws Exception {
        AppSettings s = store.load();
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        PlatformSupport.Capabilities caps = PlatformSupport.capabilities();
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        boolean sidecar = engines != null && engines.sidecarOrNull() != null;
        long completeWhisper = c.whisper.stream().filter(w -> w.complete).count();
        long usablePairs = c.argosPairs.stream().filter(p -> p.hasModel).count();
        boolean py3 = onPath("python3") || onPath("python");
        boolean pip = onPath("pip3") || onPath("pip");
        boolean ffmpeg = onPath("ffmpeg");
        boolean pw = onPath("pw-record") || onPath("parec") || onPath("parecord") || onPath("pw-cat");
        boolean linuxTts = onPath("espeak-ng") || onPath("espeak") || onPath("spd-say") || onPath("piper");
        boolean linuxInject = onPath("wl-copy") || onPath("xclip") || onPath("xdotool") || onPath("ydotool");

        System.out.println("Platform readiness report (primary target for full desktop parity: Windows host)");
        System.out.println();
        System.out.println("[NOW - core/verified baseline]");
        System.out.println("  - Build/test/package (Java 21/Maven): YES");
        System.out.println("  - Headless CLI/diagnostics (help/self-test/assets/doctor/platform/model-catalog/history/inference-check/native-info): YES");
        System.out.println("  - Pure-Java pipeline: settings/history DB, WAV read/write + VAD segmentation, headless simulated live pipeline (--live-simulate-file), synonyms/post-processing: YES");
        System.out.println("  - Local assets detected: whisper complete " + completeWhisper + "/" + c.whisper.size() + ", argos pairs with model " + usablePairs + "/" + c.argosPairs.size() + ", pyannote complete=" + (c.pyannote!=null&&c.pyannote.complete) + ", synonyms=" + (c.synonymLangs==null?0:c.synonymLangs.size()));
        boolean sttR = engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine n && n.runtimeUsable();
        boolean sttInJvm = engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine nIn && nIn.inJvm();
        boolean trR = engines!=null && engines.translator() instanceof com.gek.inference.NativeTranslationEngine ntr && ntr.runtimeUsable();
        boolean trInJvm = engines!=null && engines.translator() instanceof com.gek.inference.NativeTranslationEngine ntrI && ntrI.inJvm();
        boolean diR = engines!=null && engines.diarizer() instanceof com.gek.inference.NativeDiarizationEngine ndi && ndi.runtimeUsable();
        System.out.println("  - Effective inference mode=" + (engines!=null?engines.mode():"unknown") + " (STT native ready=" + sttR + ", translation native ready=" + trR + ", diarization native ready=" + diR + "; sidecar enabled=" + sidecar + ")");
        System.out.println("  - Option C Java-native inference: STT(" + (sttInJvm?"in-process libwhisper JNA shim":"whisper.cpp/GGML") + ")=" + (sttR?"READY":"NOT READY") + "; translation(" + (trInJvm?"in-process embedded CTranslate2+SentencePiece shim":"Argos helper") + ")=" + (trR?"READY":"NOT READY") + "; diarization=" + (diR?"READY (in-JVM ONNX Runtime pipeline)":"NOT READY") + ".");
        System.out.println();
        if (PlatformSupport.isWindows()) {
            System.out.println("[WINDOWS DESKTOP - enabled integration paths in this build]");
            System.out.println("  - Swing UI: gui env=" + caps.gui() + ", JVM headless=" + headless + " (requires non-headless JDK/JRE)");
            System.out.println("  - Global hotkeys (RegisterHotKey) and caret/text injection (clipboard + Ctrl+V): wired for Windows");
            System.out.println("  - Windows TTS (SAPI via PowerShell/System.Speech): enabled=" + caps.windowsSapiTts());
            System.out.println("  - System/loopback capture (best-effort): ffmpeg present=" + ffmpeg + ". Configure settings.audio_loopback to a DirectShow audio device name for --/UI live loopback.");
            System.out.println("  - Core Audio per-app ducking: integration hook present, native per-app implementation pending (best-effort placeholder; not reported as active)");
            System.out.println("  - Java Sound mic/input: enumerated at runtime via --list-audio-devices");
        } else {
            System.out.println("[ENVIRONMENT / SETUP]");
            System.out.println("  - Swing GUI via display/WSLg: WSLg detected=" + caps.wslg() + ", gui env=" + caps.gui() + ", JVM headless=" + headless + " (needs non-headless JDK/JRE and display)");
            System.out.println("  - Java Sound mic/input: enumerated at runtime via --list-audio-devices (devices depend on host/audio exposure)");
            System.out.println("  - Linux loopback capture tools: ffmpeg=" + ffmpeg + " pulse/pipewire helpers=" + pw);
            System.out.println("  - Linux TTS/clipboard/inject helpers: tts=" + linuxTts + " inject/clipboard=" + linuxInject);
            System.out.println();
            System.out.println("[WINDOWS-HOST DESKTOP PARITY - requires running this Java app on Windows]");
            System.out.println("  - Global hotkeys and caret/text injection into native Windows apps: enabled when run on Windows");
            System.out.println("  - Windows TTS (SAPI): enabled when run on Windows");
            System.out.println("  - Windows host system-loopback capture and Core Audio per-app ducking: require Windows host + appropriate capture/ducking runtime support");
        }
        System.out.println();
        String pyMl = pyMlStatus();
        System.out.println("[OPTIONAL FALLBACK]");
        System.out.println("  - Temporary Python sidecar: python3=" + py3 + " pip=" + pip + " ml_deps=[" + pyMl + "] (enable with --use-sidecar only if required deps present)");
        System.out.println();
        System.out.println("[OK] platform readiness completed");
    }

    private static boolean onPath(String cmd) {
        if (cmd == null || cmd.isBlank()) return false;
        try {
            ProcessBuilder pb;
            if (PlatformSupport.isWindows()) {
                pb = new ProcessBuilder("cmd.exe", "/c", "where " + cmd);
            } else {
                pb = new ProcessBuilder("sh", "-lc", "command -v " + cmd + " >/dev/null 2>&1");
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                while (br.readLine() != null) { /* consume */ }
            } catch (Exception ignored) {}
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static boolean pyModule(String py, String mod) {
        try {
            Process p = new ProcessBuilder(py, "-c", "import " + mod).start();
            boolean finished = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static String pyMlStatus() {
        String py = "python3";
        if (!onPath(py)) return "python3 not found";
        StringBuilder sb = new StringBuilder();
        sb.append("faster_whisper=").append(pyModule(py, "faster_whisper"));
        sb.append(", argostranslate=").append(pyModule(py, "argostranslate"));
        sb.append(", torch=").append(pyModule(py, "torch"));
        sb.append(", pyannote=").append(pyModule(py, "pyannote.audio"));
        return sb.toString();
    }

    private static void runInferenceCheck(AppPaths paths, SettingsStore store, EngineFactory.Engines engines) throws Exception {
        AppSettings s = store.load();
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        String requested = s.whisper_model == null ? "small" : s.whisper_model;
        boolean requestedComplete = c.whisper.stream().anyMatch(w -> w.name.equalsIgnoreCase(requested) && w.complete);
        long completeWhisper = c.whisper.stream().filter(w -> w.complete).count();
        long usablePairs = c.argosPairs.stream().filter(p -> p.hasModel).count();
        String mode = engines != null ? engines.mode() : "unknown";
        System.out.println("Inference readiness check (WSL2-feasible)");
        System.out.println("  effective_mode=" + mode);
        System.out.println("  configured_mode=" + s.inference_mode);
        System.out.println("  whisper_requested=" + requested + " complete=" + requestedComplete + " (complete_snapshots=" + completeWhisper + "/" + c.whisper.size() + ")");
        System.out.println("  argos_pairs_with_model=" + usablePairs + "/" + c.argosPairs.size());
        System.out.println("  pyannote_assets_complete=" + (c.pyannote != null && c.pyannote.complete));
        boolean sttReady = engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine nstt && nstt.runtimeUsable();
        boolean sttInJvm = engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine nsttJ && nsttJ.inJvm();
        boolean trReadyNative = engines!=null && engines.translator() instanceof com.gek.inference.NativeTranslationEngine ntr && ntr.runtimeUsable();
        boolean trInJvm = engines!=null && engines.translator() instanceof com.gek.inference.NativeTranslationEngine ntrJ && ntrJ.inJvm();
        boolean diReadyNative = engines!=null && engines.diarizer() instanceof com.gek.inference.NativeDiarizationEngine ndi && ndi.runtimeUsable();
        String sttReason = (engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine nstt2) ? nstt2.reason() : "n/a";
        String trReason = (engines!=null && engines.translator() instanceof com.gek.inference.NativeTranslationEngine ntr2) ? ntr2.reason() : "n/a";
        String diReason = (engines!=null && engines.diarizer() instanceof com.gek.inference.NativeDiarizationEngine ndi2) ? ndi2.reason() : "n/a";
        System.out.println("  java_native_stt_ready=" + sttReady);
        System.out.println("  java_native_stt_in_jvm=" + sttInJvm);
        System.out.println("  java_native_stt_detail=" + sttReason);
        System.out.println("  java_native_translation_ready=" + trReadyNative);
        System.out.println("  java_native_translation_in_jvm=" + trInJvm);
        System.out.println("  java_native_translation_detail=" + trReason);
        System.out.println("  java_native_diarization_ready=" + diReadyNative);
        System.out.println("  java_native_diarization_detail=" + diReason);
        String pyMl = pyMlStatus();
        System.out.println("  python_ml_deps_probe=" + pyMl);
        if ("sidecar".equals(mode)) {
            SidecarClient sc = engines.sidecarOrNull();
            System.out.println("  sidecar_enabled=true python=" + (sc != null ? sc.resolvedPython() : "n/a") + " script_exists=" + (sc != null && java.nio.file.Files.exists(sc.resolvedScript())));
            System.out.println("  sidecar_inference_ready=REQUIRES_PYTHON_ML_DEPS_AND_WARMUP (see python_ml_deps_probe)");
            System.out.println("[INFO] Use --self-test --use-sidecar [--warmup] to probe sidecar availability in this environment.");
        } else {
            System.out.println("  sidecar_enabled=false (default Java-native migration mode)");
            if (sttReady) {
                boolean inJvmStt = engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine nse && nse.inJvm();
                System.out.println("  native_stt_file_transcription=READY (" + (inJvmStt ? "in-process libwhisper JNA shim + GGML, no subprocess/Python" : "whisper.cpp subprocess + GGML, no Python") + "). Use --transcribe-file --input <wav> [--source-lang X] [--translate --target-lang Y].");
            } else {
                System.out.println("  native_stt_file_transcription=UNAVAILABLE (provide/build whisper.cpp and GGML model for current env).");
            }
            if (trReadyNative) System.out.println("  native_translation=READY (" + (trInJvm ? "in-process embedded ctranslate2+SentencePiece shim, no subprocess" : "local ctranslate2+SentencePiece helper on Argos packages") + "; explicit --source-lang/--target-lang pair required; BPE-only pairs unavailable). Use --translate-text --text ... --source-lang X --target-lang Y.");
            else System.out.println("  native_translation=UNAVAILABLE (translation shim/runtime or usable SentencePiece package missing).");
            if (diReadyNative) System.out.println("  native_diarization=READY (ONNX pipeline).");
            else System.out.println("  native_diarization=UNAVAILABLE (Java ORT pipeline integrated; needs ONNX exports segmentation.onnx+embedding.onnx and ORT on classpath). Detail: " + diReason);
            System.out.println("[INFO] Pure-Java ready: history/settings, --segment-file, --apply-synonyms, --model-catalog, --platform-info. Enable sidecar only as temporary fallback with --use-sidecar when Python ML deps are installed.");
        }
        System.out.println("[OK] inference-check completed");
    }

    private static void runNativeInfo(AppPaths paths, SettingsStore store, EngineFactory.Engines engines) throws Exception {
        AppSettings s = store.load();
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        String requested = s.whisper_model == null ? "small" : s.whisper_model;
        boolean requestedComplete = c.whisper.stream().anyMatch(w -> w.name.equalsIgnoreCase(requested) && w.complete);
        long usablePairs = c.argosPairs.stream().filter(p -> p.hasModel).count();
        System.out.println("Effective inference mode: " + (engines!=null?engines.mode():"unknown"));
        System.out.println("Configured inference_mode: " + s.inference_mode);
        System.out.println("Note: " + (engines!=null?engines.note():""));
        System.out.println("Configured whisper_model=" + requested + " (complete snapshot=" + requestedComplete + ") device=" + s.whisper_device + " compute=" + s.whisper_compute_type);
        String sttStatus = (engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine nstt) ? nstt.reason() : (engines!=null && engines.sidecarOrNull()!=null ? "using sidecar STT" : "unknown");
        String trStatus = (engines!=null && engines.translator() instanceof com.gek.inference.NativeTranslationEngine ntr) ? ntr.reason() : (engines!=null && engines.sidecarOrNull()!=null ? "using sidecar translation" : "unknown");
        String diStatus = (engines!=null && engines.diarizer() instanceof com.gek.inference.NativeDiarizationEngine ndi) ? ndi.reason() : (engines!=null && engines.sidecarOrNull()!=null ? "using sidecar diarization" : "unknown");
        System.out.println("Native STT runtime (Java): " + sttStatus);
        System.out.println("Native translation runtime (Java): " + trStatus);
        System.out.println("Native diarization runtime (Java): " + diStatus);
        System.out.println("Pure-Java ready now: settings/history, WAV read/write+VAD segmentation (--segment-file), headless simulated live pipeline (--live-simulate-file), synonyms/post-processing (--apply-synonyms), model catalog/asset inspection, platform diagnostics.");
        boolean sttReady = (engines!=null && engines.stt() instanceof com.gek.inference.NativeSpeechToTextEngine nstt2 && nstt2.runtimeUsable()) || (engines!=null && engines.sidecarOrNull()!=null);
        boolean trReady = (engines!=null && engines.sidecarOrNull()!=null) || (engines!=null && engines.translator() instanceof com.gek.inference.NativeTranslationEngine ntr2 && ntr2.runtimeUsable());
        boolean diR = (engines!=null && engines.diarizer() instanceof com.gek.inference.NativeDiarizationEngine ndi && ndi.runtimeUsable()) || (engines!=null && engines.sidecarOrNull()!=null);
        System.out.println("Inference-dependent now: --transcribe-file STT=" + (sttReady?"READY":"UNAVAILABLE") + " translation=" + (trReady?"READY":"UNAVAILABLE") + " diarization=" + (diR?"READY":"UNAVAILABLE (--diarize-file)") + ". STT/translation usable when runtimes ready; diarization uses in-JVM ORT pipeline when ONNX exports+ORT are present (sidecar fallback optional).");
    }

    private static void runApplySynonyms(String[] args, AppPaths paths) {
        String text = argVal(args, "--text", null);
        String lang = argVal(args, "--lang", null);
        if (text == null) throw new IllegalArgumentException("--text is required");
        if (lang == null) throw new IllegalArgumentException("--lang is required (e.g. en, nl)");
        SynonymReplacer syn = SynonymReplacer.load(paths.synonymsDir());
        String out = syn.apply(text, lang);
        System.out.println(out);
        Integer c = syn.counts().get(lang);
        System.out.println("[OK] apply-synonyms lang=" + lang + " mappings=" + (c==null?0:c) + (out.equals(text)?" (no changes)":" (changed)"));
    }

    private static void runListAudioDevices() {
        try {
            var names = com.gek.audio.AudioDevices.inputDeviceNames();
            if (names.isEmpty()) System.out.println("(no input devices found)");
            else for (int i=0;i<names.size();i++) System.out.println((i+1) + ". " + names.get(i));
            System.out.println("[OK] audio input devices=" + names.size());
        } catch (Throwable t) {
            System.out.println("[WARN] audio device enumeration failed: " + t.getMessage());
        }
    }

    private static void runPlatformInfo() {
        PlatformSupport.Capabilities c = PlatformSupport.capabilities();
        ObjectMapper m = new ObjectMapper();
        ObjectNode o = m.createObjectNode();
        o.put("os", System.getProperty("os.name"));
        o.put("wsl", c.wsl()); o.put("wslg", c.wslg()); o.put("gui", c.gui());
        o.put("windowsHotkey", c.windowsGlobalHotkey()); o.put("windowsInject", c.windowsCaretInjection());
        o.put("windowsLoopback", c.windowsWasapiLoopback()); o.put("windowsDucking", c.windowsCoreAudioDucking());
        o.put("windowsTts", c.windowsSapiTts()); o.put("linuxInjectPossible", c.linuxTextInjectionPossible()); o.put("linuxTtsPossible", c.linuxLocalTtsPossible());
        o.put("loopbackCommandAvailable", com.gek.audio.CommandLoopbackCapture.isSupported());
        o.put("loopbackBackend", com.gek.audio.CommandLoopbackCapture.backendName());
        o.put("linuxHotkeyFallback", com.gek.inject.LinuxGlobalHotkey.availableOnPlatform());
        o.put("headlessJvm", java.awt.GraphicsEnvironment.isHeadless());
        try { System.out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(o)); } catch (Exception e) { System.out.println(o.toString()); }
    }

    private static void runDoctor(String[] args, AppPaths paths, SettingsStore store, HistoryRepository history, EngineFactory.Engines engines) throws Exception {
        Main.runSelfTestPublic(paths, store, history, engines, args);
    }

    private static void runHistoryRecent(String[] args, HistoryRepository history) throws Exception {
        int n = Integer.parseInt(argVal(args, "--limit", "20"));
        List<TranslationRecord> list = history.recent(n);
        for (TranslationRecord r : list) {
            System.out.println(r.id() + "\t" + r.timestamp() + "\t" + r.originalLang() + "->" + r.translatedLang() + "\t" + oneLine(r.translatedText()));
        }
        System.out.println("[OK] history rows=" + list.size());
    }

    private static void runHistorySearch(String[] args, HistoryRepository history) throws Exception {
        String q = argVal(args, "--query", null);
        if (q == null || q.isBlank()) throw new IllegalArgumentException("--query is required for --history-search");
        int n = Integer.parseInt(argVal(args, "--limit", "20"));
        List<TranslationRecord> list = history.search(q, n);
        for (TranslationRecord r : list) {
            System.out.println(r.id() + "\t" + r.timestamp() + "\t" + oneLine(r.originalText()) + "\t=>\t" + oneLine(r.translatedText()));
        }
        System.out.println("[OK] search hits=" + list.size());
    }

    private static void runTranslateText(String[] args, AppPaths paths, SettingsStore store, EngineFactory.Engines engines) throws Exception {
        String text = argVal(args, "--text", null);
        if (text == null) throw new IllegalArgumentException("--text is required");
        AppSettings s = store.load();
        String src = argVal(args, "--source-lang", s.source_language == null ? "auto" : s.source_language);
        String tgt = argVal(args, "--target-lang", s.target_language == null ? "en" : s.target_language);
        SynonymReplacer syn = SynonymReplacer.load(paths.synonymsDir());
        String prepared = syn.apply(text, src);
        TranslationEngine tr = engines != null ? engines.translator() : null;
        if (tr == null) throw new com.gek.inference.InferenceUnavailableException("No translation engine available.");
        if (tr instanceof SidecarClient sc) sc.start();
        String out;
        try {
            out = tr.translate(prepared, src, tgt).join();
        } catch (Exception e) {
            Throwable c = e; while (c.getCause()!=null) c=c.getCause();
            if (c instanceof com.gek.inference.InferenceUnavailableException) throw (com.gek.inference.InferenceUnavailableException)c;
            throw new RuntimeException(c.getMessage()==null?String.valueOf(c):c.getMessage(), c);
        }
        out = sanitizeTranslation(out);
        boolean repetitive = TranslationPostProcessor.isRepetitive(out, prepared);
        boolean unchanged = out != null && src != null && !src.equalsIgnoreCase(tgt) && out.trim().equalsIgnoreCase(prepared.trim());
        if (out == null || out.isBlank() || repetitive) out = prepared;
        System.out.println(out);
        String suffix = prepared.equals(text) ? "" : " (synonyms applied)";
        if (repetitive) suffix += " (repetitive/low-confidence output replaced with source)";
        else if (unchanged) suffix += " (note: output identical to source; model/pair quality may be limited)";
        System.out.println("[OK] translate " + src + "->" + tgt + suffix);
    }

    private static void runTranscribeFile(String[] args, AppPaths paths, SettingsStore store, HistoryRepository history, EngineFactory.Engines engines) throws Exception {
        String in = argVal(args, "--input", null);
        if (in == null) throw new IllegalArgumentException("--input WAV path is required");
        Path input = Path.of(in).toAbsolutePath().normalize();
        if (!Files.exists(input)) throw new IllegalArgumentException("input not found: " + input);
        boolean doTranslate = has(args, "--translate");
        boolean save = has(args, "--save-history");
        AppSettings s = store.load();
        Path wav16k = ensure16kMonoWav(input, paths);
        String langHint = norm(argVal(args, "--source-lang", s.source_language));
        SpeechToTextEngine stt = engines != null ? engines.stt() : null;
        if (stt == null) throw new com.gek.inference.InferenceUnavailableException("No STT engine available.");
        if (stt instanceof SidecarClient sc) sc.start();
        TranscriptionResult trRes;
        try {
            trRes = stt.transcribe(wav16k, langHint).join();
        } catch (Exception e) {
            Throwable c = e; while (c.getCause()!=null) c=c.getCause();
            if (c instanceof com.gek.inference.InferenceUnavailableException) throw (com.gek.inference.InferenceUnavailableException)c;
            throw new RuntimeException(c.getMessage()==null?String.valueOf(c):c.getMessage(), c);
        }
        String raw = trRes.text() == null ? "" : trRes.text().trim();
        String detectedLang = norm(trRes.language());
        String src = detectedLang != null ? detectedLang : (langHint != null ? langHint : "auto");
        SynonymReplacer syn = SynonymReplacer.load(paths.synonymsDir());
        String text = syn.apply(raw, src);
        String tgt = argVal(args, "--target-lang", s.target_language == null ? "en" : s.target_language);
        String translated = text;
        if (doTranslate && !text.isEmpty() && !tgt.equalsIgnoreCase(src)) {
            String trSrc = norm(src);
            if (trSrc == null && detectedLang != null) trSrc = detectedLang;
            TranslationEngine te = engines != null ? engines.translator() : null;
            if (te == null) {
                translated = text;
                System.out.println("[WARN] translation requested but no inference runtime available; returning transcript");
            } else if (trSrc == null && te instanceof com.gek.inference.NativeTranslationEngine) {
                translated = text;
                System.out.println("[WARN] translation requested but source language not provided/detected; native translation needs explicit --source-lang (or detectable audio language) - returning transcript");
            } else {
                try {
                    if (te instanceof SidecarClient sc2) sc2.start();
                    String trOut = te.translate(text, trSrc, tgt).join();
                    trOut = sanitizeTranslation(trOut);
                    boolean repetitive = TranslationPostProcessor.isRepetitive(trOut, text);
                    boolean unchanged = trOut != null && trSrc != null && !trSrc.equalsIgnoreCase(tgt) && trOut.trim().equalsIgnoreCase(text.trim());
                    translated = (trOut == null || trOut.isBlank() || repetitive) ? text : trOut;
                    if (repetitive) System.out.println("[WARN] translation looked repetitive/low-confidence; kept transcript");
                    else if (unchanged) System.out.println("[WARN] translation output identical to source (model/pair quality may be limited)");
                } catch (Exception e) {
                    Throwable c = e; while (c.getCause()!=null) c=c.getCause();
                    if (c instanceof com.gek.inference.InferenceUnavailableException) {
                        System.out.println("[WARN] translation unavailable (" + c.getMessage() + "); returning transcript");
                        translated = text;
                    } else { log.warn("translate failed: {}", c.getMessage()); translated = text; }
                }
            }
        }
        System.out.println("SOURCE_LANG=" + src);
        System.out.println("TRANSCRIPT=" + text);
        if (doTranslate) System.out.println("TRANSLATED=" + translated);
        if (save && !text.isEmpty()) {
            history.save(TranslationRecord.ofNew(text, src, doTranslate ? translated : text, doTranslate ? tgt : src, "cli", input.toString(), "file"));
            System.out.println("[OK] saved to history");
        }
        System.out.println("[OK] transcribe-file " + input);
    }


    private static void runDiarizeFile(String[] args, AppPaths paths, EngineFactory.Engines engines) throws Exception {
        String in = argVal(args, "--input", null);
        if (in == null) throw new IllegalArgumentException("--input WAV path is required");
        Path input = Path.of(in).toAbsolutePath().normalize();
        if (!Files.exists(input)) throw new IllegalArgumentException("input not found: " + input);
        if (engines == null || engines.diarizer() == null) {
            throw new com.gek.inference.InferenceUnavailableException("No diarization engine available.");
        }
        try {
            java.util.List<com.gek.inference.DiarizationEngine.Segment> segs = engines.diarizer().diarize(input).join();
            System.out.println("DIARIZATION_SEGMENTS=" + (segs == null ? 0 : segs.size()));
            if (segs != null) {
                int i = 1;
                for (com.gek.inference.DiarizationEngine.Segment s : segs) {
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "SEGMENT\t%d\t%.3f\t%.3f\t%s", i++, s.start(), s.end(), s.speaker()));
                }
            }
            System.out.println("[OK] diarize-file " + input);
        } catch (Exception e) {
            Throwable c = e; while (c.getCause() != null) c = c.getCause();
            if (c instanceof com.gek.inference.InferenceUnavailableException) throw (com.gek.inference.InferenceUnavailableException) c;
            throw new RuntimeException(String.valueOf(c.getMessage()), c);
        }
    }

    private static void runModelCatalog(AppPaths paths) {
        ModelCatalog.Catalog c = ModelCatalog.inspect(paths);
        System.out.print(c.toText());
        long completeWhisper = c.whisper.stream().filter(w -> w.complete).count();
        long usablePairs = c.argosPairs.stream().filter(p -> p.hasModel).count();
        System.out.println("[OK] model-catalog whisperComplete=" + completeWhisper + "/" + c.whisper.size()
                + " argosPairs=" + c.argosPairs.size() + " argosWithModel=" + usablePairs
                + " pyannote=" + (c.pyannote!=null && c.pyannote.complete));
    }

    private static void runSegmentFile(String[] args, AppPaths paths, SettingsStore store) throws Exception {
        String in = argVal(args, "--input", null);
        if (in == null) throw new IllegalArgumentException("--input WAV path is required");
        Path input = Path.of(in).toAbsolutePath().normalize();
        if (!Files.exists(input)) throw new IllegalArgumentException("input not found: " + input);
        boolean writeChunks = has(args, "--write-chunks");
        AppSettings s = store.load();
        WavReader.AudioData audio = WavReader.readMono(input, 16000);
        float chunkSec = s.chunk_duration != null ? s.chunk_duration.floatValue() : 0.25f;
        int silenceMs = s.silence_duration_ms != null ? s.silence_duration_ms : 300;
        float minDur = s.min_chunk_duration != null ? s.min_chunk_duration.floatValue() : 0.15f;
        float maxDur = s.max_chunk_duration != null ? s.max_chunk_duration.floatValue() : 4.0f;
        float silenceThr = 0.01f;
        int chunkSamples = Math.max(160, (int)(16000f * chunkSec));
        java.util.List<float[]> segments = new java.util.ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, silenceThr, silenceMs, minDur, maxDur, 0.2f);
        seg.setOnSegment(segSamples -> segments.add(segSamples));
        float[] all = audio.samples();
        for (int off = 0; off < all.length; off += chunkSamples) {
            int n = Math.min(chunkSamples, all.length - off);
            float[] c = new float[n];
            System.arraycopy(all, off, c, 0, n);
            seg.accept(new AudioChunk("file", c, System.currentTimeMillis()));
        }
        seg.flush();
        Path outDir = paths.dataDir().resolve("tmp").resolve("segments");
        if (writeChunks) Files.createDirectories(outDir);
        double totalSeg = 0;
        for (int i = 0; i < segments.size(); i++) {
            float[] smp = segments.get(i);
            double dur = smp.length / 16000.0;
            totalSeg += dur;
            String line = String.format(java.util.Locale.ROOT, "SEGMENT\t%d\t%.3fs\t%d samples", i+1, dur, smp.length);
            if (writeChunks) {
                Path w = outDir.resolve(String.format(java.util.Locale.ROOT, "%s.seg%03d.wav", input.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"), i+1));
                WavWriter.writeFloatMono16k(w, smp);
                line += "\t" + w;
            }
            System.out.println(line);
        }
        System.out.println(String.format(java.util.Locale.ROOT, "[OK] segment-file input=%s duration=%.3fs segments=%d segmented=%.3fs chunk=%.2fs silenceMs=%d min=%.2fs max=%.2fs%s",
                input, audio.durationSeconds(), segments.size(), totalSeg, chunkSec, silenceMs, minDur, maxDur,
                writeChunks ? (" outDir="+outDir) : ""));
        System.out.println("Note: pure-Java segmentation/VAD only (no Python). STT/translation use native in-JVM runtimes when ready; diarization uses in-JVM ORT pipeline when ONNX exports+ORT are present (sidecar optional fallback).");
    }

    private static void runInferenceSmoke(String[] args, AppPaths paths, SettingsStore store, EngineFactory.Engines engines) throws Exception {
        AppSettings s = store.load();
        Path dir = paths.dataDir().resolve("tmp"); Files.createDirectories(dir);
        Path wav = Files.createTempFile(dir, "smoke-", ".wav");
        float[] silence = new float[16000]; // 1s
        WavWriter.writeFloatMono16k(wav, silence);
        try {
            SpeechToTextEngine stt = engines != null ? engines.stt() : null;
            TranslationEngine te = engines != null ? engines.translator() : null;
            if (stt == null || te == null) throw new com.gek.inference.InferenceUnavailableException("No inference engines available.");
            if (engines.sidecarOrNull() != null) {
                SidecarClient sc = engines.sidecarOrNull();
                sc.start();
                JsonNode warm = sc.warmupDetailed().join();
                System.out.println("WARMUP=" + warm.toString());
            } else {
                try { stt.warmup().join(); System.out.println("WARMUP=native-stt-ready"); }
                catch (Exception e) { Throwable c=e; while(c.getCause()!=null)c=c.getCause(); throw (c instanceof com.gek.inference.InferenceUnavailableException)?(com.gek.inference.InferenceUnavailableException)c:new com.gek.inference.InferenceUnavailableException(String.valueOf(c.getMessage()), c); }
            }
            try {
                TranscriptionResult tr = stt.transcribe(wav, norm(s.source_language)).join();
                System.out.println("TRANSCRIPT_EMPTY=" + (tr.text()==null || tr.text().trim().isEmpty()));
                System.out.println("TRANSCRIPT_LANG=" + tr.language());
            } catch (Exception e) {
                Throwable c=e; while(c.getCause()!=null)c=c.getCause();
                if (c instanceof com.gek.inference.InferenceUnavailableException) throw (com.gek.inference.InferenceUnavailableException)c;
                throw new RuntimeException(String.valueOf(c.getMessage()), c);
            }
            try {
                String translated = te.translate("hello world", "en", argVal(args, "--target-lang", s.target_language==null?"es":s.target_language)).join();
                System.out.println("TRANSLATE_SAMPLE=" + translated);
            } catch (Exception e) {
                Throwable c=e; while(c.getCause()!=null)c=c.getCause();
                if (c instanceof com.gek.inference.InferenceUnavailableException) {
                    System.out.println("TRANSLATE_UNAVAILABLE=" + c.getMessage());
                } else {
                    throw new RuntimeException(String.valueOf(c.getMessage()), c);
                }
            }
            System.out.println("[OK] inference-smoke completed");
        } finally {
            try { Files.deleteIfExists(wav); } catch (Exception ignored) {}
        }
    }

    /** Convert to 16k mono WAV if needed (best-effort via Java Sound; may fail for unsupported formats). */
    private static Path ensure16kMonoWav(Path input, AppPaths paths) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(input.toFile())) {
            AudioFormat f = ais.getFormat();
            if (Math.abs(f.getSampleRate() - 16000f) < 1 && f.getChannels() == 1 && f.getSampleSizeInBits() == 16) return input;
        } catch (Exception e) {
            // fall through to just return input and let sidecar/ffmpeg-less path try; whisper may still read
            log.info("cannot inspect/convert wav ({}), using as-is: {}", e.getMessage(), input);
            return input;
        }
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(input.toFile())) {
            AudioFormat target = new AudioFormat(16000f, 16, 1, true, false);
            try (AudioInputStream conv = AudioSystem.getAudioInputStream(target, ais)) {
                byte[] pcm = conv.readAllBytes();
                float[] samples = new float[pcm.length / 2];
                for (int i = 0; i < samples.length; i++) {
                    int lo = pcm[i*2] & 0xFF; int hi = pcm[i*2+1];
                    short s = (short)((hi<<8)|lo); samples[i] = s / 32768f;
                }
                Path dir = paths.dataDir().resolve("tmp"); Files.createDirectories(dir);
                Path out = Files.createTempFile(dir, "conv16k-", ".wav");
                WavWriter.writeFloatMono16k(out, samples);
                return out;
            }
        } catch (Exception e) {
            log.info("wav conversion unavailable ({}), using original", e.getMessage());
            return input;
        }
    }

    private static String sanitizeTranslation(String s) {
        if (s == null) return null;
        String out = s.replace("\u2581", " ").replace("\u2047", "").replace('\u00A0', ' ');
        String[] lines = out.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i].replaceAll("[ \\t]+", " ").trim());
        }
        String res = sb.toString();
        int start = 0, end = res.length();
        while (start < end && Character.isWhitespace(res.charAt(start))) start++;
        while (end > start && Character.isWhitespace(res.charAt(end - 1))) end--;
        return start <= 0 && end >= res.length() ? res : res.substring(start, end);
    }
    private static String norm(String s){ if(s==null) return null; String v=s.trim(); if(v.isEmpty()||v.equalsIgnoreCase("auto")||v.equalsIgnoreCase("detected")) return null; return v; }
    private static String oneLine(String s){ if(s==null) return ""; String t=s.replace('\n',' ').replace('\r',' '); return t.length()>120?t.substring(0,117)+"...":t; }

    private static void runLiveSimulateFile(String[] args, AppPaths paths, SettingsStore store, HistoryRepository history, EngineFactory.Engines engines) throws Exception {
        String in = argVal(args, "--input", null);
        if (in == null) throw new IllegalArgumentException("--input WAV path is required");
        Path input = Path.of(in).toAbsolutePath().normalize();
        if (!Files.exists(input)) throw new IllegalArgumentException("input not found: " + input);
        boolean save = has(args, "--save-history");
        boolean doTranslate = !has(args, "--no-translate");
        boolean doDiarize = has(args, "--diarize") || has(args, "--with-diarization");
        AppSettings s = store.load();
        String srcHint = norm(argVal(args, "--source-lang", s.source_language));
        String tgt = argVal(args, "--target-lang", s.target_language == null ? "en" : s.target_language);

        java.util.List<com.gek.inference.DiarizationEngine.Segment> diarSegs = java.util.Collections.emptyList();
        if (doDiarize) {
            if (engines == null || engines.diarizer() == null) {
                throw new com.gek.inference.InferenceUnavailableException("No diarization engine available.");
            }
            try {
                diarSegs = engines.diarizer().diarize(input).join();
            } catch (Exception e) {
                Throwable c = e; while (c.getCause() != null) c = c.getCause();
                if (c instanceof com.gek.inference.InferenceUnavailableException) throw (com.gek.inference.InferenceUnavailableException) c;
                throw new RuntimeException(String.valueOf(c.getMessage()), c);
            }
            System.out.println("DIARIZATION_SEGMENTS=" + (diarSegs == null ? 0 : diarSegs.size()));
            if (diarSegs != null) {
                for (int i = 0; i < diarSegs.size(); i++) {
                    com.gek.inference.DiarizationEngine.Segment ds = diarSegs.get(i);
                    System.out.println(String.format(java.util.Locale.ROOT, "DIAR\t%d\t%.3f\t%.3f\t%s", i + 1, ds.start(), ds.end(), ds.speaker()));
                }
            }
        }

        SpeechToTextEngine stt = engines != null ? engines.stt() : null;
        TranslationEngine te = engines != null ? engines.translator() : null;
        if (stt == null) throw new com.gek.inference.InferenceUnavailableException("No STT engine available for --live-simulate-file.");
        if (stt instanceof SidecarClient sc) sc.start();
        if (te instanceof SidecarClient sc2) sc2.start();
        try { stt.warmup().join(); } catch (Exception e) {
            Throwable c = e; while (c.getCause() != null) c = c.getCause();
            if (c instanceof com.gek.inference.InferenceUnavailableException) throw (com.gek.inference.InferenceUnavailableException) c;
            throw new RuntimeException(c.getMessage() == null ? String.valueOf(c) : c.getMessage(), c);
        }

        WavReader.AudioData audio = WavReader.readMono(input, 16000);
        float chunkSec = s.chunk_duration != null ? s.chunk_duration.floatValue() : 0.25f;
        int silenceMs = s.silence_duration_ms != null ? s.silence_duration_ms : 300;
        float minDur = s.min_chunk_duration != null ? s.min_chunk_duration.floatValue() : 0.15f;
        float maxDur = s.max_chunk_duration != null ? s.max_chunk_duration.floatValue() : 4.0f;
        int chunkSamples = Math.max(160, (int) (16000f * chunkSec));

        java.util.List<float[]> segments = new java.util.ArrayList<>();
        SpeechSegmenter seg = new SpeechSegmenter(16000f, 0.01f, silenceMs, minDur, maxDur, 0.2f);
        seg.setOnSegment(segments::add);
        float[] all = audio.samples();
        for (int off = 0; off < all.length; off += chunkSamples) {
            int n = Math.min(chunkSamples, all.length - off);
            float[] c = new float[n];
            System.arraycopy(all, off, c, 0, n);
            seg.accept(new AudioChunk("file-sim", c, System.currentTimeMillis()));
        }
        seg.flush();

        SynonymReplacer syn = SynonymReplacer.load(paths.synonymsDir());
        Path dir = paths.dataDir().resolve("tmp");
        Files.createDirectories(dir);
        String baseSource = "live-sim:file";
        int processed = 0, saved = 0;
        for (int i = 0; i < segments.size(); i++) {
            float[] smp = segments.get(i);
            Path tmp = Files.createTempFile(dir, "livesim-", ".wav");
            try {
                WavWriter.writeFloatMono16k(tmp, smp);
                TranscriptionResult trRes;
                try {
                    trRes = stt.transcribe(tmp, srcHint).join();
                } catch (Exception e) {
                    Throwable c = e; while (c.getCause() != null) c = c.getCause();
                    if (c instanceof com.gek.inference.InferenceUnavailableException) throw (com.gek.inference.InferenceUnavailableException) c;
                    System.out.println("[WARN] segment " + (i + 1) + " stt failed: " + c.getMessage());
                    continue;
                }
                String raw = trRes.text() == null ? "" : trRes.text().trim();
                if (raw.isEmpty()) {
                    if (doDiarize) {
                        String spk = speakerForIndex(diarSegs, i, segments.size(), audio.durationSeconds());
                        System.out.println("SEGMENT " + (i + 1) + " " + spk + ": (empty)");
                    } else {
                        System.out.println("SEGMENT " + (i + 1) + ": (empty)");
                    }
                    continue;
                }
                String detectedLang = norm(trRes.language());
                String src = detectedLang != null ? detectedLang : (srcHint != null ? srcHint : "auto");
                String prepared = syn.apply(raw, src);
                String translated = prepared;
                String effectiveTgt = tgt;
                if (doTranslate && te != null && !tgt.equalsIgnoreCase(src)) {
                    String trSrc = norm(src);
                    if (trSrc == null && te instanceof com.gek.inference.NativeTranslationEngine) {
                        System.out.println("[WARN] segment " + (i + 1) + " translation skipped (source language unknown for native translator)");
                    } else {
                        try {
                            String trOut = te.translate(prepared, trSrc != null ? trSrc : src, tgt).join();
                            trOut = sanitizeTranslation(trOut);
                            boolean repetitive = TranslationPostProcessor.isRepetitive(trOut, prepared);
                            translated = (trOut == null || trOut.isBlank() || repetitive) ? prepared : trOut;
                            if (repetitive) System.out.println("[WARN] segment " + (i + 1) + " translation repetitive/low-confidence; kept transcript");
                        } catch (Exception e) {
                            Throwable c = e; while (c.getCause() != null) c = c.getCause();
                            System.out.println("[WARN] segment " + (i + 1) + " translation failed: " + c.getMessage());
                            translated = prepared;
                        }
                    }
                } else {
                    effectiveTgt = src;
                }
                String speaker = doDiarize ? speakerForIndex(diarSegs, i, segments.size(), audio.durationSeconds()) : null;
                String sourceLabel = speaker != null ? (baseSource + ":" + speaker) : baseSource;
                processed++;
                String spTag = speaker != null ? (" " + speaker) : "";
                System.out.println("SEGMENT " + (i + 1) + spTag + " [" + src + "->" + effectiveTgt + " " + sourceLabel + "] " + oneLine(prepared) + " => " + oneLine(doTranslate ? translated : prepared));
                if (save) {
                    history.save(TranslationRecord.ofNew(prepared, src, doTranslate ? translated : prepared, doTranslate ? effectiveTgt : src, "live-sim", input.toString(), sourceLabel));
                    saved++;
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[OK] live-simulate-file input=%s duration=%.3fs segments=%d processed=%d saved=%d source=%s translate=%s tgt=%s diarize=%s",
                input, audio.durationSeconds(), segments.size(), processed, saved, baseSource, doTranslate, tgt, doDiarize));
        System.out.println("Note: headless simulated live pipeline (file->segment->in-JVM STT->translation->history/callback flow). " + (doDiarize ? "Diarization: in-JVM ORT + PLDA/config (coarse speaker attribution by proportional timeline). " : "") + "No mic/loopback capture used (WSL2-safe).");
    }

    private static String speakerForIndex(java.util.List<com.gek.inference.DiarizationEngine.Segment> diarSegs, int idx, int total, double audioDur) {
        if (diarSegs == null || diarSegs.isEmpty()) return "SPEAKER_00";
        if (diarSegs.size() == 1) return diarSegs.get(0).speaker() == null ? "SPEAKER_00" : diarSegs.get(0).speaker();
        double mid;
        if (audioDur > 0 && total > 0) mid = ((idx + 0.5) / (double) total) * audioDur;
        else mid = 0.0;
        String best = diarSegs.get(0).speaker();
        double bestDist = Double.POSITIVE_INFINITY;
        for (com.gek.inference.DiarizationEngine.Segment ds : diarSegs) {
            if (mid >= ds.start() && mid <= ds.end()) return ds.speaker() == null ? "SPEAKER_00" : ds.speaker();
            double dist = Math.min(Math.abs(mid - ds.start()), Math.abs(mid - ds.end()));
            if (dist < bestDist) { bestDist = dist; best = ds.speaker(); }
        }
        return best == null ? "SPEAKER_00" : best;
    }
}
