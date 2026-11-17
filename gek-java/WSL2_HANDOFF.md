# GEK Java – handoff / status (WSL2 + Windows P4)

**Last updated:** 2026-11-17 (from WSL2 workspace)  
**Repo/branch:** `samirmina/Gek` → `feature/java-wsl2-baseline` (default)  
**Scope:** verified WSL2/CLI baseline + current Java-side Windows (P4) implementation status. **Windows runtime behavior is not validated in this environment.**

## 1) Verified baseline (WSL2, headless) – done
- **Build/package:** `mvn -q -DskipTests compile|package` → `target/gek-java-0.1.0-SNAPSHOT.jar` (`com.gek.app.Main`). `mvn -q test` OK (as previously verified in this environment).
- **Entrypoints:** CLI flags run headless; no-args on headless JVM exits cleanly with guidance. GUI requires display + non-headless JDK/JRE.
- **In-process inference (no Python sidecar required):**
  - **STT:** JNA libwhisper shim + GGML (`resources/models/whisper-ggml/ggml-medium.bin` active when present). `--transcribe-file` verified.
  - **Translation:** JNA embedded CTranslate2+SentencePiece shim over `resources/models/argos/packages/` + local `gek-java/native/.venv`. `--translate-text` verified (explicit `--source-lang/--target-lang`).
  - **Diarization:** in-JVM ONNX Runtime + Java pipeline (PLDA/VBx) over `resources/models/pyannote/speaker-diarization-community-1/` (`config.yaml`, `plda/*.npz`, `onnx/`). `--diarize-file` and `--live-simulate-file --diarize` verified.
- **Pipelines/tools:** `--segment-file`, `--apply-synonyms`, history CLI, model/platform/native/readiness checks, `--inference-smoke`, simulated live flow `--live-simulate-file` (file → VAD → STT → translation → optional history/diarization). `--list-audio-devices` may report 0 in WSL2/headless (environment limitation).

## 2) Runbook (reproducible)
From `gek-java/`:
```bash
mvn -q -DskipTests package
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --help
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --self-test
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --inference-check
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --native-info
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --wsl2-readiness

# examples (in-JVM, sample file committed in repo)
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --transcribe-file --input native/models/test_tone.wav
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --translate-text --text "Good morning" --source-lang en --target-lang es
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --diarize-file --input native/models/test_tone.wav
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --live-simulate-file --input native/models/test_tone.wav --source-lang en --target-lang es
```
GUI: `java -jar target/gek-java-0.1.0-SNAPSHOT.jar` (non-headless JDK/JRE + display/WSLg/Windows). Windows parity features must be run/validated on a Windows host.

## 3) Windows (P4) – current implementation status

> **Important:** items below marked “implemented/wired” are **code-level only** unless explicitly marked validated. **Windows-host validation is required for every P4 item.**

| Area | Status | Implementation (key files) | Remaining / validation |
|---|---|---|---|
| Global hotkeys into native Windows apps | **Implemented (code), not Windows-validated** | `com.gek.inject.WindowsGlobalHotkey` (JNA `RegisterHotKey` + message loop), wired in `Main`/UI on Windows | Validate on Windows (non-admin/admin as needed), confirm hotkey works across focused native apps, handle conflicts/failures/cleanup gracefully |
| Caret/text injection into focused Windows fields | **Implemented best-effort (code), not Windows-validated** | `com.gek.inject.WindowsTextInjector` / `ClipboardTextInjector` (clipboard-preserving `Ctrl+V` via AWT Robot), `TextInjector.bestEffort()` | Validate paste into common apps (Notepad, browsers, editors), IME/clipboard edge cases, focus/permission behavior; consider alternative injection (SendInput/UIA) if needed |
| Windows-native TTS | **Implemented best-effort (code), not Windows-validated** | `com.gek.audio.WindowsSapiTtsEngine` (PowerShell + `System.Speech.Synthesis`), selected on Windows in `Main` | Validate on Windows (PowerShell/`.NET` speech availability), voice/lang selection behavior, stop/cancel, long text, UI responsiveness |
| System/loopback capture | **Partial / best-effort (not full WASAPI parity), not Windows-validated** | `com.gek.audio.CommandLoopbackCapture` (ffmpeg + DirectShow device via `settings.audio_loopback`, e.g. DirectShow device name or `wasapi:<device>` where supported), `PlatformSupport`/`CliRunner`/`ControlWindow` messaging | Requires Windows host + `ffmpeg` + correct capture device config (Stereo Mix/virtual cable/etc.). **Not native WASAPI endpoint/session loopback.** Validate capture, latency, format, device selection UX/fallbacks |
| Core Audio per-app ducking | **Not implemented (placeholder/hook only)** | `com.gek.audio.WindowsAppDucker` (`isSupported()` false, no real ducking), wired on Windows in `Main` | Implement functional per-app ducking via native/Core Audio session APIs (JNA/JNI/COM helper) as required; integrate restore semantics and report actual active state |
| Full end-to-end dictation/live-translate parity on Windows | **Pending** | Orchestration/UI/controllers exist; Windows integrations above are partial/unvalidated | Run full flows on Windows (mic + loopback, hotkeys, injection, TTS, ducking when implemented), fix issues, confirm parity and UX |

**P4 status:** **in progress** (code-level partial integrations present; Windows-host validation and remaining native parity work required).

## 4) Remaining work checklist (for Windows session / IDE)

### Must complete/validate for P4
- [ ] **Build & run on Windows:** `mvn -q -DskipTests package` and `mvn -q test` with non-headless JDK 21; launch GUI and CLI.
- [ ] **Global hotkey:** verify `RegisterHotKey` works in real usage, hotkey config/parsing, conflicts, start/stop lifecycle, and behavior across target apps.
- [ ] **Text injection:** verify caret injection in target apps; improve method (e.g. SendInput/UI Automation) if clipboard-paste is insufficient.
- [ ] **Windows TTS:** verify `WindowsSapiTtsEngine` on target Windows builds; ensure voice/language mapping, interruption/stop, and error handling are acceptable.
- [ ] **Loopback capture parity:** validate ffmpeg/DirectShow path with real devices; decide/implement true **WASAPI** endpoint loopback if required for parity (device enumeration, render loopback, format/resampling, stability).
- [ ] **Per-app ducking:** replace placeholder with functional **Core Audio** per-app/session ducking (and restore) via native integration/helper; expose accurate capability/status.
- [ ] **End-to-end parity:** validate dictation and live-translate flows on Windows host (audio devices, latency, diarization/translation pipeline, history/UI, hotkey-driven flows).
- [ ] **Docs/settings UX:** document required Windows prerequisites (ffmpeg, capture device names, permissions), and update settings/help/UI as needed.

### Optional / follow-ups
- [ ] Add/adjust tests for Windows-specific logic where feasible (parsing, command construction, capability reporting) without requiring Windows-only runtime in CI.
- [ ] Re-validate on WSL2 after Windows changes to avoid regressions.
- [ ] Address asset gaps if needed (e.g. GGML `large-v3`, SentencePiece variants for BPE-only pairs like `en->pl`, `es->en`).
- [ ] Optional diarization parity hardening (strict community-1 checkpoint-matched export) if required.

## 5) Environment/asset notes
- **WSL2 limits:** no Java Sound mic/loopback devices and headless JVM in this session (environment, not app blockers).
- **Assets not bundled:** `resources/models/**`, `resources/data/**`, `gek-java/native/.venv/`, `gek-java/native/whisper.cpp/` are local-only unless added explicitly; repo includes `gek-java/native/models/test_tone.wav`.
- **Translation:** BPE-only Argos packages unsupported in in-process runtime (currently `en->pl`, `es->en` where applicable). Explicit `--source-lang/--target-lang` required.
- **Sidecar:** optional/explicit fallback only (`--use-sidecar` / env/settings).

## 6) Key paths
- **In repo:** `gek-java/src/main/java/com/gek/...`, `gek-java/README.md`, `gek-java/WSL2_HANDOFF.md`, `gek-java/native/{libgek_*_shim.so,translate_argos.py,*_shim.c,models/test_tone.wav}`
- **Build output (local):** `gek-java/target/gek-java-0.1.0-SNAPSHOT.jar`
- **Local-only:** `resources/models/**`, `resources/data/**`, `gek-java/native/.venv/`, `gek-java/native/whisper.cpp/`
