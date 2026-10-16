# GEK Voice Translation

Windows desktop app that translates spoken audio in near real time.

## What it does
- Captures system audio and microphone input.
- Transcribes speech with faster-whisper.
- Translates text with Argos Translate and synonym maps.
- Speaks results through installed SAPI5 voices.
- Shows live text in a draggable HUD overlay.

## Current state
- Core modules are implemented under `src/core` and `src/ui`.
- Tests are under `src/tests`.
- Runtime models and data live under `resources/`.
- The codebase is at a production-ready baseline: `302` non-performance tests pass, dead code/imports are removed, and settings/toggle persistence is fully wired.

## Completed wiring
- `src/config.py` defines `TRANSLATION_ENABLED`, `TTS_ENABLED`, `DUCKING_ENABLED`, `SOURCE_LANG`, and `TARGET_LANG`.
- `src/main.py` passes `config.WHISPER_MODELS_DIR` to `STTPipeline` instead of a hard-coded path.
- `src/core/diarizer.py` resolves the pyannote model from `config.MODELS_DIR / "pyannote" / "speaker-diarization-community-1"`.
- `LiveApp._handle_transcription` routes recognized text through translation, `Database.save(...)`, and `TTSEngine` playback with optional per-app ducking.

## Completed
- STT pause/resume with reason tracking and queue/buffer reset.
- TTS speaking-state callback and auto-mute integration.
- Idempotent stop-listening flow with race guards.
- Resizable HUD/main window with `minsize`.
- Scrollable language selection via `CTkScrollableFrame` and explicit STT language selector.
- Explicit `language=self.language` propagation in `STTPipeline._do_transcribe()`.
- LSP/type-hint cleanup in `src/ui/hud.py`.
- Persisted toggle keys in `resources/data/settings.json`.
- Auto-mute setting reconciliation in `LiveApp._apply_settings()`.
- Path resolution fix: `config.SETTINGS_PATH` and resource roots now resolve to the project-level `resources/` tree.
- Removed dead alias modules `core/stt_engine.py` and `core/audio_capture.py`, and generated artifact `repomix-output.xml`.
- Reduced STT/transcribe queue timeouts to `0.1` so pause/resume takes effect promptly.
- Hardened TTS lifecycle: initialized `_cancelling`, removed dead queue infrastructure, prevented overlapping `speak_async` workers, and made duck restoration reliable across stop/cancel interruptions.
- Cleaned up dead imports and duplicate logging in `src/main.py`, and removed unused HUD helpers.
- Fixed `loop_iteration` NameError in `src/core/stt_pipeline.py`.
- Main-window STT/TTS toggles now persist to `config` and `settings.json`.
- Settings load/save roundtrip expanded to include Whisper language, diarization, audio, TTS, and timeout settings.
- Added regression tests for TTS lifecycle, HUD language resolution, and config persistence; `309` non-performance tests pass.
- Fixed NumPy empty-slice warnings in `src/core/diarizer.py` by guarding `np.mean`/empty-array paths.
- Transitioned `AppDucker` to PID-based Windows Core Audio session resolution via `AudioProcessDetector`.
- Resolved TTS thread COM initialization conflict by calling `CoInitializeEx` at the entry of `_speak_worker`.
- Refactored "Stop Capture" into a true Pause/Resume toggle: capture and processing suspend without unloading the Whisper model or killing worker threads. `Quit` remains the full shutdown path.

## Remaining
- Replace PowerShell playback with a direct Windows audio API or embedded player.
- Expand `SUPPORTED_LANGUAGES` and `SAPI5_VOICE_MAP` as new voices/packages are installed.
- Pause/Resume does not restart audio device recorders; capture threads sleep while paused.

## Running
1. Install Python dependencies on Windows.
2. Run `src/main.py`.
3. Use the root window to start capture, open settings, history, notes, or toggle the HUD.

## Documentation
- `architecture.md` describes the module layout, data flow, and known issues.
