# GEK Architecture

## Overview
GEK is a Windows desktop voice-translation app. It captures system audio and microphone input, transcribes speech with faster-whisper, and displays results in a CustomTkinter HUD. Translation, database persistence, and TTS playback are wired into the live transcription callback. Completed work includes resizable HUD, scrollable language selection, reason-tracked STT pause/resume, explicit language enforcement, Stop Listening guards, TTS speaking-state callbacks with auto-mute integration, path resolution fixes, dead-code removal, queue-timeout reductions, TTS lifecycle hardening, diarizer empty-segment guards, PID-based audio session ducking, TTS thread COM initialization, and a true Pause/Resume capture toggle. Remaining work focuses on replacing PowerShell playback with a direct Windows audio API or embedded player, and expanding language/voice support. The capture control flow now distinguishes Pause/Resume from full Quit.

## Directory layout
```
D:\gek
├── src/
│   ├── main.py                 # LiveApp entry point, lifecycle, settings apply/reload
│   ├── config.py               # Constants, presets, path setup, settings load/save
│   ├── db.py                   # SQLite translation history + FTS5
│   ├── core/
│   │   ├── stt_pipeline.py     # Audio capture, buffering, Whisper transcription, hallucination filter
│   │   ├── translator.py       # Argos Translate wrapper, synonyms, repetition guards
│   │   ├── tts_engine.py       # pyttsx3 + PowerShell playback, async worker with ducking
│   │   ├── diarizer.py         # pyannote speaker diarization
│   │   ├── audio_detector.py   # Active audio process detection via pycaw / poll fallback
│   │   └── app_ducker.py       # Per-app volume duck/restore via pycaw
│   ├── ui/
│   │   ├── hud.py              # Overlay HUD: drag, resize, color filter, language selection
│   │   ├── settings_window.py  # Preset, model, device, audio, TTS speed, HUD opacity
│   │   ├── history_window.py   # Searchable history from SQLite FTS
│   │   └── note_window.py      # Simple notes with temp-file write guard
│   └── tests/
│       ├── conftest.py
│       ├── test_regression.py
│       ├── test_unit_*.py
│       ├── test_bug_auto.py
│       └── test_performance.py
└── resources/
    ├── models/
    │   ├── whisper/             # faster-whisper model cache
    │   ├── argos/               # Argos language-pair packages
    │   └── pyannote/            # speaker-diarization model
    ├── data/
    │   ├── settings.json
    │   ├── history.db
    │   ├── synonyms/
    │   └── logs/
    ├── notes.txt
    ├── console.txt
    ├── kilo.txt
    ├── wu_diagnostic.txt
    ├── voice_install_log.txt
    ├── install_voices.ps1
    └── fix_tts_voices.ps1
```

## Core data flow
1. `LiveApp` creates `TTSEngine`, `Translator`, `AppDucker`, `Diarizer`, `AudioProcessDetector`, and `Database`.
2. `STTPipeline.start()` opens soundcard recorders for loopback and microphone, feeding labeled audio chunks into a queue.
3. The STT loop buffers chunks until silence or max duration, then dispatches to a transcribe worker.
4. Transcription produces text, language, and confidence; optional diarization picks a dominant speaker.
5. `LiveApp._on_transcription` schedules `_handle_transcription` on the Tkinter main loop.
6. `_handle_transcription` updates the HUD and routes text through `Translator`, `Database.save(...)`, and `TTSEngine` playback with optional `AppDucker`.

## Key modules
- `config.py`: `ROOT_DIR` resolves to the project root. `MODELS_DIR`, `DATA_DIR`, and related paths point under `resources/`. Settings are persisted to `resources/data/settings.json`. Defines `TRANSLATION_ENABLED`, `TTS_ENABLED`, `DUCKING_ENABLED`, `SOURCE_LANG`, and `TARGET_LANG`.
- `main.py`: Builds the root window, wires settings/history/notes/HUD callbacks, and handles model reload on setting changes. Creates `STTPipeline` with `config.WHISPER_MODELS_DIR`.
- `db.py`: SQLite with `translations` table plus `translations_fts` and triggers for FTS5.
- `stt_pipeline.py`: Main capture+transcription engine. `models_dir` controls local/cache Whisper model lookup.
- `tts_engine.py`: Provides `speak`, `speak_async`. Uses pyttsx3 for synthesis and PowerShell `Media.SoundPlayer` for playback. Worker thread initializes COM explicitly and supports PID-based app ducking.
- `translator.py`: Wraps Argos Translate, loads synonym JSON files, and filters repetitive/unlikely translations.
- `diarizer.py`: Loads a local pyannote pipeline from `config.MODELS_DIR / "pyannote" / "speaker-diarization-community-1"` and returns merged speaker segments. Guards against empty or all-NaN audio segments.
- `audio_detector.py`: Prefers WASAPI session enumeration via pycaw; falls back to process-name polling. Tracks PIDs alongside process names.
- `app_ducker.py`: Ducks and restores per-app audio sessions for TTS playback using PID-based session resolution when available.
- `ui/hud.py`: Top-level overlay with drag/resize, color filtering, RTL wrapping, and language selection.
- `ui/settings_window.py`: Reads/writes settings and applies preset/model/device changes at runtime.
- `ui/history_window.py`: Loads recent and FTS-searched translations with playback.
- `ui/note_window.py`: Simple notes stored under `resources/notes.txt` with atomic save.

## Current known issues
- Replace PowerShell playback with a direct Windows audio API or embedded player.
- Expand `SUPPORTED_LANGUAGES` and `SAPI5_VOICE_MAP` as new voices/packages are installed.
- `AudioProcessDetector.get_pid_for_source()` returns only the first active PID; multi-app TTS ducking may need per-app PID resolution.
- Pause/Resume does not restart audio device recorders; capture threads sleep while paused.

## Pipeline integration status
- `LiveApp._handle_transcription` updates the HUD and performs translation, DB persistence, and TTS playback. Auto-mute and ducking lifecycle are implemented; echo prevention remains pending.
- Wiring translation, DB persistence, and TTS into the live callback is complete.

## Remaining work
- Replace PowerShell playback with a direct Windows audio API or embedded player.
- Expand `SUPPORTED_LANGUAGES` and `SAPI5_VOICE_MAP` as new voices/packages are installed.
- Remove PowerShell playback dependency entirely.
