# GEK Voice Translation — Portfolio Profile

## Project Overview

**GEK Voice Translation** is a production-ready Windows desktop application that performs near real-time speech translation. It captures system audio (loopback) and microphone input, transcribes speech using **faster-whisper** (CUDA-accelerated), translates text via **Argos Translate** (offline, no API keys), and speaks results through installed **SAPI5** voices. A draggable, resizable HUD overlay displays live transcriptions with speaker diarization, confidence scores, and language detection.

**Tech Stack:** Python 3.11+, CustomTkinter (UI), faster-whisper (STT), Argos Translate (MT), pyttsx3 + Windows Media.SoundPlayer (TTS), pyannote.audio (speaker diarization), pycaw (Core Audio session control), SQLite + FTS5 (history), soundcard (audio capture), torch/CUDA (GPU inference).

---

## Key Features

| Feature | Implementation |
|---------|---------------|
| **Dual Audio Capture** | Simultaneous loopback (system audio) + microphone via `soundcard`; device selection in settings |
| **Streaming STT** | faster-whisper with configurable chunking (0.15–4s), silence detection, carryover, beam-size presets (Performance/Balanced/Quality) |
| **Speaker Diarization** | pyannote `speaker-diarization-community-1` model; merges segments, picks dominant speaker; guards against empty/NaN audio |
| **Offline Translation** | Argos Translate with synonym maps (JSON), English-pivot fallback, hallucination/repetition filters (7 heuristic checks) |
| **TTS Playback** | pyttsx3 synthesis → WAV → PowerShell `Media.SoundPlayer`; per-language SAPI5 voice map; async worker with COM init |
| **Per-App Audio Ducking** | PID-based Windows Core Audio session resolution via `pycaw`; ducks active app during TTS, restores reliably |
| **Auto-Mute STT During TTS** | Callback-driven pause/resume with reason tracking (`tts`, `manual`); prevents feedback loops |
| **Pause/Resume (Not Stop)** | Capture threads sleep; Whisper model stays loaded; instant resume; `Quit` = full shutdown |
| **Resizable HUD Overlay** | CustomTkinter borderless topmost window; drag/resize; color-coded source bars; RTL wrapping; language filter; opacity/position persistence |
| **Settings Persistence** | JSON (`resources/data/settings.json`); 30+ keys including model, device, audio, TTS, diarization, timeouts, toggles |
| **History & Search** | SQLite + FTS5 virtual table; full-text search; playback from history |
| **Notes & Diagnostics** | Atomic note saving; structured logging (rotating file + console); CUDA VRAM reporting |

---

## Architecture Highlights

```
LiveApp (main.py)
├── STTPipeline (core/stt_pipeline.py)          # Audio capture → buffering → Whisper transcription
├── Translator (core/translator.py)              # Argos wrapper + synonyms + hallucination guard
├── TTSEngine (core/tts_engine.py)               # pyttsx3 + PowerShell playback + async worker + ducking
├── Diarizer (core/diarizer.py)                  # pyannote pipeline loading + segment merging
├── AppDucker (core/app_ducker.py)               # PID-based per-app volume duck/restore
├── AudioProcessDetector (core/audio_detector.py)# WASAPI session enum → PID mapping for ducking
├── Database (db.py)                             # SQLite + FTS5 history
└── UI (ui/*.py)                                 # HUD, Settings, History, Notes windows
```

**Data Flow:**
1. `STTPipeline.start()` opens loopback + mic recorders → labeled audio chunks → queue
2. STT loop buffers until silence/max-duration → dispatches to transcribe worker(s)
3. Transcription yields `(text, lang, confidence)` → optional diarization → `LiveApp._on_transcription`
4. `_handle_transcription` (on Tk main loop): HUD update → translate → DB save → TTS speak_async (with ducking)

---

## Engineering Highlights

- **Thread Safety:** `RLock`-protected pause/resume with reason-set; idempotent stop with race guards; queue/buffer reset on pause
- **Resource Management:** Explicit model unload/reload on setting changes; CUDA cache clear on shutdown; `gc.collect()`
- **Type Safety & Linting:** Full type hints; LSP-clean; dead code/imports removed (`stt_engine.py`, `audio_capture.py` aliases deleted)
- **Test Coverage:** 300+ non-performance unit/regression tests (STT pipeline, TTS lifecycle, HUD language resolution, config persistence, translator hallucination filters, DB FTS)
- **Windows-Specific Integration:** COM initialization in TTS worker thread; `ctypes` monitor enumeration for HUD positioning; `CREATE_NO_WINDOW` for silent PowerShell playback
- **Config-Driven Presets:** Three tiers (Performance/Balanced/Quality) auto-tune Whisper model, beam size, compute type, chunking
- **Path Resolution Fix:** `config.ROOT_DIR` handles both source and frozen (PyInstaller) layouts; all resources under `resources/`

---

## Known Limitations (Honest Assessment)

| Area | Status |
|------|--------|
| **PowerShell Playback** | Works but spawns process per utterance; planned: direct WASAPI/IAudioClient or embedded player (e.g., `pyaudio`/`sounddevice`) |
| **Language/Voice Coverage** | 11 languages mapped; requires manual SAPI5 voice install per language; Argos packages must be pre-downloaded |
| **Multi-App Ducking** | `AudioProcessDetector.get_pid_for_source()` returns first active PID only; simultaneous multi-app ducking not yet supported |
| **Pause/Resume Capture** | Threads sleep; audio devices not reopened (by design for speed); may accumulate buffer drift over very long pauses |

---

## Metrics & Scale

- **Lines of Code:** ~4,500 (src/ only, excluding tests)
- **Test Count:** 300+ non-performance tests passing
- **Models Supported:** Whisper tiny/base/small/medium/large-v3; Argos 11 language pairs; pyannote diarization
- **Latency Target:** <1.5s end-to-end (audio → HUD text) on CUDA with tiny/base models
- **Platform:** Windows 10/11 (x64); Python 3.11+; CUDA 11.8+ for GPU acceleration

---

## Suggested CV Bullet Points

> **GEK Voice Translation** — *Windows Desktop App, Python/CUDA*  
> - Built a real-time speech translation pipeline: system/mic audio capture → faster-whisper (CUDA) → Argos Translate (offline) → SAPI5 TTS with per-app audio ducking  
> - Architected modular core (STT, MT, TTS, diarization, audio session control) with clean callback-based wiring; 300+ unit/regression tests  
> - Implemented pause/resume with reason tracking, model hot-reload, settings persistence (30+ keys), SQLite+FTS5 history, and a draggable/resizable CustomTkinter HUD overlay  
> - Hardened TTS lifecycle: COM init per worker, async pending-text pattern, reliable duck/restore via PID-based Core Audio session resolution  
> - Optimized for latency: configurable chunking (0.15–4s), beam-size presets, queue timeouts (0.1s), CUDA float16/int8 compute

---

## Repository Structure (for reference)

```
gek/
├── src/
│   ├── main.py                 # LiveApp entry point, lifecycle, settings apply/reload
│   ├── config.py               # Constants, presets, path setup, settings load/save
│   ├── db.py                   # SQLite translation history + FTS5
│   ├── core/
│   │   ├── stt_pipeline.py     # Audio capture, buffering, Whisper transcription
│   │   ├── translator.py       # Argos wrapper, synonyms, repetition guards
│   │   ├── tts_engine.py       # pyttsx3 + PowerShell, async worker, ducking
│   │   ├── diarizer.py         # pyannote speaker diarization
│   │   ├── audio_detector.py   # Active audio process detection (pycaw)
│   │   └── app_ducker.py       # Per-app volume duck/restore
│   ├── ui/
│   │   ├── hud.py              # Overlay HUD: drag, resize, color filter, lang selection
│   │   ├── settings_window.py  # Preset, model, device, audio, TTS, HUD
│   │   ├── history_window.py   # Searchable history from SQLite FTS
│   │   └── note_window.py      # Simple notes with atomic save
│   └── tests/                  # 300+ unit/regression/performance tests
└── resources/
    ├── models/                 # Whisper, Argos, pyannote model caches
    └── data/                   # settings.json, history.db, synonyms/, logs/
```

---

## Links (if applicable)

- **Repository:** [Add your GitHub/GitLab URL here]
- **Demo Video:** [Add link if you have one]
- **Built With:** Python, CustomTkinter, faster-whisper, Argos Translate, pyannote.audio, pycaw, SQLite