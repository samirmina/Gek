import sys
import platform
import json
from pathlib import Path

PLATFORM = platform.system().lower()

if getattr(sys, "frozen", False):
    ROOT_DIR = Path(sys.executable).parent
else:
    ROOT_DIR = Path(__file__).resolve().parent.parent

MODELS_DIR = ROOT_DIR / "resources" / "models"
WHISPER_MODELS_DIR = MODELS_DIR / "whisper"
ARGOS_MODELS_DIR = MODELS_DIR / "argos"
DATA_DIR = ROOT_DIR / "resources" / "data"
DB_PATH = DATA_DIR / "history.db"
SYNONYMS_DIR = DATA_DIR / "synonyms"
SETTINGS_PATH = DATA_DIR / "settings.json"

WHISPER_MODEL_SIZE = "tiny"

try:
    import torch
    WHISPER_DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
except Exception:
    WHISPER_DEVICE = "cpu"

WHISPER_COMPUTE_TYPE = "float16" if WHISPER_DEVICE == "cuda" else "int8"
WHISPER_LANGUAGE = None

SAMPLE_RATE = 16000
CHUNK_DURATION = 0.25
SILENCE_THRESHOLD = 0.01
SILENCE_DURATION_MS = 300
MIN_CHUNK_DURATION = 0.15
MAX_CHUNK_DURATION = 4.0
CARRYOVER_DURATION = 0.2

DIARIZATION_ENABLED = True
DIARIZATION_MAX_SPEAKERS = 4
PROCESS_POLL_INTERVAL = 2.0

WHISPER_IDLE_TIMEOUT = 0
WHISPER_TRANSCRIBE_TIMEOUT_FACTOR = 0.0
WHISPER_BEAM_SIZE = 1
WHISPER_NO_SPEECH_THRESHOLD = 0.6
WHISPER_LOG_PROB_THRESHOLD = -1.0
WHISPER_COMPRESSION_RATIO_THRESHOLD = 2.4
WHISPER_CONDITION_ON_PREVIOUS_TEXT = False

WHISPER_BEAM_SIZE_MAP = {
    "tiny": 1,
    "base": 3,
    "small": 5,
    "medium": 5,
    "large-v3": 5,
}

PRESETS = {
    "Performance": {
        "whisper_model": "tiny",
        "whisper_device": "cuda",
        "whisper_compute_type": "float16",
        "whisper_beam_size": 1,
        "chunk_duration": 0.2,
        "silence_duration_ms": 300,
        "min_chunk_duration": 0.15,
        "max_chunk_duration": 4.0,
    },
    "Balanced": {
        "whisper_model": "small",
        "whisper_device": "cuda",
        "whisper_compute_type": "float16",
        "whisper_beam_size": 5,
        "chunk_duration": 0.25,
        "silence_duration_ms": 300,
        "min_chunk_duration": 0.15,
        "max_chunk_duration": 4.0,
    },
    "Quality": {
        "whisper_model": "medium",
        "whisper_device": "cuda",
        "whisper_compute_type": "int8",
        "whisper_beam_size": 5,
        "chunk_duration": 0.5,
        "silence_duration_ms": 300,
        "min_chunk_duration": 0.15,
        "max_chunk_duration": 4.0,
    },
}

TTS_RATE = 150
TTS_VOLUME = 1.0
TTS_DUCK_LEVEL = 0.2

SUPPORTED_LANGUAGES = {
    "en": "English",
    "ar": "Arabic",
    "de": "German",
    "nl": "Dutch",
    "fr": "French",
    "it": "Italian",
    "ja": "Japanese",
    "ko": "Korean",
    "zh": "Chinese",
    "es": "Spanish",
    "pl": "Polish",
    "sv": "Swedish",
}

TRANSLATION_ENABLED = True
TTS_ENABLED = True
STT_ENABLED = True
DUCKING_ENABLED = True
AUTO_MUTE_DURING_TTS = True
SOURCE_LANG = "en"
TARGET_LANG = "en"

HUD_OPACITY = 0.95
HUD_WIDTH = 850

AUDIO_LOOPBACK_DEVICE = None
AUDIO_MIC_DEVICE = None
AUDIO_SAMPLE_RATE = 16000
AUDIO_CHUNK_DURATION = 0.25

for d in [MODELS_DIR, WHISPER_MODELS_DIR, ARGOS_MODELS_DIR, DATA_DIR, SYNONYMS_DIR]:
    d.mkdir(parents=True, exist_ok=True)


def load_settings():
    if SETTINGS_PATH.exists():
        try:
            with open(SETTINGS_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data
        except Exception:
            pass
    return {}


def save_settings(data: dict):
    try:
        SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(SETTINGS_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        print(f"Failed to save settings: {e}")
