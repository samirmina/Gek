# GEK Java (Java-first, Swing)

Java 21 desktop port of GEK (voice capture → STT → translation → history/UI).  
**Default runtime:** Java-native, in-process inference (no Python sidecar). Sidecar is an explicit fallback only (`--use-sidecar` / `GEK_USE_SIDECAR=1|true|yes|on` / `inference_mode=sidecar|python`).

Target for full end-to-end parity is **Windows**. This README is the runbook for the current, verified WSL2 baseline and the Windows-only (P4) work that remains.

## Prerequisites
- Java 21 (a **non-headless** JDK/JRE is required to launch the Swing UI; headless is fine for CLI)
- Maven 3.9+
- Project assets/models present under `resources/` (see *Assets & resolution*)
- Run commands from `gek-java/` unless noted; paths below assume that working directory

## Build, test, package
```bash
# compile
mvn -q -DskipTests compile

# tests
mvn -q test

# runnable shaded jar
mvn -q -DskipTests package
# -> target/gek-java-0.1.0-SNAPSHOT.jar (Main-Class: com.gek.app.Main)
```

## Entrypoints & run path
```bash
# CLI (headless-safe)
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --help
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --self-test
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --inference-check
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --native-info
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --wsl2-readiness

# GUI (Swing) — requires a display and a non-headless JDK/JRE
java -jar target/gek-java-0.1.0-SNAPSHOT.jar
```
- **No args, headless JVM:** prints a clear message (notes WSLg/display if detected) and exits 0; use `--self-test`/CLI. It does not crash.
- **Inference mode:** defaults to `native` (in-process JVM engines). Sidecar is never implicit; enable only when needed and deps are available with `--use-sidecar`.

## Works now in WSL2 (verified baseline)
Headless CLI/diagnostics and in-process inference run in this environment (no Python sidecar required):

| Area | Commands / behavior |
|---|---|
| Diagnostics & readiness | `--help`, `--self-test` [--warmup only with sidecar], `--doctor`, `--assets-check`, `--platform-info`, `--model-catalog`, `--native-info`, `--inference-check`, `--wsl2-readiness` |
| History & data | `--history-recent [--limit N]`, `--history-search --query Q [--limit N]` (SQLite/FTS store under `resources/data/`) |
| Pure-Java pipeline | `--segment-file --input <wav> [--write-chunks]`, `--apply-synonyms --text T --lang L`, `--list-audio-devices` |
| STT (in-JVM, libwhisper JNA shim + GGML) | `--transcribe-file --input <wav> [--source-lang X] [--translate --target-lang Y] [--save-history]` |
| Translation (in-JVM, embedded CTranslate2+SentencePiece) | `--translate-text --text T --source-lang X --target-lang Y` (explicit pair required) |
| Diarization (in-JVM ONNX Runtime + Java pipeline) | `--diarize-file --input <wav>` |
| Simulated live pipeline (no mic/loopback) | `--live-simulate-file --input <wav> [--source-lang X] [--target-lang Y] [--save-history] [--no-translate] [--diarize]` (file → VAD → STT → translation → optional history/speaker attribution) |
| Smoke | `--inference-smoke [--target-lang Y]` (uses available native runtime) |

`--inference-check` reports the effective/native readiness of STT, translation, and diarization for the resolved assets.

## Environment notes (WSL2, not app blockers)
- **No Java Sound input/loopback devices:** `--list-audio-devices` may return 0 here, so real microphone and real system-loopback capture cannot be exercised in this WSL2 session. This is an environment limitation, not a missing CLI feature.
- **Headless JVM:** Swing UI cannot be visually validated without a non-headless JDK/JRE and a display (WSLg/X11/Wayland). The jar handles headless launch gracefully.
- **Linux-side helpers (optional):** PipeWire/Pulse/ffmpeg capture tools and local TTS/clipboard/inject tools are not required for the verified headless/inference flows above.

## Windows-only (P4, deferred — requires a Windows session)
Not achievable/validatable as full parity in WSL2; implement and validate on Windows (Java-first via JNA/JNI where appropriate):
- Global hotkeys into native Windows apps
- Caret/text injection into focused Windows text fields
- WASAPI/system loopback of Windows-host audio endpoints
- Core Audio per-app ducking
- Windows-native TTS and Windows UI/app/process integration
- Full end-to-end dictation/live-translate parity on the Windows host

## Assets & resolution (current baseline)
- **STT:** in-process JNA shim `gek-java/native/libgek_whisper_shim.so` over GGML models in `resources/models/whisper-ggml/` (e.g. `ggml-medium.bin`, plus base/small/tiny as available). Falls back to an available/valid GGML when the requested one is missing/corrupt. `whisper.cpp` CLI subprocess is fallback only if shim is unavailable.
- **Translation:** in-process JNA shim `gek-java/native/libgek_translate_shim.so` (embedded Python/CT2 runtime) using `gek-java/native/.venv` (`ctranslate2`, `sentencepiece`) over Argos packages in `resources/models/argos/packages/`. Requires an explicit `--source-lang/--target-lang` pair with a **SentencePiece** tokenizer. Helper subprocess fallback only.
  - *Known asset limitation:* BPE-only packages are unusable in this runtime (currently `en->pl`, `es->en`); other present SentencePiece pairs are usable.
- **Diarization:** in-JVM ONNX Runtime pipeline using `resources/models/pyannote/speaker-diarization-community-1/` (`config.yaml`, `plda/*.npz`, and `onnx/` artifacts: `segmentation.onnx`, `embedding_encoder.onnx`, `resnet_seg_1_weight.npy`, `resnet_seg_1_bias.npy`). Functional baseline is ready; artifact/export parity caveats are tracked in `WSL2_HANDOFF.md`.
- **Config/data:** `resources/data/` (`settings.json`, `history.db`, `synonyms/`); inference mode defaults to `native`.
- **Notes:** no local GGML `large-v3` asset in this environment.

## Temporary sidecar fallback (explicit, optional)
Only when a native/runtime path is unavailable and Python ML deps are installed:
```bash
java -jar target/gek-java-0.1.0-SNAPSHOT.jar --use-sidecar --self-test --warmup
# CPU example: GEK_WHISPER_DEVICE=cpu GEK_WHISPER_COMPUTE_TYPE=int8
```
Dependencies vary by engine (e.g. `faster-whisper`, `argostranslate`, `torch`/`pyannote.audio`). Not required for the verified native baseline above.

## Layout (high level)
- `src/main/java/com/gek/app` — bootstrap/CLI/controllers (`Main`, `CliRunner`)
- `core` — config/settings/paths/platform/model catalog
- `audio` — capture/VAD/WAV/segmentation
- `inference` — engine contracts, native STT/translation/diarization, optional sidecar client
- `db` — SQLite history
- `ui` — Swing
- `inject`, `text` — injection and text/post-processing helpers
- `native/` — local shims/runtime helpers and dev artifacts
- `bridge/` — optional Python sidecar (fallback)
