package com.gek.app;

import com.gek.audio.AppDucker;
import com.gek.audio.LinuxNoopAppDucker;
import com.gek.audio.NoopAppDucker;
import com.gek.audio.NoopTtsEngine;
import com.gek.audio.ShellTtsEngine;
import com.gek.audio.TtsEngine;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.core.PlatformSupport;
import com.gek.core.SettingsStore;
import com.gek.db.HistoryRepository;
import com.gek.inference.EngineFactory;
import com.gek.inference.SidecarClient;
import com.gek.inject.GlobalHotkey;
import com.gek.inject.TextInjector;
import com.gek.inject.WindowsGlobalHotkey;
import com.gek.inject.WindowsTextInjector;
import com.gek.ui.ControlWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppPaths paths = AppPaths.resolve();
        System.setProperty("GEK_LOG_DIR", paths.logsDir().toString());
        Files.createDirectories(paths.logsDir());

        SettingsStore store = new SettingsStore(paths.settingsPath());
        AppSettings settings = store.load();
        PlatformSupport.Capabilities caps = PlatformSupport.capabilities();
        log.info("GEK Java starting. root={} os={} wsl={} wslg={} gui={} hotkey={} inject={} loopback={} tts={} duck={}",
                paths.projectRoot(), System.getProperty("os.name"), caps.wsl(), caps.wslg(), caps.gui(),
                caps.windowsGlobalHotkey(), caps.windowsCaretInjection() || caps.linuxTextInjectionPossible(),
                caps.windowsWasapiLoopback(), caps.windowsSapiTts() || caps.linuxLocalTtsPossible(), caps.windowsCoreAudioDucking());

        HistoryRepository history = new HistoryRepository(paths.dbPath());
        int count = 0; try { count = history.count(); } catch (Exception ignored) {}
        log.info("History DB ready: {} ({} rows)", paths.dbPath(), count);

        boolean forceSidecar = hasArg(args, "--use-sidecar") || envTruthy("GEK_USE_SIDECAR") || "sidecar".equalsIgnoreCase(System.getenv("GEK_INFERENCE_MODE"));
        if (forceSidecar) settings.inference_mode = "sidecar";
        boolean explicitSidecar = forceSidecar
                || "sidecar".equalsIgnoreCase(settings.inference_mode)
                || "python".equalsIgnoreCase(settings.inference_mode);
        EngineFactory.Engines engines = EngineFactory.create(paths, settings, explicitSidecar);
        TextInjector injector = WindowsTextInjector.bestEffort();
        TtsEngine tts = PlatformSupport.isLinux() ? new ShellTtsEngine() : new NoopTtsEngine();
        AppDucker ducker = PlatformSupport.isWindows() ? new NoopAppDucker() : new LinuxNoopAppDucker();
        DictationController dictation = new DictationController(paths, settings, engines.stt(), engines.translator(), injector, history);
        LiveTranslateController live = new LiveTranslateController(paths, settings, engines.stt(), engines.translator(), history);
        live.setTtsEngine(tts);
        live.setDucker(ducker);

        if (hasArg(args, "--help") || hasArg(args, "-h")) { printHelp(); closeQuiet(engines, history); return; }
        if (hasArg(args, "--self-test") || hasArg(args, "--healthcheck")) { runSelfTest(paths, store, history, engines, args); return; }
        if (hasArg(args, "--assets-check")) { runAssetsCheck(paths, history, engines); return; }
        if (CliRunner.handleIfRequested(args, paths, store, history, engines)) { closeQuiet(engines, history); return; }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            if (caps.wslg()) {
                System.out.println("GEK Java: WSLg/display detected but this Java runtime is headless (AWT GUI unavailable). Install a full (non-headless) JDK/JRE to run the Swing UI.");
            } else {
                System.out.println("GEK Java: headless environment detected. GUI requires a display (Windows, X11/Wayland, or WSLg).");
            }
            System.out.println("Use --self-test for checks. Use --help for options.");
            closeQuiet(engines, history);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            ControlWindow[] ref = new ControlWindow[1];
            GlobalHotkey hotkey = null;
            try {
                if (PlatformSupport.isWindows()) {
                    WindowsGlobalHotkey.HotkeySpec hs = WindowsGlobalHotkey.parse(settings.dictation_hotkey);
                    hotkey = new WindowsGlobalHotkey(0x4711, hs.modifiers(), hs.vk(), () -> { if (ref[0] != null) ref[0].toggleDictationFromHotkey(); });
                    hotkey.start();
                } else if (PlatformSupport.isLinux()) {
                    com.gek.inject.LinuxGlobalHotkey lhk = new com.gek.inject.LinuxGlobalHotkey(() -> { if (ref[0] != null) ref[0].toggleDictationFromHotkey(); });
                    lhk.start();
                    hotkey = lhk;
                }
            } catch (Throwable t) { log.warn("Global hotkey init failed: {}", t.getMessage()); }
            ControlWindow w = new ControlWindow(paths, settings, store, dictation, live, engines.sidecarOrNull(), hotkey, history, caps, injector, tts, ducker);
            ref[0] = w;
            w.setVisible(true);
            GlobalHotkey hk = hotkey;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (hk != null) hk.close(); } catch (Exception ignored) {}
                try { dictation.close(); } catch (Exception ignored) {}
                try { live.close(); } catch (Exception ignored) {}
                try { engines.close(); } catch (Exception ignored) {}
                try { history.close(); } catch (Exception ignored) {}
            }, "gek-shutdown"));
        });
    }

    private static boolean hasArg(String[] args, String name) {
        if (args == null) return false;
        for (String a : args) if (name.equalsIgnoreCase(a)) return true;
        return false;
    }

    private static boolean envTruthy(String name) {
        String v = System.getenv(name);
        if (v == null) return false;
        String t = v.trim().toLowerCase();
        return "1".equals(t) || "true".equals(t) || "yes".equals(t) || "on".equals(t);
    }

    private static void printHelp() {
        System.out.println("GEK Java (Java-native migration; Python sidecar is optional fallback)");
        System.out.println("Usage: java -jar gek-java-<version>.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --help, -h        Show this help");
        System.out.println("  --self-test       Run checks (settings, DB, assets, audio devices, platform caps; sidecar ping only if sidecar enabled)");
        System.out.println("  --self-test --warmup  Also attempt sidecar warmup/loading of local models (requires --use-sidecar or inference_mode=sidecar and Python ML deps)");
        System.out.println("  --assets-check    Print local asset/model inventory");
        System.out.println("  --platform-info   Print platform/WSL2 capability JSON");
        System.out.println("  --doctor          Alias for detailed --self-test checks");
        System.out.println("  --inference-check Assess local model assets and inference readiness (pure diagnostics, no model execution)");
        System.out.println("  --wsl2-readiness  Report what is feasible now in WSL2 vs needs setup vs Windows-required");
        System.out.println("  --model-catalog   List local Whisper/Argos/Pyannote assets and translation pairs (pure Java)");
        System.out.println("  --history-recent [--limit N]        Print recent history rows");
        System.out.println("  --history-search --query Q [--limit N]");
        System.out.println("  --translate-text --text T [--source-lang X] [--target-lang Y]   (uses native translation when available, else sidecar fallback)");
        System.out.println("  --transcribe-file --input file.wav [--translate] [--source-lang X] [--target-lang Y] [--save-history]   (uses native STT when available; translation requires native/runtime or sidecar fallback)");
        System.out.println("  --segment-file --input file.wav [--write-chunks]  Segment WAV using Java VAD pipeline (no Python/inference)");
        System.out.println("  --live-simulate-file --input file.wav [--source-lang X] [--target-lang Y] [--save-history] [--no-translate] [--diarize]  Simulated live pipeline + optional diarization (no mic/loopback, WSL2-safe)");
        System.out.println("  --diarize-file --input file.wav                   Diarize WAV (native ORT pipeline when ONNX models+ORT present)");
        System.out.println("  --apply-synonyms --text T --lang L             Apply local synonym JSON (pure Java)");
        System.out.println("  --list-audio-devices                           List Java Sound input devices");
        System.out.println("  --native-info      Show effective inference mode and local model/runtime status");
        System.out.println("  --inference-smoke  Warm and run smoke STT/translate using available native runtime, or sidecar when enabled");
        System.out.println("  --use-sidecar      Explicitly enable temporary Python sidecar fallback for inference (default is Java-native mode)");
        System.out.println();
        System.out.println("Inference mode: default is 'native' (no Python dependency). Set inference_mode=sidecar in settings or pass --use-sidecar for temporary fallback when Python ML deps/models are available.");
        System.out.println("No args: launch Swing UI (Windows, X11/Wayland, or WSLg). In WSL2, GUI/caret-inject/loopback/ducking parity depends on host tools/runtime.");
    }

    public static void runSelfTestPublic(AppPaths paths, SettingsStore store, HistoryRepository history, EngineFactory.Engines engines, String[] args) { runSelfTest(paths, store, history, engines, args); }
    private static void runSelfTest(AppPaths paths, SettingsStore store, HistoryRepository history, EngineFactory.Engines engines, String[] args) {
        int ok = 0, fail = 0;
        com.gek.core.AppSettings settings = com.gek.core.AppSettings.defaults();
        PlatformSupport.Capabilities caps = PlatformSupport.capabilities();
        System.out.println("[INFO] platform os=" + System.getProperty("os.name") + " wsl=" + caps.wsl() + " wslg=" + caps.wslg() + " gui=" + caps.gui()
                + " windowsHotkey=" + caps.windowsGlobalHotkey() + " windowsInject=" + caps.windowsCaretInjection()
                + " windowsLoopback=" + caps.windowsWasapiLoopback() + " windowsDucking=" + caps.windowsCoreAudioDucking()
                + " windowsTts=" + caps.windowsSapiTts() + " linuxInjectPossible=" + caps.linuxTextInjectionPossible() + " linuxTtsPossible=" + caps.linuxLocalTtsPossible());
        try { settings = store.load(); System.out.println("[OK] settings load/save path: " + paths.settingsPath()); ok++; } catch (Exception e) { System.out.println("[FAIL] settings: " + e.getMessage()); fail++; }
        try { int c = history.count(); System.out.println("[OK] history db: " + paths.dbPath() + " rows=" + c); ok++; } catch (Exception e) { System.out.println("[FAIL] history: " + e.getMessage()); fail++; }
        try { var mics = com.gek.audio.AudioDevices.inputDeviceNames(); System.out.println("[OK] audio input devices enumerated: " + mics.size()); ok++; } catch (Throwable e) { System.out.println("[WARN] audio devices: " + e.getMessage()); }
        try {
            var report = com.gek.core.AssetInspector.inspect(paths);
            long completeWhisper = report.whisperModels.stream().filter(w -> w.complete).count();
            System.out.println("[OK] assets: whisper complete=" + completeWhisper + "/" + report.whisperModels.size() + " argos=" + report.argosPackages + " pyannote=" + report.pyannoteComplete + " synonyms=" + report.synonymFiles);
            ok++;
        } catch (Exception e) { System.out.println("[WARN] assets: " + e.getMessage()); }
        System.out.println("[INFO] inference mode=" + engines.mode() + " note=" + engines.note());
        System.out.println("[INFO] loopback command available=" + com.gek.audio.CommandLoopbackCapture.isSupported() + " backend=" + com.gek.audio.CommandLoopbackCapture.backendName());
        System.out.println("[INFO] linux hotkey fallback available=" + com.gek.inject.LinuxGlobalHotkey.availableOnPlatform());
        SidecarClient sc = engines.sidecarOrNull();
        if (sc != null) {
            System.out.println("[INFO] sidecar python=" + sc.resolvedPython() + " script=" + sc.resolvedScript() + " exists=" + java.nio.file.Files.exists(sc.resolvedScript())
                    + " model=" + settings.whisper_model + " device=" + settings.whisper_device + " compute=" + settings.whisper_compute_type);
            try { boolean pong = sc.ping().join(); System.out.println(pong ? "[OK] sidecar ping" : "[FAIL] sidecar ping false"); if (pong) ok++; else fail++; } catch (Exception e) { System.out.println("[FAIL] sidecar ping: " + root(e)); fail++; }
            boolean doWarm = hasArg(args, "--warmup");
            if (doWarm) {
                try {
                    com.fasterxml.jackson.databind.JsonNode r = sc.warmupDetailed().join();
                    boolean w = r.path("whisper").asBoolean(false);
                    boolean a = r.path("argos").asBoolean(false);
                    boolean d = r.path("diarizer").asBoolean(false);
                    com.fasterxml.jackson.databind.JsonNode errs = r.path("errors");
                    String summary = "whisper=" + w + " argos=" + a + " diarizer=" + d;
                    if (errs != null && errs.isObject() && errs.size() > 0) {
                        java.util.List<String> parts = new java.util.ArrayList<>();
                        errs.fieldNames().forEachRemaining(k -> parts.add(k + ": " + errs.path(k).asText()));
                        System.out.println("[WARN] sidecar warmup partial/unavailable (" + summary + ") errors={" + String.join("; ", parts) + "}");
                    } else {
                        System.out.println("[OK] sidecar warmup (" + summary + ")");
                        ok++;
                    }
                } catch (Exception e) { System.out.println("[WARN] sidecar warmup failed (expected if ML deps/models unavailable): " + root(e)); }
            } else {
                System.out.println("[SKIP] sidecar warmup (pass --warmup to attempt loading local models)");
            }
        } else {
            System.out.println("[INFO] Java-native inference selected (no Python sidecar). Native STT/translation/diarization runtimes: NOT YET IMPLEMENTED (Option C migration pending).");
            System.out.println("[OK] local model assets cataloged for future native engines (see --model-catalog)"); ok++;
            if (hasArg(args, "--warmup")) {
                System.out.println("[SKIP] --warmup has no effect in native mode (no sidecar). Use --use-sidecar with Python ML deps for temporary sidecar warmup.");
            }
        }
        System.out.println("[INFO] native inference parity verdict: " + nativeVerdict(caps));
        System.out.println("Self-test complete. ok=" + ok + " fail=" + fail);
        closeQuiet(engines, history);
        if (fail > 0) System.exit(1);
    }

    private static String nativeVerdict(PlatformSupport.Capabilities c) {
        StringBuilder sb = new StringBuilder();
        sb.append("core UI/history/settings/Java pipeline tools can run on Linux/WSL2 (no Python required)");
        if (c.wslg()) sb.append("; WSLg display detected (GUI launchable in this WSL env)");
        else sb.append("; GUI requires display server");
        sb.append(". Missing for full GEK parity on pure WSL2: Windows-global hotkey into Windows apps, caret injection into Windows apps, WASAPI system loopback, Core Audio app ducking, SAPI5 TTS.");
        sb.append(" Linux substitutes (local clipboard/paste tools, local TTS, Pulse/PipeWire monitor) can cover WSLg Linux-desktop workflows but not Windows-host app control.");
        return sb.toString();
    }

    private static void runAssetsCheck(AppPaths paths, HistoryRepository history, EngineFactory.Engines engines) {
        try {
            var report = com.gek.core.AssetInspector.inspect(paths);
            System.out.print(report.toText());
        } catch (Exception e) {
            System.out.println("Asset check failed: " + e.getMessage());
        }
        closeQuiet(engines, history);
    }

    private static void closeQuiet(EngineFactory.Engines engines, HistoryRepository history) {
        try { if (engines != null) engines.close(); } catch (Exception ignored) {}
        try { history.close(); } catch (Exception ignored) {}
    }

    private static String root(Throwable t) {
        Throwable c = t; while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
