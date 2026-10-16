# Directory Split Plan

## Goal
Separate the project into two clear parts:
1. **Code** — the application source
2. **External resources** — models, runtime data, logs, notes, and auxiliary files

---

## Current State

The project currently mixes code and resources at the root:

- **Code:** `main.py`, `config.py`, `db.py`, `core/`, `ui/`, `tests/`
- **Resources:** `models/`, `data/`, `notes.txt`, `console.txt`, `kilo.txt`, `wu_diagnostic.txt`, `voice_install_log.txt`, `install_voices.ps1`, `fix_tts_voices.ps1`, `repomix-output.xml`

---

## Proposed Directory Split

```
gek/
├── src/                          # CODE (project)
│   ├── main.py
│   ├── config.py
│   ├── db.py
│   ├── core/
│   │   ├── stt_pipeline.py
│   │   ├── audio_capture.py
│   │   ├── audio_detector.py
│   │   ├── app_ducker.py
│   │   ├── stt_engine.py
│   │   ├── translator.py
│   │   ├── tts_engine.py
│   │   └── diarizer.py
│   ├── ui/
│   │   ├── hud.py
│   │   ├── settings_window.py
│   │   ├── history_window.py
│   │   └── note_window.py
│   └── tests/
│       └── ...
│
├── resources/                    # EXTERNAL RESOURCES
│   ├── models/
│   │   ├── whisper/
│   │   ├── argos/
│   │   └── pyannote/
│   ├── data/
│   │   ├── synonyms/
│   │   ├── settings.json
│   │   ├── logs/
│   │   └── history.db
│   ├── notes.txt
│   ├── console.txt
│   ├── kilo.txt
│   ├── wu_diagnostic.txt
│   ├── voice_install_log.txt
│   ├── install_voices.ps1
│   ├── fix_tts_voices.ps1
│   └── repomix-output.xml
│
└── .kilo/                        # Tooling config (leave as-is)
    ├── package.json
    ├── package-lock.json
    ├── .gitignore
    └── kilo.jsonc
```

---

## Files That Need Path Updates

| File | Current Path Reference | New Path |
|---|---|---|
| `config.py` | `MODELS_DIR = BASE_DIR / "models"` | `BASE_DIR / "resources" / "models"` |
| `config.py` | `DATA_DIR = BASE_DIR / "data"` | `BASE_DIR / "resources" / "data"` |
| `config.py` | `DB_PATH = DATA_DIR / "history.db"` | updated automatically |
| `core/translator.py` | `models/argos/packages` | `resources/models/argos/packages` |
| `core/translator.py` | `data/synonyms` | `resources/data/synonyms` |
| `core/diarizer.py` | `models/pyannote/...` | `resources/models/pyannote/...` |
| `core/stt_pipeline.py` | `models_dir` parameter | receives updated path from config |
| `ui/hud.py` | `notes.txt` path | `resources/notes.txt` |
| `ui/note_window.py` | `NOTES_FILE` path | `resources/notes.txt` |

---

## Rationale

1. **`src/`** — All application code, including tests. This is the "project" part. Easy to version-control, review, and deploy.
2. **`resources/`** — Everything that's generated, downloaded, or user-specific:
   - ML models (Whisper, Argos, pyannote) — large binary files, not source code
   - Runtime data (settings, logs, history DB) — generated at runtime
   - User notes, diagnostics, install scripts — auxiliary files
3. **`.kilo/`** — Left at root because it's tooling configuration, not project code or resources.

---

## Benefits

- **Clean separation**: Code and resources are in distinct, easy-to-find locations
- **Easier gitignore**: Add `resources/` to `.gitignore` to exclude large models and runtime data
- **Easier backup**: Only `src/` needs to be committed; `resources/` can be backed up separately
- **Easier deployment**: Ship `src/` as a package, mount `resources/` as a data volume
