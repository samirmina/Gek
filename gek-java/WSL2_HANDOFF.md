# GEK Java — WSL2/Windows Handoff (inject stabilization + Windows runtime)

**Updated:** 2026-11-18 (WSL2/headless pass)  
**Purpose:** authoritative handoff for continuing stabilization of `gek-java/` (Java 21, Swing) from a real Windows host, with emphasis on the `com.gek.inject` package and Windows-only runtime behaviors (focus/caret, hotkeys, injection, audio/loopback, TTS/ducking).  
**Hard rules:** Java-only application logic; local-only inference/services (no remote endpoints). No commits/branches/pushes/PRs/issues/comments, GitHub Actions, or secrets unless explicitly requested. Never commit `.git/` internals. Windows runtime behavior (foreground/focus-lock, global hotkeys, external-caret injection, `WS_EX_NOACTIVATE`/non-activating overlay, GUI, mic/DirectShow/WASAPI/loopback/playback, SAPI, Core Audio duck/restore, helper activation/flash, “GEK never foregrounds”) **must be validated on a real Windows host** (non-headless JDK 21, GUI session, real devices). **WSL2/headless results are not Windows parity proof.** Keep `.deleted` backups for reference until fixed/working; do not delete/simplify to a minimal 1-button UI until behavior is pinned by tests and proven on Windows.

---

## 1) Latest verified baseline (WSL2, headless)

- **Environment:** Linux/WSL2, headless JVM, Java 21.0.13, Maven 3.9.9 (`gek-java/.tools/apache-maven-3.9.9/`). No Xvfb; no wl-copy/xclip/xdotool/ydotool by default (clipboard-tool branches also exercised in-run with `/tmp/faketools` shims on `PATH`).
- **Commands:** `cd gek-java && .tools/apache-maven-3.9.9/bin/mvn -q jacoco:prepare-agent test jacoco:report` (full). Coverage below read from `gek-java/target/site/jacoco/jacoco.csv` after that run.
- **Surefire:** **221 tests, 65 files, 0 failures, 0 errors, 0 skipped**.
- **JaCoCo (overall):** **60.1% instruction** (20056/33349), **50.2% branch** (2228/4442), **63.4% line** (3154/4974).
- **JaCoCo by package (instr / branch / line %):** `com.gek.app` 38.0 / 28.1 / 43.6, `com.gek.audio` 58.3 / 53.4 / 61.9, `com.gek.core` 90.9 / 65.2 / 96.2, `com.gek.db` 95.9 / 60.0 / 95.7, `com.gek.inference` 75.9 / 55.8 / 82.6, `com.gek.inference.diarization` 82.0 / 66.1 / 86.3, `com.gek.inject` **69.0 / 62.4 / 67.6**, `com.gek.text` 89.4 / 76.7 / 96.3, `com.gek.ui` 0.0 / 0.0 / 0.0.
- **Expected/non-fatal noise in this env:** sidecar stderr `ModuleNotFoundError` for `faster_whisper`/`argostranslate`/`pyannote`; expected STT-failure/controller-test warnings and stack traces; headless clipboard/Robot/AWT warnings (e.g. `HeadlessException`, “No X11 DISPLAY…”); missing clipboard-tool warnings when exercising forced/failure branches. Tests still pass.

**Delta vs prior session baseline (before this inject-focused pass):** overall ~57.7%/47.0%/60.3% (192 tests, 56 files) → **60.1%/50.2%/63.4% (221 tests, 65 files)**. `com.gek.inject` ~47.0%/36.5%/41.1% → **69.0%/62.4%/67.6%**.

---

## 2) What was done this session (WSL2, inject-focused)

### 2.1 New characterization/reflection/branch tests added (`gek-java/src/test/java/com/gek/inject/`)
- `VoiceCommandInterpreterMoreTest.java` — additional parse-count/move/arrow, selection/edit/delete variants, literal/fallback/whitespace, command descriptions.
- `VoiceCommandInterpreterCoverageTest.java` — more move/arrow/select/delete/literal/fallback/punctuation and newline/paragraph/tab variants to raise interpreter branch coverage.
- `RobotInputExecutorReflectionTest.java` — `name`/factory/null-execute/headless-failure paths; `mapKey`/`isModifier`/`isMac`/`ctrlOrCmd`; `keyChord`/`repeat`/`tap`/`chord`/`repeatChord`/`redo`/`typeText`/mouse edge behavior via reflection/null-robot and platform toggling; `ImageTransferable` inner class.
- `WindowsTextInjectorMoreTest.java` — name/null/empty/non-empty headless behavior, `forPlatform`, `ImageSelection` transferable.
- `WindowsFocusManagerLinuxTest.java` — non-Windows public API behavior and null-guard private helpers via reflection (JNA HWND type present but no Win32 runtime calls required for null paths).
- `WindowsGlobalHotkeyMoreTest.java` — `parse` variants/modifiers/vk, non-Windows support/lifecycle (no Win32 loop start on Linux), and stop behavior when a thread id is present (Throwable-safe path).
- `ClipboardTextInjectorMoreTest.java` — null/empty/factory, `detectMethod` consistency, `copyLinux`/`pasteLinux` success/failure using shell commands.
- `LinuxGlobalHotkeyReflectionTest.java` — `fire` callback/exception/null handling, file-watch trigger paths (modification/content) with stop, stdin-path start/stop/close.
- `ClipboardTextInjectorForcedMethodsTest.java` — forces internal method selection to exercise Windows-robot/awt/none/unknown and Linux wl-copy/xclip(+ydotool/xdotool/clipboard-only) branches (including failure/exception paths).

### 2.2 Fixes made to get tests stable and green
- Aligned test expectations with **actual** `VoiceCommandInterpreter` semantics (e.g. count parsing in move forms, unrecognized `delete <text>` resolving to a delete command rather than plain typing in that form) instead of changing production behavior.
- Fixed reflection invocation/varargs issues in robot/chord helper tests.
- Avoided starting `WindowsGlobalHotkey`’s Win32/JNA loop on non-Windows in tests (natives unavailable) while still covering parsing, lifecycle guards, and stop/cleanup paths.

### 2.3 `com.gek.inject` coverage after this pass (instr / branch, missed instr)
- **Package:** **69.0% / 62.4%** (2526/3710 instr, 337/542 branch), **67.6% line** (400/584).
- **By class:** `VoiceCommandInterpreter` 97.5% / 85.0% (30), `LinuxGlobalHotkey` 71.2% / 44.4% (65), `ClipboardTextInjector` 59.8% / 47.5% (109, with fake clipboard tools), `WindowsGlobalHotkey` 47.7% / 50.0% (162), `RobotInputExecutor` 40.9% / 52.8% (469), `WindowsTextInjector` 24.5% / 30.0% (117), `WindowsFocusManager` 17.8% / 14.6% (189), `WindowsFocusManager.User32Ex` 0.0% (7), `InputAction` 99.2% / 92.9% (2), inner/record/types at 100% where applicable (`InputExecutor`, `InputAction.Kind`, `NoopTextInjector`, `VoiceCommandInterpreter.Interpretation`, `WindowsGlobalHotkey.HotkeySpec`, `RobotInputExecutor.ImageTransferable`, `WindowsTextInjector.ImageSelection`).

### 2.4 Why >80% for the whole `inject` package is not realistically achievable from WSL2/headless (as-is)
- ~**944 missed instructions** remain concentrated in **Windows/JNA/Robot/clipboard/display** paths: `RobotInputExecutor` success/switch execution needs a working AWT `Robot` + real display/clipboard (headless `new Robot()` and clipboard access fail); `WindowsFocusManager`/`WindowsGlobalHotkey`/`WindowsTextInjector` depend on Win32 `User32`/`Kernel32`/HWND/foreground semantics and Windows paste/focus flows unavailable on Linux.
- `WindowsFocusManager.User32Ex` is a JNA interface stub (not directly coverable without real Win32 calls/mocking strategy). No **Xvfb** installed; X11/WSLg headless constraints block Robot-success paths in this environment.
- Reaching **80% package instruction** requires roughly **+442 covered instructions** (to ~2968/3710) — not practical here without either a different runtime environment (real Windows GUI, or properly working non-headless/Xvfb + seams) or **testability refactors**.

### 2.5 WSL2-feasible follow-ups (if continued later)
- Small additional gains: remaining `VoiceCommandInterpreter` branches (~30 missed), more `LinuxGlobalHotkey` stdin/file paths, a bit more `ClipboardTextInjector`.
- **High-impact (behavior pinned first):** introduce seams to make non-Win32 logic and success/error branches testable headless — e.g. injectable `Robot`/`Clipboard`/process-runner/clock, package-private hooks or adapters around `User32`/foreground checks, and splitting pure decision logic from platform side effects. Consolidate legacy `TextInjector` abstractions vs `InputExecutor` path only when pinned by tests (and Windows proof where relevant).
- `com.gek.ui` remains 0% and is headless-limited; app/flow coverage can continue elsewhere with seams, but Windows runtime proof still gates simplification.

---

## 3) Carried-forward context (critical for Windows work)

### 3.1 Core issue (focus/caret) — root-cause summary
Voice typing must behave like Windows voice typing: **background pipeline + global hotkey toggle + truly non-activating overlay/status; text/commands land at the caret in the external foreground app; GEK never takes foreground during capture→transcribe→inject.** Current failures are a combination of: (a) GEK windows/surfaces activating/stealing focus (main window/dialogs/tray/floating controls not truly Win32 non-activating), (b) target capture invalidated when starting from GEK UI (hotkey-time capture must be authoritative and external-only), (c) Windows foreground-lock preventing reliable restore, and (d) clipboard-paste/Robot injection requiring the exact target control to have focus (no robust `SendInput`/UIA fallbacks yet). Parts are fixable in code but **correctness can only be proven on Windows**.

### 3.2 Runtime/config notes
- `resources/data/settings.json`: `inference_mode="native"`, `audio_loopback="auto"` (must be set/verified to a real **system-output** capture endpoint on Windows, not mic), `audio_mic` set to a specific device (verify on host), `dictation_hotkey="CTRL+SHIFT+SPACE"`, `tts_enabled=false`, `auto_mute_during_tts=false`.
- `WindowsAppDucker` is a placeholder (`isSupported()=false`); loopback via ffmpeg/DirectShow is best-effort (not true WASAPI per-process parity); no Windows whisper shim `.dll`; some translation pairs may be BPE-only/unsupported.
- Active injection path: `InputExecutor`/`RobotInputExecutor` (+ `WindowsFocusManager`, `WindowsTextInjector` helpers). Legacy `TextInjector`/`ClipboardTextInjector`/`WindowsTextInjector`/`NoopTextInjector` APIs/tests still exist (consolidate only when pinned).

### 3.3 Key files
- Handoff: `gek-java/WSL2_HANDOFF.md`
- Startup/overlay/UI: `gek-java/src/main/java/com/gek/app/Main.java`, `gek-java/src/main/java/com/gek/ui/ControlWindow.java`, `FloatingControlWindow.java`, `HudWindow.java`
- Dictation/hotkey/focus/injection: `gek-java/src/main/java/com/gek/app/DictationController.java`, `gek-java/src/main/java/com/gek/inject/WindowsFocusManager.java`, `WindowsTextInjector.java`, `RobotInputExecutor.java`, `ClipboardTextInjector.java`, `WindowsGlobalHotkey.java`, `LinuxGlobalHotkey.java`, `InputExecutor.java`, `InputAction.java`, `VoiceCommandInterpreter.java`
- Audio/TTS/ducking/loopback: `gek-java/src/main/java/com/gek/audio/AutoAudioSelector.java`, `CommandLoopbackCapture.java`, `MicCapture.java`, `WindowsSapiTtsEngine.java`, `WindowsAppDucker.java`
- Capabilities/config: `gek-java/src/main/java/com/gek/core/PlatformSupport.java`, `AppSettings.java`, `AppPaths.java`; runtime config `resources/data/settings.json`
- Reports: `gek-java/target/surefire-reports/*`, `gek-java/target/site/jacoco/index.html`, `gek-java/target/site/jacoco/jacoco.csv`

---

## 4) Instructions for Windows-side Kilo Code (do on real Windows host)

### 4.1 Environment, sync, and baseline (first)
1. Use **Windows 10/11 GUI session, non-headless JDK 21**, Maven on `PATH` (or `gek-java/.tools/apache-maven-3.9.9/bin/mvn` if present), `ffmpeg` on `PATH`, PowerShell/SAPI available, real mic + real system-output capture device/virtual cable as applicable. A Windows Python + venv with `ctranslate2` and `sentencepiece` is required for translation validation.
2. **Sync the full working tree** from WSL2 (many local/uncommitted changes, new tests, and this updated handoff). Do **not** commit/push.
3. Run baseline: `cd gek-java && mvn -q -DskipTests compile && mvn -q jacoco:prepare-agent test jacoco:report`. Record results and compare to WSL2: **221 tests, 65 files, 0 failures/errors/skips**, overall **60.1%/50.2%/63.4%**, `inject` **69.0%/62.4%/67.6%**. Fix any Windows-specific breakage **portably** (preserve Linux/WSL behavior) and keep tests green.
4. Run CLI diagnostics and record output/device names: `--self-test`, `--inference-check`, `--native-info`, `--wsl2-readiness`, `--list-audio-devices` (and `--segment-file` sanity if useful).

### 4.2 P0 (critical): external-caret voice typing, non-activating overlay, no refocus
**Goal:** hotkey-driven dictation/types at the caret in the *external* foreground app; GEK never takes foreground during capture→transcribe→inject.
1. **True non-activating overlay:** make `FloatingControlWindow`/HUD non-activating on Windows (Win32 `WS_EX_NOACTIVATE` and appropriate tool-window/topmost styles via JNA/native helper or equivalent). Mouse interaction must not steal focus; if interaction cannot be made safe, make the overlay passive/indicator-only.
2. **Hotkey-only reliable start/toggle:** global hotkey captures the authoritative external (non-GEK) HWND at press time and is the primary/only safe way to start typing. Do not rely on clicking GEK UI/overlay to begin typing. Audit/remove focus-stealing `setVisible(true)`/`toFront()`/tray/menu/dialog activations during voice-typing flows (`Main`, `ControlWindow`, tray/floating actions); keep management UI strictly on-demand.
3. **Harden target capture/restore/injection:** reject GEK/self/invalid targets always; improve foreground restore within OS limits (`SetForegroundWindow` best-effort plus permitted techniques such as `AttachThreadInput` choreography / `AllowSetForegroundWindow` where applicable); expose definitive success/failure and **never inject into the wrong window**. Prefer/add fallbacks to `SendInput` Unicode and/or UI Automation text insertion where appropriate; keep clipboard+Ctrl+V fallback. Handle UIPI/elevated/IME/secure-field cases explicitly and report clearly on failure. Key files: `WindowsFocusManager`, `RobotInputExecutor`, `WindowsTextInjector`, `InputExecutor`, `WindowsGlobalHotkey`, `DictationController`, `ControlWindow`.
4. **Validate (record evidence):** with caret in **Notepad, a browser text field, and an editor** (name them), use the global hotkey to start/stop and speak/type commands — text/edits land at that caret, GEK never foregrounds, overlay does not steal focus or break caret. Capture pass/fail per app, device names, and hotkey.

### 4.3 P0: eliminate helper-process window/focus leaks
- Launch helpers hidden/non-interactive on Windows (no console flash/activation) without breaking stdout/stderr piping: SAPI PowerShell in `WindowsSapiTtsEngine`, `ffmpeg` in `CommandLoopbackCapture` and device probes in `AutoAudioSelector`, and other `ProcessBuilder` sites as applicable (`ClipboardTextInjector`, `SidecarClient`, `NativeProcessRunner`, `ShellTtsEngine`, `CliRunner`, `PlatformSupport`, `NativeRuntimeProbe`). Verify during dictation/TTS/capture.

### 4.4 P1: audio devices, loopback/playback, translation
1. Set `audio_mic` and `audio_loopback` in `resources/data/settings.json` to **verified** Windows endpoints. `audio_loopback` must be real **system-output** capture (Stereo Mix/VB-CABLE/virtual device or supported `wasapi:` form per implementation), **not** mic. Verify with `ffmpeg -list_devices true -f dshow -i dummy` and real capture.
2. Validate **mic dictation E2E** and **playback/system-audio transcription E2E**. Make capability/readiness truthful (ffmpeg present ≠ usable loopback). Implement true WASAPI endpoint/per-process loopback if parity is required.
3. Provide/configure Windows Python + venv with `ctranslate2`+`sentencepiece`; resolve helper on Windows; validate supported Argos pairs (`resources/models/argos/packages/`) and surface unsupported/BPE-only pairs explicitly.

### 4.5 P2: TTS and per-app ducking
1. Verify Windows SAPI TTS is audible, respects settings, and has no focus/UI side effects.
2. If required, replace `WindowsAppDucker` placeholder with real Core Audio per-app duck/restore (native/COM/JNA/helper) with **guaranteed restore** on stop/error/exit; report support only when functional.

### 4.6 Push `com.gek.inject` coverage on Windows (and keep it meaningful)
1. Re-run coverage on Windows: expect Win32/Robot/clipboard paths to become exercisable; identify remaining gaps in `RobotInputExecutor`, `WindowsFocusManager`, `WindowsGlobalHotkey`, `WindowsTextInjector`, `ClipboardTextInjector`, `LinuxGlobalHotkey`.
2. Add/extend **characterization tests** (pin current behavior before refactors) to cover Windows-success and failure branches now reachable (foreground capture/restore decisions, hotkey register/unregister/message handling where testable, paste/injection success paths, fallbacks). Prefer seams/adapters for untestable OS calls rather than brittle end-to-end-only tests.
3. Target **>80% instruction coverage for `com.gek.inject`** on Windows where practical, but prioritize **correctness of focus/injection behavior** over chasing percentage in paths that require dangerous/unsafe automation; document any intentionally uncovered, environment-gated, or OS-internal branches.
4. Keep full suite green (`mvn -q jacoco:prepare-agent test jacoco:report`) and portable across Windows/Linux.

### 4.7 Consolidation, gating, and evidence (before any UI simplification/deletion)
1. Consolidate injection strategy (single well-defined `InputExecutor` path; retire/merge legacy `TextInjector` implementations) only after behavior is pinned by tests and Windows proof.
2. Keep `PlatformSupport`/capability/status/docs truthful to runtime reality.
3. **Do not delete/simplify to the minimal 1-button UI** until P0 focus/caret/injection (and relevant E2E) are proven on Windows and covered/pinned by tests.
4. **Update this handoff** with: exact commands run, test/coverage totals and `inject` breakdown, device names/endpoints, hotkey(s), external apps tested, pass/fail per checklist item, translation pair results, helper visibility observations, and remaining blockers with concrete evidence.

---

## 5) Known limitations / not yet proven
- P0/P1/P2 Windows runtime behaviors above are **not validated** from WSL2; `com.gek.ui` has 0% coverage and is headless-limited.
- No Xvfb in this WSL2 env; Robot-success and some clipboard paths require a real display or Windows.
- `WindowsFocusManager.User32Ex` and deep Win32 foreground/hotkey internals remain largely uncovered from Linux by nature; Windows/seam work needed.
- Working tree is local/uncommitted and contains many modified/untracked files; no commits created.

## Windows baseline (this session, real Windows host) - 4.1 executed

- **Environment:** Windows 11 GUI, non-headless JVM, OpenJDK Temurin 21.0.12.1+1 LTS, bundled Maven .tools\apache-maven-3.9.9\bin\mvn.cmd (no system mvn). Working tree local/uncommitted.
- **Compile:** `cd gek-java && .tools\apache-maven-3.9.9\bin\mvn.cmd -q -DskipTests compile` -> OK.
- **Tests+coverage:** `mvn -q jacoco:prepare-agent test jacoco:report` -> **221 tests, 65 files, 0 failures, 0 errors, 1 skipped** (WSL2: 221/65/0/0/0). JaCoCo overall **61.6% instr (20546/33349), 51.8% branch (2299/4442), 64.8% line (3224/4974)**. `com.gek.inject` **71.6% instr (2656/3710), 64.4% branch (349/542), 71.6% line (418/584)** (WSL2: 69.0%/62.4%/67.6%). Inject (instr miss/cov, branch miss/cov): ClipboardTextInjector 107/164,20/20; InputAction 2/247,1/13; LinuxGlobalHotkey 65/161,20/16; RobotInputExecutor 391/403,53/74; VoiceCommandInterpreter 30/1169,32/181; WindowsFocusManager 177/53,39/9; WindowsFocusManager.User32Ex 7/0,0/0; WindowsGlobalHotkey 162/148,15/15; WindowsTextInjector 113/42,13/7.
- **Portable Windows breakages fixed (tests only, behavior preserved):** `NativeProcessRunnerTest` OS-conditional cmd/sh; `AppPathsTest` path-separator-safe `Path.endsWith`; `CommandLoopbackCaptureTest` ffmpeg-presence guard for Windows dshow/wasapi; `CliRunnerMoreTest`/`CliRunnerSafeCommandsTest` close `HistoryRepository` (try-with-resources) to release SQLite WAL/SHM handles for `@TempDir` cleanup on Windows.
- **Diagnostics (`com.gek.app.Main` via exec-maven-plugin):** `--platform-info` gui/hotkey/inject/tts=true, loopback/ducking=false, backend none, ffmpeg unavailable. `--list-audio-devices` -> 2 (Primary Sound Capture Driver; Microphone (Logitech G733 Gaming Headset)). `--wsl2-readiness`/`--inference-check`/`--native-info`: STT READY (whisper.cpp/GGML whisper-cli.exe + ggml-base.bin, requested medium fallback), translation NOT READY (no helper venv gek-java/native/.venv with ctranslate2+sentencepiece; 18 usable SentencePiece pairs, 2 BPE-only unusable), diarization READY (ONNX pipeline). `--self-test` ok=5 fail=0. Config: resources/data/settings.json `audio_loopback=auto`, `audio_mic=Microphone (Logitech G733 Gaming Headset)`, `inference_mode=native`, hotkey CTRL+SHIFT+SPACE.
- **P0 hardening (code, pending recompile/retest/Windows proof):** `WindowsFocusManager.restoreForeground()` now attempts best-effort `AttachThreadInput` + `AllowSetForegroundWindow(-1)` choreography when GEK is foreground before restoring/showing the saved external target (attach/detach in finally); still fail-closed if target not secured.
- **Runtime P0/P1/P2 validation still pending (not interactively proven):** hotkey capture->restore->caret injection in external apps with proof GEK never foregrounds, true non-activating overlay behavior, helper window/flash observation, mic/loopback E2E with verified ffmpeg+output device, translation venv/helper, audible SAPI/no side effects, real duck/restore.


## Windows follow-up (this session, after 4.1 baseline)

- **Code hardening (best-effort, no UI simplification):**
  - [`WindowsFocusManager`](src/main/java/com/gek/inject/WindowsFocusManager.java): `restoreForeground()` now attempts `AttachThreadInput` (current->target thread) and `AllowSetForegroundWindow(-1)` when GEK is foreground before restore/show/bring/set-foreground (retries), with attach/detach in `finally`; still fail-closed if the external target is not secured/restored.
  - [`FloatingControlWindow`](src/main/java/com/gek/ui/FloatingControlWindow.java), [`HudWindow`](src/main/java/com/gek/ui/HudWindow.java): apply Windows extended styles `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW` (via JNA on `addNotify`) to reduce activation/focus-steal from overlays/HUD (best-effort; Swing `setFocusableWindowState(false)` remains).
- **Verification after changes:** `mvn -q -DskipTests compile` OK; `mvn -q jacoco:prepare-agent test jacoco:report` **exit 0** on Windows (suite green; expected characterization warning/error logs observed). Interactive Windows runtime proof still required for P0 (hotkey capture->restore->caret injection in external apps with proof GEK never foregrounds/wrong window, true overlay non-activation, helper window/flash absence) and for P1/P2 (ffmpeg+real loopback endpoint E2E, translation venv/helper+pairs, audible SAPI/no side effects, real per-app duck/restore).
- **Blocking environment gaps (current host):** `ffmpeg` not installed (`loopbackCommandAvailable=false`); translation helper venv `gek-java/native/.venv` missing (ctranslate2+sentencepiece). Real device/endpoint and UI-automation/runtime validation cannot be completed headlessly.

## Phase 0 spike — pure-Java translation (this session, Windows host)
**Goal:** complete spike steps 0.1–0.5 to decide feasibility and choose C (staged) vs D (all-at-once). **Result:** investigation complete; **no-Python PoC on this Windows host is blocked** by missing Windows-native CTranslate2/SentencePiece libraries and bindings. Recommendation: **proceed with C (staged, SPM-first)** after obtaining/building Windows+Linux native libs (go/no-go gate below).

### 0.1 Package inventory (SPM vs BPE) — DONE
Source: `resources/models/argos/packages/` (20 packages, all with `model/model.bin`). Tokenizers:
- **SentencePiece (usable by current helper, 18):** `ar->en` (ar_en,1.0), `de->en` (translate-de_en-1_3,1.3), `en->ar` (en_ar,1.0), `en->de` (translate-en_de-1_3,1.3), `en->es` (en_es,1.0), `en->fr` (translate-en_fr-1_9,1.9), `en->it` (en_it,1.0), `en->ja` (en_ja,1.1), `en->ko` (en_ko,1.1), `en->nl` (translate-en_nl-1_8,1.8), `en->sv` (en_sv,1.5), `en->zh` (translate-en_zh-1_9,1.9), `fr->en` (translate-fr_en-1_9,1.9), `it->en` (it_en,1.0), `ja->en` (ja_en,1.1), `ko->en` (ko_en,1.1), `nl->en` (translate-nl_en-1_8,1.8), `zh->en` (translate-zh_en-1_9,1.9). All have `sentencepiece.model`/`source.spm`/`spm.model` (SPM).
- **BPE-only (unusable by current helper, 2):** `en->pl` (translate-en_pl-1_9,1.9, `bpe.model`), `es->en` (translate-es_en-1_9,1.9, `bpe.model`).
- **Scope:** SPM-first path covers **18/20** pairs; BPE requires a compatible BPE tokenizer/decoder (extra work) — see [`gek-java/native/translate_argos.py`](gek-java/native/translate_argos.py:1) and [`NativeRuntimeProbe.findArgosPackage`](gek-java/src/main/java/com/gek/inference/NativeRuntimeProbe.java:307).

### 0.2 Backend choice & feasibility — DONE (decision + host assessment)
- **Decision (recommended):** call **CTranslate2** (CT2 `model/model.bin`) + **SentencePiece** from Java via **JNI/JNA**, shipping native binaries per-OS (project-local, e.g. `gek-java/native/`), analogous to existing shim loading in [`EmbeddedTranslationLib.tryLoad`](gek-java/src/main/java/com/gek/inference/EmbeddedTranslationLib.java:15) but loading pure `ctranslate2`/`sentencepiece` (no embedded CPython, no venv). Rationale: models are CT2 format (best fidelity/perf), avoids Python runtime/venv entirely. **SPM pairs first (18)**; **BPE** (2) as follow-up needing a compatible BPE implementation.
- **Host feasibility (Windows 11, Java 21 Temurin, Maven 3.9.9 project-local):**
  - **Present:** whisper/ggml Windows DLLs in `gek-java/native/bin/` and `.tools/whisper-bin-*`; Linux CT2+SPM artifacts exist only inside a Linux venv at `gek-java/native/.venv/` (e.g. `lib/python3.14/site-packages/ctranslate2.libs/libctranslate2-*.so`, `ctranslate2/_ext.*linux*.so`, `sentencepiece/_sentencepiece.*linux*.so`); existing shims are Linux `.so` (`gek-java/native/libgek_translate_shim.so`, `libgek_whisper_shim.so`).
  - **Missing on this host:** Windows-native `ctranslate2`/`sentencepiece` DLLs, any Java CT2/SentencePiece binding jars, a working Windows Python/venv (`.venv` has `bin/python` but no `Scripts/python.exe`; no system Python) — so neither the current Windows runtime nor a Windows pure-native PoC can run yet.
- **Implication:** a true no-Python PoC on Windows requires **obtaining/building Windows CT2+SPM native libs** (and a JNA/JNI binding) as project-local assets; Linux libs/binaries also needed for parity. This is feasible in principle but needs a lib/toolchain/build step (prebuilt binaries or CMake/MSVC build) not completable in-place on this host as-is.

### 0.3 Tiny no-Python PoC — BLOCKED on this host
- **Status:** not executable here. Blockers: no Windows `ctranslate2`/`sentencepiece` native libs, no Java binding, and existing CT2/SentencePiece binaries are Linux-only (`.so` in `gek-java/native/.venv`).
- **To unblock:** provide/build Windows (and Linux) CT2+SentencePiece shared libraries + a thin JNA/JNI wrapper; place project-locally (e.g. `gek-java/native/`), then run a minimal Java harness loading 1 SPM package (e.g. `en_es` or `en_fr`) and translating sample strings with no Python/venv/process.

### 0.4 Golden comparison baseline — DEFINED, capture BLOCKED on this host
- **Baseline (parity target), per current behavior** ([`gek-java/native/translate_argos.py`](gek-java/native/translate_argos.py:1), [`gek-java/native/translate_shim.c`](gek-java/native/translate_shim.c:1), sanitization in [`NativeTranslationEngine.sanitize`](gek-java/src/main/java/com/gek/inference/NativeTranslationEngine.java:135)/[`EmbeddedTranslationEngine.sanitize`](gek-java/src/main/java/com/gek/inference/EmbeddedTranslationEngine.java:126)): explicit `source/target` (no auto-detect), blank/same-lang passthrough, **line-wise** translation for multi-line input, input cap ~12k chars, decode preferring string pieces with id fallback and cleanup of `▁`/`⁇`/NBSP + whitespace normalization, beam/default max-decoding as helper.
- **Suggested golden cases (to capture when a runtime is available, SPM pairs e.g. en↔es/fr/de/zh/ja/ar):** (a) short sentence, (b) multi-line (3 lines, incl. a blank line) to verify per-line/structure, (c) Unicode/punctuation, (d) longer paragraph, (e) already-same-lang/blank edge. Capture input, pair, and exact output as the parity oracle.
- **Status:** **golden outputs not captured** on this host because no working translation runtime is available (no Windows Python/venv; Linux venv/libs not runnable on Windows; pure-native libs not yet present). Parity must be validated once 0.3 is unblocked.

### 0.5 Findings & go/no-go (C vs D)
- **What’s proven:** package/model assets are ready and well-understood (18 SPM / 2 BPE); Java 21 + Maven toolchain ready; existing architecture already cleanly abstracts translation behind [`com.gek.inference.TranslationEngine`](gek-java/src/main/java/com/gek/inference/TranslationEngine.java:5) with fallback/wiring points ([`NativeTranslationEngine`](gek-java/src/main/java/com/gek/inference/NativeTranslationEngine.java:20), [`NativeRuntimeProbe`](gek-java/src/main/java/com/gek/inference/NativeRuntimeProbe.java:443), [`EngineFactory`](gek-java/src/main/java/com/gek/inference/EngineFactory.java:51), [`CliRunner`](gek-java/src/main/java/com/gek/app/CliRunner.java:120)).
- **Blockers (spike):** no Windows-native CT2/SentencePiece libs or Java binding; goldens/PoC cannot run on this host until libs are provided/built. Tokenizer parity (esp. decode quirks) and BPE support remain the main technical risks.
- **Go/no-go:** **Conditional GO for C (staged, SPM-first)** — the migration is feasible and valuable, but the spike’s runtime proof is pending native libs. **Do not choose D now.**
  - **Next gate:** obtain/build Windows+Linux CT2+SPM natives + JNA/JNI wrapper → run 0.3 PoC + 0.4 goldens on at least SPM pairs. If outputs match and libs package cleanly, proceed with **C** (SPM end-to-end → integrate with fallback → verify → consider BPE → retire Python). If parity/packaging blocks, keep Python path as bridge and resolve the specific blocker.
- **Immediate next actions (unblock 0.3/0.4):** (1) source/build `ctranslate2` and `sentencepiece` shared libs for Windows x64 (+ Linux) and a thin Java binding, stored project-locally; (2) implement minimal load+translate harness for one SPM pair; (3) capture golden cases and compare; (4) record evidence and decide C vs D definitively.

## Session (2026-11-19, Windows host) â€” streaming chat voice typing separated from live captions (core implementation)

**Goal (approved):** introduce a second, decoupled system â€” **chat voice typing** (caret/typing logic) â€” separate from **live captions** (overlay-only), so live captions can be near-instant while chat typing owns streaming partialsâ†’finalizeâ†’voice commands/synonyms/optional translateâ†’caret injection, with **no entanglement** between the two. Injection must work in the environment the app is running from and in any other app. Preserve existing CLI/one-shot/file contracts. Super-high deterministic testing/coverage to prove correctness. Working tree remains local/uncommitted.

### Architecture / separation (implemented)

- **Chat voice typing (caret logic only):** mic â†’ streaming STT session (live partials in a dedicated chat UI) â†’ stop/finalize â†’ `VoiceCommandInterpreter`/`SynonymReplacer`/optional translation (honors `dictation_insert_mode`) â†’ inject at the secured caret target â†’ history `dictation`/`dictation-cmd`. **No HUD/caption coupling, no `SpeechSegmenter` batch path.**
- **Live captions (overlay-only):** mic/loopback/endpoints â†’ `SpeechSegmenter` batch â†’ STT â†’ translate/dedupe â†’ history `live` â†’ `HudWindow` (+ optional TTS/ducking). **No caret injection, no chat coupling.** (Role/output separated; low-latency/streaming refactor for "almost instant" captions still pending.)
- **Shared only safe primitives/backends** (STT/translation engines, audio capture primitives). Chat and live flows use separate controllers, listeners, and UI sinks.

### New / modified files

- **Streaming abstractions (new):** `src/main/java/com/gek/inference/StreamingTranscriptionListener.java` (`onPartial/onFinal/onError/onClosed`), `src/main/java/com/gek/inference/StreamingSttSession.java` (`stop/isRunning/finalTranscript/close`), `src/main/java/com/gek/inference/StreamingSpeechToTextEngine.java` (extends `SpeechToTextEngine`; `startSession`, `streamingAvailable`).
- **Rolling-window streaming session (new):** `src/main/java/com/gek/inference/WindowedStreamingSession.java` â€” backend-agnostic partials by periodic re-decode over a rolling 16 kHz mono window through a one-shot `SpeechToTextEngine` (~700 ms interval, 20 s max window, 0.35 s min, single-flight/coalesced, latest-decode-wins, temp WAVs). Emits `onPartial` on change and `onFinal`+`onClosed` on stop/final decode. Fallback when no persistent warm backend is available.
- **Warm/stream binary discovery (modified):** `src/main/java/com/gek/inference/NativeRuntimeProbe.java` â€” generalized `findWhisperBinary`; added `findWhisperStreamBinary` (`whisper-stream/stream`) and `findWhisperServerBinary` (`whisper-server/server`). Warm binaries probed but **no persistent warm backend integrated yet**.
- **STT streaming support (modified):** `src/main/java/com/gek/inference/NativeSpeechToTextEngine.java` â€” now implements `StreamingSpeechToTextEngine`; vends `WindowedStreamingSession` over the resolved delegate/self (`streamingAvailable` when runtime usable). One-shot/CLI contracts preserved.
- **Chat controller (new, decoupled):** `src/main/java/com/gek/app/ChatVoiceTypingController.java` â€” own continuous mic + streaming session (prefers `StreamingSpeechToTextEngine.startSession`, else `WindowedStreamingSession`), no `SpeechSegmenter`. Live `onPartial` â†’ partial/status listener; `onFinal` â†’ `exec.submit(finalizeText)`: synonyms/`VoiceCommandInterpreter` â†’ `InputExecutor.executeAll` **or** optional translate (per `dictation_insert_mode`) â†’ inject `typeText(out+" ")`; persists `dictation`/`dictation-cmd`; reports injected/not-injected/command status; clears partial. `finalizeForTest` for deterministic coverage.
- **Chat partials UI (new):** `src/main/java/com/gek/ui/ChatTypingWindow.java` â€” dedicated non-activating overlay (`WS_EX_NOACTIVATE|WS_EX_TOOLWINDOW`, bottom-center) showing chat streaming partials/status only (never HUD, never fed by live captions).
- **Wiring (modified):** `src/main/java/com/gek/app/Main.java` constructs/wires `ChatVoiceTypingController` into `ControlWindow` and shuts it down on exit. `src/main/java/com/gek/ui/ControlWindow.java` routes chat partialsâ†’`ChatTypingWindow` and statusâ†’voice-typing status (never HUD) and live transcriptsâ†’`HudWindow` only; hotkey/voice toggle drives chat typing with target capture; `stopAllModes` stops chat/dictation/live.

### Injection policy rework (P0, per user requirement; real-Windows proof pending)

**Requirement:** injection must work in the environment the app is running from and in any other app.
- **`src/main/java/com/gek/inject/WindowsFocusManager.java` (modified):** `captureForeground/captureForegroundIfUseful` capture any valid focused window (including GEK or any other app). `restoreForeground()` secures delivery to the current/remembered valid target (adopts current valid focus, else best-effort restore of last saved via AttachThreadInput/AllowSetForegroundWindow/SetForegroundWindow; fail-closed if none/failed). `hasTarget/hasExternalTarget` reflect any valid secured target. **`GEK_WINDOW_TITLE` rejection removed** â€” non-activating overlays remain the UI-level concern.
- **Consumers:** `RobotInputExecutor` and `WindowsTextInjector` continue to gate on `restoreForeground()` on Windows and now permit any secured foreground target (including GEK) under the new policy.
- **Status:** policy updated and tests green; **"wrong-window" guarantees and no-focus-steal behavior in GEK/external apps require real-Windows interactive validation and possible consolidation.**

### Deterministic tests added / modified

- **New:** `src/test/java/com/gek/inference/WindowedStreamingSessionTest.java` (5) â€” fake in-memory STT: stop emits final+closes, emits partials while audio arrives, empty audio finalizes empty, no duplicate partials when unchanged, stop/close idempotent (single final/close).
- **New:** `src/test/java/com/gek/app/ChatVoiceTypingControllerTest.java` (12) â€” fake STT/translator/input/history: plain transcript injects+persists `dictation`, blank clears partial/no inject, command executes actions+persists `dictation-cmd` (never translated as text), command/injection failures reported, translated insert modes (success/blank/exception/no inject), same-lang skips translation, injection failure still persists record, spacing, auto-lang normalization, injectable executor path. Asserts chat records as `dictation`/`dictation-cmd` (not `live`).
- **Modified:** `src/test/java/com/gek/inject/RobotInputExecutorReflectionTest.java` â€” `basicInfoAndNullExecute` made environment-safe for the permissive policy on headed Windows (no-op/harmless action under GUI, headless path still validates failure) to avoid the headed-Windows regression.

### Verification (Windows 11, JDK 21, Maven 3.9.9, run from `D:\gek`)

- **Targeted (new):** `WindowedStreamingSessionTest,ChatVoiceTypingControllerTest` â†’ **17 run, 0 failures, 0 errors, 0 skipped**.
- **Focused after injection-policy rework:** inject/controller set â†’ **32 run, 0 failures**.
- **Full suite:** `cd gek-java && .tools\apache-maven-3.9.9\bin\mvn.cmd -q -DskipTests=false test` â†’ **Tests run: 280, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS** (JaCoCo report generated). Expected/non-fatal noise in this env: sidecar module warnings in some contexts, headless/clipboard/robot warnings, intentional controller error logs in characterization tests. Existing CLI/one-shot/file contracts preserved; no regressions observed.

### Known limits / not yet complete (this session)

- **Warm/low-latency backend not integrated:** chat partials use `WindowedStreamingSession` re-decoding via the one-shot engine (repeated subprocess decodes on this host) until a persistent warm backend (e.g. `whisper-server` HTTP or streaming process) is wired behind the session API.
- **Live captions still batch-segmented:** `LiveTranslateController` remains on `SpeechSegmenter` (overlay-only, no injection); needs its own low-latency/streaming captions flow for near-instant output while staying fully decoupled from chat typing.
- **No-entanglement proof to harden:** structural separation and initial deterministic tests are in place; further pipeline-isolation/cross-feed tests and coverage hardening are still required to fully prove "nothing entangled" under the approved bar.
- **P0 injection not fully closed:** permissive-anywhere policy implemented, but hotkey/chat typing in GEK and external apps, correct "target not secured" behavior, non-focus-stealing/non-activating overlay interaction, and "never inject into wrong window" guarantees need **real-Windows interactive validation** and possible consolidation.
- **Runtime/env constraints (unchanged):** Windows translation runtime not ready (no Windows Python/venv/CT2+SPM deps; Option C blocked on Windows natives+binding); `ggml-medium.bin` unusable (base used/fallback); loopback/WASAPI and SAPI/ducking require real-host proof; ffmpeg/venv gaps on this host. Run from project root `D:\gek` for correct resource resolution.

### Concrete next gates

1. Integrate a persistent warm STT backend behind `StreamingSttSession` (keep windowed fallback) to reduce partial/final latency.
2. Refactor `LiveTranslateController` to a low-latency/streaming captions path (HUD-only, no injection) fully independent of chat typing.
3. Expand deterministic no-entanglement/pipeline-isolation and coverage tests (chat vs live, partialâ†’finalâ†’inject, command/translate edges) while keeping suite green/portable.
4. Real-Windows interactive proof of P0 injection (GEK + external apps, no focus steal, correct target/security) and P1/P2 (loopback/translation/SAPI/ducking/helper visibility); update evidence.
5. Proceed with Option C translation once Windows+Linux CT2+SPM natives + binding are available (PoC + goldens).


## Session continuation (2026-11-19, Windows host) â€” warm-server STT backend and live-captions streaming path

**Goal:** continue the approved remaining gates (warm/low-latency backend, near-instant live captions independence, no-regression stability) with concrete, test-verified progress.

### Implemented

- **Warm whisper-server STT backend (gate 7, implemented/integrated):** added `src/main/java/com/gek/inference/WhisperServerSttEngine.java` â€” persistent `whisper-server` process (auto port, readiness wait) keeping the GGML model loaded; transcribe via HTTP `POST /inference` (multipart WAV + JSON response parsing via Jackson). Implements `StreamingSpeechToTextEngine` so streaming sessions can run as `WindowedStreamingSession` over the warm server (avoids per-window CLI/model reload). Best-effort `tryCreate` with `gek.stt.disableWarmServer` guard for tests; clean shutdown.
- **Native STT prefers warm backend:** `src/main/java/com/gek/inference/NativeSpeechToTextEngine.java` now attempts `WhisperServerSttEngine.tryCreate(...)` when an STT runtime is usable and uses it as delegate (fallback preserved: in-JVM shim / subprocess CLI). Accelerates repeated transcribe and streaming partial/final decodes when server+model are available (binaries present on this host, e.g. `native/bin/whisper-server.exe`).
- **Live captions streaming/near-instant path (gate 5, advanced):** `src/main/java/com/gek/app/LiveTranslateController.java` refactored to use a `StreamingSttSession` (when STT is streaming-capable) for the live audio feed â€” partials emitted to HUD immediately (overlay-only), finals processed through the existing translate/dedupe/persist/history/TTS pipeline (no injection). `process/processForTest` and batch `SpeechSegmenter` fallback preserved for compatibility and deterministic tests. Captions remain fully decoupled from chat voice typing.

### Verification (Windows 11, JDK 21, Maven 3.9.9, from `D:\gek`, warm server disabled for tests: `-DargLine=-Dgek.stt.disableWarmServer=true`)

- Compile: `mvn -q -f gek-java/pom.xml -DskipTests compile` â†’ **OK**.
- Targeted: `LiveTranslateControllerFlowTest,LiveTranslateOrchestrationTest,ChatVoiceTypingControllerTest,WindowedStreamingSessionTest` â†’ **PASS** (exit 0).
- Full suite: `mvn -q -f gek-java/pom.xml -DskipTests=false "-DargLine=-Dgek.stt.disableWarmServer=true" test` â†’ **BUILD SUCCESS, exit 0** (expected intentional error/warning logs from characterization tests). No regressions observed; CLI/one-shot/file contracts preserved.

### Remaining gates (still pending / require runtime or external validation)

- **Gate 7:** runtime validation of warm `whisper-server` under real workload (latency/partial behavior), tuning/readiness, and fallback behavior on this host.
- **Gate 5:** further low-latency tuning and proof of "almost instant" captions on real devices (mic/loopback/endpoints), while keeping strict decoupling from chat typing.
- **Gate 9:** expand deterministic no-entanglement/pipeline-isolation and coverage tests (cross-feed/chat-vs-live proofs beyond current foundations).
- **Gate 8 (P0 injection):** real-Windows interactive proof that hotkey/chat typing injects correctly in GEK and in external apps with no focus steal, correct "target not secured" handling, and "never inject into wrong window" guarantees (policy implemented; proof pending).
- **P1/P2/env-blocked:** loopback/WASAPI E2E (ffmpeg/endpoint), Windows translation runtime/Option C (CT2+SPM Windows natives+binding, PoC+goldens), SAPI audibility/ducking, helper-process visibility â€” unchanged constraints on this host.


## Session (2026-11-20, Windows host, continuation) — translation-oriented chat voice typing (in-field, no GEK overlay)

**Goal (approved, in progress):** chat voice typing must be translation-oriented and populate/edit in the **currently focused external app's text field/caret** (no GEK overlay, no in-app composer). Stream **translated** partials live into that field (replace-on-update as speech evolves) so the user can edit/review there; **hotkey toggles start/stop only** and stop/finalize **leaves the final translated text in place for review/send (no auto-send)**. Pause/stop auto-updates if a manual edit is detected to avoid clobbering. Keep caret/focus targeting rules and strict chat/live separation.

### Implemented (this session)
- **[`com.gek.app.ChatVoiceTypingController`](src/main/java/com/gek/app/ChatVoiceTypingController.java):** translation-oriented in-field streaming refactor.
  - Streaming partials: sequenced/coalesced handling (`partialSeq`) sanitizes and translates for chat via detected/preferred source → `settings.target_language` (fallback to transcript on unusable/blank/repetitive result or exception; same-lang returns transcript), then **replaces the injected segment in the secured target field**.
  - Replace-on-update: tracks `lastInjectedText`; guarded by `fieldLock`; issues `BACKSPACE(lastInjectedText.length()) + TYPE_TEXT(newText)` (skips no-op/equal). On failed/unsafe replacement sets `autoUpdatePaused=true` and reports target not secured/auto-update paused (best-effort safety against clobbering). State reset on `start()`.
  - Finalize (hotkey stop): invalidates in-flight partials; empty clears streamed segment and reports `stopped (no speech)`. Applies synonyms/voice commands (clears streamed text, executes actions, persists `dictation-cmd`, leaves field text for review). Otherwise translation-oriented finalize to target (same-lang keeps transcript; unavailable/invalid/repetitive/exception falls back to transcript with status note) and **leaves final text in the field for review/edit/send — no auto-send, no trailing space** (`ready:` or fallback note). Persists `dictation` of inserted text (translated when applied, else transcript + fallback lang). Uses accumulating window (`WindowedStreamingSession.ACCUMULATING_MAX_WINDOW_SECONDS`).
- **[`com.gek.ui.ControlWindow`](src/main/java/com/gek/ui/ControlWindow.java):** removed chat voice-typing overlay wiring (`ChatTypingWindow` no longer created/routed/shown). Chat typing is in-field (no GEK overlay); hotkey remains toggle start/stop with secured-target capture; compact status reflects `ready (in field)` / listening / not-injected semantics. **Live captions remain HUD/overlay-only and decoupled.**
- **Overlay retired:** [`com.gek.ui.ChatTypingWindow`](src/main/java/com/gek/ui/ChatTypingWindow.java) and [`com.gek.ui.ChatTypingWindowTest`](src/test/java/com/gek/ui/ChatTypingWindowTest.java) renamed to `.java.disabled` (unwired from build/runtime) to reflect "voice typing overlay gone" without destructive deletion.
- **Tests:** [`com.gek.app.ChatVoiceTypingControllerTest`](src/test/java/com/gek/app/ChatVoiceTypingControllerTest.java) rewritten/extended for translation-oriented in-field UX (deterministic fakes): finalize leaves text in field (no auto-send), translated/fallback/unavailable/exception and Arabic/detected-language cases, command/not-injected/history isolation (`dictation`/`dictation-cmd` only, never `live`), and streaming in-field replace coverage (`partialForTest`: translated replacement via backspace+type, fallback-on-translation-failure).

### Verification (Windows 11, JDK 21, Maven 3.9.9, from `D:\gek`, warm server disabled)
- Targeted: `ChatVoiceTypingControllerTest` — **PASS (BUILD SUCCESS)**.
- Compile: `mvn -q -DskipTests test-compile` — **BUILD SUCCESS**.
- Full deterministic suite: `mvn -DargLine="-Dgek.stt.disableWarmServer=true" test` — **Tests run: 309, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS** (exit 0). JaCoCo report generated (known "execution data does not match" warnings for classes modified in-session). `ChatTypingWindowTest` no longer part of suite (retired).
- Jar rebuilt: `mvn -q -DskipTests package` — **BUILD SUCCESS** (`target/gek-java-0.1.0-SNAPSHOT.jar`).

### Notes / limitations (todo 12)
- **Manual-edit detection while listening (external field):** with Robot/keystroke injection the controller cannot read arbitrary external field contents, so reliable detection of mid-stream user edits is **not fully provable**. Mitigations implemented: tracked last-injected segment, replace-on-update only of that segment, and **auto-update paused on failed/unsafe replacement or lost secured target** to reduce clobbering. A fully robust solution likely requires platform accessibility/UIA integration or a different interaction model — requires host validation.
- **Invariants preserved:** chat typing is caret/in-field only with `dictation`/`dictation-cmd` history (no HUD/live coupling); live captions remain overlay-only/no injection/no chat coupling. Injection policy remains secured-target for any focused app (GEK or external).

### AGENTS.md (checked per request)
- Found [`gek-java/AGENTS.md`](AGENTS.md) (per-project rules template) and vendored `native/whisper.cpp/AGENTS.md`. No `AGENTS.md.` / trailing-dot "little file" found in `gek-java/`; rename/mark instruction could not be applied to a concrete target. GEK remains a desktop Swing/local-inference app — approved GEK plan/invariants take precedence over conflicting template architecture.

### Still pending (gates / host-dependent, for testing today and follow-up)
- **Gate 8 (P0, needs real Windows interactive proof):** in-field streaming replace/finalize in GEK and external apps — secured target, no focus steal, correct "target not secured", never wrong window.
- **Gate 7:** warm `WhisperServerSttEngine` real-workload latency/readiness/tuning.
- **Gate 5 / P1–P2:** real mic/captions E2E, loopback/WASAPI (ffmpeg/endpoint), SAPI audibility/ducking; Arabic spoken recognition quality is STT model/runtime dependent (quantized larger model + `source_language=ar` recommended) and translation runtime availability is host-dependent.
- **Gates 9–10:** deterministic coverage uplift (mind JaCoCo aggregation anomalies), finalize handoff/commit as desired.
