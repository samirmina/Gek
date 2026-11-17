# GEK Java – WSL2 handoff / runbook

**Date:** 2026-11-16  
**Environment:** WSL2, headless JVM (`/mnt/d/gek`, `gek-java/`)  
**Scope:** WSL2-feasible work and the verified Java-native baseline. Windows-only parity (P4) is deferred to a Windows session.

## 1) Verified baseline (works now in WSL2)
Build, headless CLI, and in-process inference are green on this baseline:

- **Build/package:** `mvn -q -DskipTests compile|package` OK → `target/gek-java-0.1.0-SNAPSHOT.jar` (`com.gek.app.Main`).
- **Tests:** `mvn -q test` OK.
- **Entrypoints:** `--help` and CLI flags run headless; `--self-test` reports core checks OK (settings/DB/assets/platform). No-args with a headless JVM prints a clear message (WSLg-aware) and exits 0 (no crash).
- **Inference (all native/in-JVM, `--inference-check` ready):**
  - **STT:** in-process JNA `libwhisper` shim + GGML (`resources/models/whisper-ggml/ggml-medium.bin` active; base/small/tiny present). `--transcribe-file` verified.
  - **Translation:** in-process JNA embedded CT2+SentencePiece shim over `resources/models/argos/packages/` + `gek-java/native/.venv`. `--translate-text` verified (explicit `--source-lang/--target-lang`).
  - **Diarization:** in-JVM ONNX Runtime + Java pipeline (PLDA/VBx) over `resources/models/pyannote/speaker-diarization-community-1/` (`config.yaml`, `plda/*.npz`, `onnx/` artifacts). `--diarize-file` and `--live-simulate-file --diarize` verified.
- **Pipelines/tools:** `--segment-file`, `--apply-synonyms`, `--history-recent|--history-search`, `--model-catalog`, `--platform-info`, `--native-info`, `--wsl2-readiness`, `--inference-smoke`, and headless simulated live flow `--live-simulate-file` (file → VAD → in-JVM STT → in-JVM translation → optional history, optional diarization attribution). No Python sidecar required.

## 2) Runbook (reproducible)
From `gek-java/`:
```bash
mvn -q -DskipTests package
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --help
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --self-test
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --inference-check
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --native-info
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --wsl2-readiness

# examples (in-JVM, WSL2-safe)
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --transcribe-file --input native/models/test_tone.wav
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --translate-text --text "Good morning" --source-lang en --target-lang es
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --diarize-file --input native/models/test_tone.wav
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --live-simulate-file --input native/models/test_tone.wav --source-lang en --target-lang es
```
GUI: `java -jar target/gek-java-0.1.0-SNAPSHOT.jar` (no args) requires a display and a **non-headless** JDK/JRE (e.g. WSLg/Windows/X11); headless JVM reports and exits cleanly.

## 3) Boundaries
### Environment-limited in this WSL2 session (not code blockers)
- **No Java Sound input/loopback devices** (`--list-audio-devices` → 0): real mic and real system-loopback capture cannot be exercised here.
- **Headless JVM:** visual Swing validation not possible here.

### Windows-only, deferred (P4)
Requires a Windows session to implement/validate (Java-first via JNA/JNI where appropriate):
- WASAPI/system loopback of Windows-host audio endpoints
- Global hotkeys into native Windows apps
- Caret/text injection into focused Windows text fields
- Core Audio per-app ducking
- Windows-native TTS and Windows UI/app/process integration
- Full end-to-end dictation/live-translate parity on Windows host

## 4) Known asset/runtime notes (factual)
- **STT:** GGML `tiny/base/small/medium` present; no local GGML `large-v3`. Requested model falls back to an available/valid GGML.
- **Translation:** SentencePiece pairs usable where packaged; BPE-only packages are not supported by the in-process runtime (currently `en->pl`, `es->en`). Explicit `--source-lang/--target-lang` required.
- **Diarization:** in-JVM ORT pipeline is ready and runnable with current `onnx/` artifacts + local `config.yaml`/`plda`. Current artifacts provide a functional baseline; strict community-1 checkpoint-matched export/parity is a separate, optional hardening step (environment/tooling dependent) and is not blocking the verified baseline.
- **Sidecar:** optional/explicit fallback only; not required for the verified native flows. System Python ML deps not assumed.

## 5) Phase status (aligned)
- **P0–P2:** complete (native/in-JVM STT and translation).
- **P3 (WSL2-feasible, simulated):** complete (`--live-simulate-file`, headless, no mic/loopback).
- **P5 (WSL2-feasible integration):** complete as in-JVM ORT pipeline (VBx) with functional baseline; optional parity hardening is separate from this baseline.
- **P4:** pending, Windows-only.

## 6) Next steps (when applicable)
1. **Windows (P4):** implement/validate WASAPI loopback, global hotkeys, text injection, ducking, Windows TTS/integration on a Windows session and package/run the same Java app there.
2. **Optional:** improve real-device capture coverage when audio devices are exposed (WSLg/Windows), and address asset gaps (e.g. add GGML `large-v3`, provide SentencePiece variants for BPE-only pairs) as needed.

## 7) Key paths
- Jar (build output, not committed): `gek-java/target/gek-java-0.1.0-SNAPSHOT.jar` (`com.gek.app.Main`)
- Source/docs (in repo): `gek-java/src/main/java/com/gek/...`, `gek-java/README.md`, `gek-java/WSL2_HANDOFF.md`
- Native runtimes/helpers (in repo): `gek-java/native/libgek_whisper_shim.so`, `gek-java/native/libgek_translate_shim.so`, `gek-java/native/translate_argos.py`, `gek-java/native/*_shim.c`
- Local-only (not bundled): `gek-java/native/.venv/`, `gek-java/native/whisper.cpp/`, model/runtime assets under `resources/models/...`, and app data under `resources/data/`
- Sample (in repo): `gek-java/native/models/test_tone.wav`
