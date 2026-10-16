import os
os.environ["CT2_CUDA_ALLOCATOR"] = "cub_caching"

import warnings
import soundcard.mediafoundation as _soundcard_mf
warnings.simplefilter("ignore", _soundcard_mf.SoundcardRuntimeWarning)
warnings.filterwarnings("ignore", message=".*torchcodec.*")
warnings.filterwarnings("ignore", message=".*triton.*")
warnings.filterwarnings("ignore", category=UserWarning, module="pyannote")

import sys
import threading
import logging
from logging.handlers import RotatingFileHandler

import customtkinter as ctk
import tkinter as tk
from tkinter import messagebox

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

logging.getLogger("comtypes").setLevel(logging.WARNING)
logging.getLogger("comtypes._post_coinit").setLevel(logging.WARNING)
logging.getLogger("matplotlib").setLevel(logging.WARNING)
logging.getLogger("matplotlib.font_manager").setLevel(logging.WARNING)
logging.getLogger("fsspec.local").setLevel(logging.WARNING)
logging.getLogger("urllib3.connectionpool").setLevel(logging.WARNING)

logs_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "resources", "data", "logs")
os.makedirs(logs_dir, exist_ok=True)
log_path = os.path.join(logs_dir, "app.log")

logger = logging.getLogger("GEK")
logger.setLevel(logging.DEBUG)

fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")

console = logging.StreamHandler(sys.stdout)
console.setLevel(logging.DEBUG)
console.setFormatter(fmt)
logger.addHandler(console)

file_handler = RotatingFileHandler(log_path, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
file_handler.setLevel(logging.DEBUG)
file_handler.setFormatter(fmt)
logger.addHandler(file_handler)

logging.basicConfig(handlers=[console, file_handler], level=logging.DEBUG)
logging.captureWarnings(True)
warnings_logger = logging.getLogger("warnings")
warnings_logger.setLevel(logging.DEBUG)

from config import (
    SAMPLE_RATE, CHUNK_DURATION, SUPPORTED_LANGUAGES,
    SILENCE_THRESHOLD, SILENCE_DURATION_MS, MIN_CHUNK_DURATION, MAX_CHUNK_DURATION,
    CARRYOVER_DURATION,
    DIARIZATION_ENABLED, DIARIZATION_MAX_SPEAKERS,
    PROCESS_POLL_INTERVAL, TTS_DUCK_LEVEL, TTS_RATE, TTS_VOLUME,
    STT_ENABLED, TTS_ENABLED, AUTO_MUTE_DURING_TTS,
)
from db import Database
from core.tts_engine import TTSEngine
from core.stt_pipeline import STTPipeline
from core.translator import Translator
from core.diarizer import Diarizer
from core.audio_detector import AudioProcessDetector
from core.app_ducker import AppDucker
from ui.hud import Hud

logger = logging.getLogger(__name__)


class LiveApp:
    def _on_tts_speaking_changed(self, is_speaking):
        if not self.auto_mute_during_tts:
            return
        pipeline = getattr(self, "_pipeline", None)
        if pipeline is None:
            return
        try:
            if is_speaking:
                pipeline.pause_input("tts")
            else:
                pipeline.resume_input("tts")
        except Exception as e:
            logger.debug(f"Auto-mute STT error: {e}")

    def __init__(self):
        ctk.set_appearance_mode("dark")
        self.root = ctk.CTk()
        self.root.title("GEK - Voice Translation")
        self.root.geometry("420x360")
        self.root.resizable(True, True)
        self.root.minsize(420, 360)
        self.root.configure(fg_color="#1a1a2e")
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        self._load_settings()

        self.database = Database()
        self.tts = TTSEngine(rate=TTS_RATE, volume=TTS_VOLUME, on_speaking_state_changed=self._on_tts_speaking_changed)
        self.translator = Translator()
        self.app_ducker = AppDucker(duck_level=TTS_DUCK_LEVEL)
        self.diarizer = Diarizer(
            sample_rate=SAMPLE_RATE,
            max_speakers=DIARIZATION_MAX_SPEAKERS,
        )
        self.process_detector = AudioProcessDetector(
            poll_interval=PROCESS_POLL_INTERVAL,
        )

        self._pipeline = None
        self.hud = None
        self._history_win = None

        self._capturing = False
        self._paused = False
        self._shutting_down = False
        self._stopping_capture = False
        self.stt_enabled = bool(STT_ENABLED)
        self.tts_enabled = bool(TTS_ENABLED)
        self.auto_mute_during_tts = bool(AUTO_MUTE_DURING_TTS)

        self._build_root()

        if DIARIZATION_ENABLED:
            threading.Thread(
                target=self.diarizer.load_model,
                daemon=True,
                name="diarizer-load",
            ).start()

    def _load_settings(self):
        logger.info("LiveApp init: loading settings")
        try:
            import config
            data = config.load_settings()
            if not data:
                logger.info("LiveApp init: no saved settings found")
                return
            config.WHISPER_MODEL_SIZE = data.get("whisper_model", config.WHISPER_MODEL_SIZE)
            config.WHISPER_DEVICE = data.get("whisper_device", config.WHISPER_DEVICE)
            config.WHISPER_COMPUTE_TYPE = data.get("whisper_compute_type", config.WHISPER_COMPUTE_TYPE)
            config.WHISPER_BEAM_SIZE = data.get("whisper_beam_size", config.WHISPER_BEAM_SIZE)
            config.TTS_RATE = int(data.get("tts_speed", config.TTS_RATE / 150) * 150)
            config.HUD_OPACITY = data.get("hud_opacity", config.HUD_OPACITY)
            config.AUDIO_LOOPBACK_DEVICE = data.get("audio_loopback") or None
            config.AUDIO_MIC_DEVICE = data.get("audio_mic") or None
            config.CHUNK_DURATION = data.get("chunk_duration", config.CHUNK_DURATION)
            config.SILENCE_DURATION_MS = data.get("silence_duration_ms", config.SILENCE_DURATION_MS)
            config.MIN_CHUNK_DURATION = data.get("min_chunk_duration", config.MIN_CHUNK_DURATION)
            config.MAX_CHUNK_DURATION = data.get("max_chunk_duration", config.MAX_CHUNK_DURATION)
            config.WHISPER_LANGUAGE = data.get("whisper_language", config.WHISPER_LANGUAGE)
            config.DIARIZATION_ENABLED = bool(data.get("diarization_enabled", config.DIARIZATION_ENABLED))
            config.DIARIZATION_MAX_SPEAKERS = int(data.get("diarization_max_speakers", config.DIARIZATION_MAX_SPEAKERS))
            config.PROCESS_POLL_INTERVAL = float(data.get("process_poll_interval", config.PROCESS_POLL_INTERVAL))
            config.TTS_DUCK_LEVEL = float(data.get("tts_duck_level", config.TTS_DUCK_LEVEL))
            config.TTS_VOLUME = float(data.get("tts_volume", config.TTS_VOLUME))
            config.WHISPER_IDLE_TIMEOUT = float(data.get("whisper_idle_timeout", config.WHISPER_IDLE_TIMEOUT))
            config.WHISPER_TRANSCRIBE_TIMEOUT_FACTOR = float(data.get("whisper_transcribe_timeout_factor", config.WHISPER_TRANSCRIBE_TIMEOUT_FACTOR))
            config.WHISPER_NO_SPEECH_THRESHOLD = float(data.get("whisper_no_speech_threshold", config.WHISPER_NO_SPEECH_THRESHOLD))
            config.WHISPER_LOG_PROB_THRESHOLD = float(data.get("whisper_log_prob_threshold", config.WHISPER_LOG_PROB_THRESHOLD))
            config.WHISPER_COMPRESSION_RATIO_THRESHOLD = float(data.get("whisper_compression_ratio_threshold", config.WHISPER_COMPRESSION_RATIO_THRESHOLD))
            config.WHISPER_CONDITION_ON_PREVIOUS_TEXT = bool(data.get("whisper_condition_on_previous_text", config.WHISPER_CONDITION_ON_PREVIOUS_TEXT))
            config.STT_ENABLED = bool(data.get("stt_enabled", config.STT_ENABLED))
            config.TTS_ENABLED = bool(data.get("tts_enabled", config.TTS_ENABLED))
            config.AUTO_MUTE_DURING_TTS = bool(data.get("auto_mute_during_tts", config.AUTO_MUTE_DURING_TTS))
            self.stt_enabled = bool(config.STT_ENABLED)
            self.tts_enabled = bool(config.TTS_ENABLED)
            self.auto_mute_during_tts = bool(config.AUTO_MUTE_DURING_TTS)
            self._saved_preset = data.get("preset", "Balanced")
            logger.info("Settings loaded from disk")
        except Exception as e:
            logger.error(f"Load settings error: {e}")
            self._saved_preset = "Balanced"

    def _build_root(self):
        main = ctk.CTkFrame(self.root, fg_color="transparent")
        main.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

        ctk.CTkLabel(main, text="GEK Voice Translation",
                     font=("Segoe UI", 18, "bold"),
                     text_color="#eaeaea").pack(pady=(0, 12))

        self.start_btn = ctk.CTkButton(
            main, text="Start Capture", height=36,
            fg_color="#2ecc71", hover_color="#27ae60",
            text_color="#ffffff", font=("Segoe UI", 13, "bold"),
            command=self._do_start,
        )
        self.start_btn.pack(fill=tk.X, pady=3)

        self.stop_btn = ctk.CTkButton(
            main, text="Stop Capture", height=36,
            fg_color="#e74c3c", hover_color="#c0392b",
            text_color="#ffffff", font=("Segoe UI", 13, "bold"),
            command=self._do_stop, state=tk.DISABLED,
        )
        self.stop_btn.pack(fill=tk.X, pady=3)

        row2 = ctk.CTkFrame(main, fg_color="transparent")
        row2.pack(fill=tk.X, pady=6)
        ctk.CTkButton(row2, text="Notes", height=30,
                      fg_color="#0f3460", hover_color="#16213e",
                      text_color="#a0a0b0",
                      command=self._open_notes).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(0, 3))
        ctk.CTkButton(row2, text="History", height=30,
                      fg_color="#0f3460", hover_color="#16213e",
                      text_color="#a0a0b0",
                      command=self._open_history).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(3, 3))
        ctk.CTkButton(row2, text="Settings", height=30,
                      fg_color="#0f3460", hover_color="#16213e",
                      text_color="#a0a0b0",
                      command=self._open_settings).pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(3, 0))

        row3 = ctk.CTkFrame(main, fg_color="transparent")
        row3.pack(fill=tk.X, pady=4)
        self.hud_var = tk.BooleanVar(value=True)
        ctk.CTkSwitch(row3, text="Show HUD", variable=self.hud_var,
                      onvalue=True, offvalue=False,
                      command=self._toggle_hud,
                      text_color="#a0a0b0",
                      progress_color="#e94560",
                      button_color="#eaeaea",
                      button_hover_color="#c93650").pack(side=tk.LEFT)
        self.stt_var = tk.BooleanVar(value=self.stt_enabled)
        ctk.CTkSwitch(row3, text="STT", variable=self.stt_var,
                      command=self._toggle_stt,
                      text_color="#a0a0b0",
                      progress_color="#2ecc71",
                      button_color="#eaeaea",
                      button_hover_color="#27ae60").pack(side=tk.LEFT, padx=(8, 0))
        self.tts_var = tk.BooleanVar(value=self.tts_enabled)
        ctk.CTkSwitch(row3, text="TTS", variable=self.tts_var,
                      command=self._toggle_tts,
                      text_color="#a0a0b0",
                      progress_color="#e94560",
                      button_color="#eaeaea",
                      button_hover_color="#c93650").pack(side=tk.LEFT, padx=(8, 0))
        ctk.CTkButton(row3, text="Quit", width=60, height=28,
                      fg_color="#e74c3c", hover_color="#c0392b",
                      text_color="#ffffff",
                      command=self._on_close).pack(side=tk.RIGHT)

    def _toggle_hud(self):
        if self.hud_var.get():
            self._start_hud()
        else:
            if self.hud:
                try:
                    self.hud.destroy()
                except Exception as e:
                    logger.debug("HUD destroy: %s", e)
                self.hud = None

    def _toggle_stt(self):
        self.stt_enabled = bool(self.stt_var.get())
        try:
            import config
            settings = config.load_settings()
            settings["stt_enabled"] = self.stt_enabled
            config.STT_ENABLED = self.stt_enabled
            config.save_settings(settings)
        except Exception as e:
            logger.debug(f"Toggle STT save error: {e}")
        pipeline = getattr(self, "_pipeline", None)
        if pipeline is None:
            return
        try:
            if self.stt_enabled:
                pipeline.resume_input("manual")
            else:
                pipeline.pause_input("manual")
        except Exception as e:
            logger.debug(f"Toggle STT error: {e}")

    def _toggle_tts(self):
        self.tts_enabled = bool(self.tts_var.get())
        if not self.tts_enabled:
            try:
                self.tts.stop()
            except Exception as e:
                logger.debug(f"Toggle TTS stop error: {e}")
        try:
            import config
            settings = config.load_settings()
            settings["tts_enabled"] = self.tts_enabled
            config.TTS_ENABLED = self.tts_enabled
            config.save_settings(settings)
        except Exception as e:
            logger.debug(f"Toggle TTS save error: {e}")

    def _start_hud(self):
        if self.hud is not None:
            try:
                if self.hud.winfo_exists():
                    return
            except Exception:
                pass

        langs = list(SUPPORTED_LANGUAGES.keys())
        self.hud = Hud(master=self.root, languages=langs,
                       on_close=lambda: self.root.after(0, lambda: self.hud_var.set(False)))
        if self._pipeline:
            self.hud._stt_ref = self._pipeline

    def _do_start(self):
        logger.info("LiveApp._do_start: begin start sequence")
        if self._capturing:
            logger.warning("LiveApp._do_start: already capturing, aborting")
            return

        try:
            import config
            logger.info(f"LiveApp._do_start: creating STTPipeline model={config.WHISPER_MODEL_SIZE} device={config.WHISPER_DEVICE}")
            self._pipeline = STTPipeline(
                model_size=config.WHISPER_MODEL_SIZE,
                device=config.WHISPER_DEVICE,
                compute_type=config.WHISPER_COMPUTE_TYPE,
                language=config.WHISPER_LANGUAGE,
                sample_rate=SAMPLE_RATE,
                chunk_duration=CHUNK_DURATION,
                models_dir=str(config.WHISPER_MODELS_DIR),
                on_transcription=self._on_transcription,
                on_status=self._on_status,
                silence_threshold=SILENCE_THRESHOLD,
                silence_duration_ms=SILENCE_DURATION_MS,
                min_chunk_duration=MIN_CHUNK_DURATION,
                max_chunk_duration=MAX_CHUNK_DURATION,
                carryover_duration=CARRYOVER_DURATION,
                diarizer=self.diarizer if DIARIZATION_ENABLED else None,
                idle_timeout=config.WHISPER_IDLE_TIMEOUT,
                transcribe_timeout_factor=config.WHISPER_TRANSCRIBE_TIMEOUT_FACTOR,
                beam_size=config.WHISPER_BEAM_SIZE,
                no_speech_threshold=config.WHISPER_NO_SPEECH_THRESHOLD,
                log_prob_threshold=config.WHISPER_LOG_PROB_THRESHOLD,
                compression_ratio_threshold=config.WHISPER_COMPRESSION_RATIO_THRESHOLD,
                condition_on_previous_text=config.WHISPER_CONDITION_ON_PREVIOUS_TEXT,
                loopback_device=config.AUDIO_LOOPBACK_DEVICE,
                microphone_device=config.AUDIO_MIC_DEVICE,
            )

            if self.hud:
                whisper_lang = self.hud.get_whisper_language()
                if whisper_lang:
                    self._pipeline.set_language(whisper_lang)
                self.hud._stt_ref = self._pipeline

            if not self.stt_enabled:
                self._pipeline.pause_input("manual")

            logger.info("LiveApp._do_start: calling pipeline.start()")
            self._pipeline.start()
            logger.info("LiveApp._do_start: pipeline.start() returned")

            logger.info("LiveApp._do_start: calling process_detector.start()")
            self.process_detector.start()
            logger.info("LiveApp._do_start: process_detector.start() returned")

            try:
                import torch
                if torch.cuda.is_available():
                    total_mb = torch.cuda.get_device_properties(0).total_memory / 1024**2
                    logger.info(f"Whisper device: CUDA ({torch.cuda.get_device_name(0)}, {total_mb:.0f} MB VRAM) | Diarizer: CUDA")
                else:
                    logger.info("Whisper device: CPU | Diarizer: CPU")
            except Exception:
                logger.info("Whisper device: CPU | Diarizer: CPU")

            self._capturing = True
            self._paused = False
            logger.info("LiveApp._do_start: capturing=True, start button disabled")
            self.start_btn.configure(state=tk.DISABLED)
            self.stop_btn.configure(state=tk.NORMAL, text="Stop Capture")

            if self.hud:
                self.hud.update_status("Listening...", "#2ecc71")
                logger.info("LiveApp._do_start: HUD status set to Listening")

            logger.info("LiveApp._do_start: start sequence complete")

        except Exception as e:
            logger.error(f"Start error: {e}")
            if self._pipeline:
                try:
                    self._pipeline.stop()
                except Exception:
                    pass
                self._pipeline = None
            messagebox.showerror("Start Error", str(e))

    def _do_stop(self):
        logger.info("LiveApp._do_stop: begin stop sequence")
        if self._stopping_capture:
            return
        if not self._capturing or not self._pipeline:
            self._capturing = False
            self._paused = False
            if self.root.winfo_exists():
                self.start_btn.configure(state=tk.NORMAL)
                self.stop_btn.configure(state=tk.DISABLED, text="Stop Capture")
            return

        self._stopping_capture = True
        try:
            if not self._paused:
                self._pipeline.pause_input("manual")
                self._paused = True
                if self.root.winfo_exists():
                    self.stop_btn.configure(text="Resume Capture")
                if self.hud:
                    self.hud.update_status("Paused", "#f39c12")
            else:
                self._pipeline.resume_input("manual")
                self._paused = False
                if self.root.winfo_exists():
                    self.stop_btn.configure(text="Stop Capture")
                if self.hud:
                    self.hud.update_status("Listening...", "#2ecc71")
        except Exception as e:
            logger.error(f"Pause/resume error: {e}")
        finally:
            self._stopping_capture = False

    def _on_transcription(self, source, text, speaker="", detected_lang="", confidence=0.0):
        logger.debug(f"LiveApp._on_transcription: source={source!r} lang={detected_lang!r} conf={confidence:.2f} text={text[:60]!r}")
        if self._shutting_down:
            logger.debug("LiveApp._on_transcription: shutting down, discarding")
            return
        try:
            self.root.after(0, self._handle_transcription, source, text, speaker, detected_lang, confidence)
        except Exception as e:
            logger.debug(f"Transcription dispatch error: {e}")

    def _handle_transcription(self, source, text, speaker="", detected_lang="", confidence=0.0):
        import config
        logger.debug(f"LiveApp._handle_transcription: source={source!r} lang={detected_lang!r} conf={confidence:.2f} text={text[:60]!r}")
        if not text or not text.strip():
            logger.debug("LiveApp._handle_transcription: empty text, returning")
            return
        if self._shutting_down:
            logger.debug("LiveApp._handle_transcription: shutting down, discarding")
            return
        if confidence < 0.45:
            logger.debug(f"LiveApp._handle_transcription: low confidence {confidence:.2f}, discarding")
            return
        if not self.stt_enabled:
            logger.debug("LiveApp._handle_transcription: STT disabled, discarding")
            return

        display_source = source
        if source == "system":
            display_source = self.process_detector.get_label_for_source(source)

        if self.hud:
            phrase_id = self.hud.next_phrase_id()
            self.hud.update_text(text, display_source, speaker, detected_lang or "", phrase_id=phrase_id, confidence=confidence)
            self.hud.update_status("Listening...", "#2ecc71")
            logger.debug(f"LiveApp._handle_transcription: routed to HUD phrase_id={phrase_id}")

        translated_text = text
        if config.TRANSLATION_ENABLED:
            try:
                if self.translator:
                    source_lang = detected_lang or config.SOURCE_LANG
                    target_lang = config.TARGET_LANG
                    translated_text = self.translator.translate(text, source_lang=source_lang, target_lang=target_lang)
            except Exception as e:
                logger.warning(f"Translation failed: {e}")

        try:
            self.database.save(
                original_text=text,
                original_lang=detected_lang or config.SOURCE_LANG,
                translated_text=translated_text,
                translated_lang=config.TARGET_LANG,
                source_name=display_source,
                audio_source=source,
            )
        except Exception as e:
            logger.warning(f"Database save failed: {e}")

        if not self.tts_enabled or not translated_text:
            return
        try:
            target_lang = config.TARGET_LANG
            active_pid = self.process_detector.get_pid_for_source(source)
            if config.DUCKING_ENABLED:
                self.tts.speak_async(translated_text, ducker=self.app_ducker, app_name=display_source, lang_code=target_lang, app_pid=active_pid)
            else:
                self.tts.speak_async(translated_text, lang_code=target_lang, app_pid=active_pid)
        except Exception as e:
            logger.warning(f"TTS playback failed: {e}")
    def _on_status(self, text):
        logger.debug(f"LiveApp._on_status: status={text!r}")
        if self._shutting_down:
            return
        try:
            if self.hud:
                self.hud.update_status(text, "#a0a0b0")
        except Exception as e:
            logger.debug(f"Status dispatch error: {e}")

    def _open_notes(self):
        from ui.note_window import NoteWindow
        try:
            if self.hud:
                self.hud._open_notes()
            else:
                NoteWindow(self.root)
        except Exception as e:
            logger.error(f"Notes error: {e}")

    def _open_history(self):
        from ui.history_window import HistoryWindow
        try:
            if not self._history_win or not self._history_win.window:
                self._history_win = HistoryWindow(self.root, self.database, self.tts)
            self._history_win.show()
        except Exception as e:
            logger.error(f"History error: {e}")

    def _open_settings(self):
        from ui.settings_window import SettingsWindow
        try:
            import config
            settings = {
                "preset": getattr(self, '_saved_preset', 'Balanced'),
                "whisper_model": config.WHISPER_MODEL_SIZE,
                "whisper_device": config.WHISPER_DEVICE,
                "tts_speed": config.TTS_RATE / 150,
                "hud_opacity": config.HUD_OPACITY,
                "audio_loopback": getattr(config, 'AUDIO_LOOPBACK_DEVICE', None) or "",
                "audio_mic": getattr(config, 'AUDIO_MIC_DEVICE', None) or "",
                "stt_enabled": getattr(self, 'stt_enabled', True),
                "tts_enabled": getattr(self, 'tts_enabled', True),
                "auto_mute_during_tts": getattr(self, 'auto_mute_during_tts', True),
            }
            SettingsWindow(self.root, settings, on_apply=self._apply_settings).show()
        except Exception as e:
            logger.error(f"Settings error: {e}")

    def _apply_settings(self, settings):
        logger.info(f"LiveApp._apply_settings: keys={sorted(settings.keys())}")
        try:
            preset = settings.get("preset", "Balanced")
            new_model = settings.get("whisper_model")
            new_device = settings.get("whisper_device", "cpu")
            new_speed = settings.get("tts_speed", 1.0)
            new_opacity = settings.get("hud_opacity", 0.95)
            audio_loopback = settings.get("audio_loopback", "")
            audio_mic = settings.get("audio_mic", "")
            stt_enabled = bool(settings.get("stt_enabled", self.stt_enabled))
            tts_enabled = bool(settings.get("tts_enabled", self.tts_enabled))
            auto_mute_during_tts = bool(settings.get("auto_mute_during_tts", self.auto_mute_during_tts))

            import config
            from config import PRESETS
            preset_config = PRESETS.get(preset, {})
            if preset_config:
                config.WHISPER_MODEL_SIZE = preset_config.get("whisper_model", new_model)
                config.WHISPER_DEVICE = preset_config.get("whisper_device", new_device)
                config.WHISPER_COMPUTE_TYPE = preset_config.get("whisper_compute_type", "float16" if new_device == "cuda" else "int8")
                config.WHISPER_BEAM_SIZE = preset_config.get("whisper_beam_size", 5)
                config.CHUNK_DURATION = preset_config.get("chunk_duration", 0.25)
                config.SILENCE_DURATION_MS = preset_config.get("silence_duration_ms", 800)
                config.MIN_CHUNK_DURATION = preset_config.get("min_chunk_duration", 0.3)
                config.MAX_CHUNK_DURATION = preset_config.get("max_chunk_duration", 10.0)
            else:
                config.WHISPER_MODEL_SIZE = new_model
                config.WHISPER_DEVICE = new_device
                config.WHISPER_COMPUTE_TYPE = "float16" if new_device == "cuda" else "int8"

            config.TTS_RATE = int(new_speed * 150)
            config.HUD_OPACITY = new_opacity
            config.AUDIO_LOOPBACK_DEVICE = audio_loopback if audio_loopback else None
            config.AUDIO_MIC_DEVICE = audio_mic if audio_mic else None
            config.STT_ENABLED = stt_enabled
            config.TTS_ENABLED = tts_enabled
            config.AUTO_MUTE_DURING_TTS = auto_mute_during_tts
            self.stt_enabled = stt_enabled
            self.tts_enabled = tts_enabled
            self.auto_mute_during_tts = auto_mute_during_tts

            if hasattr(self, "stt_var"):
                self.stt_var.set(self.stt_enabled)
            if hasattr(self, "tts_var"):
                self.tts_var.set(self.tts_enabled)

            pipeline = getattr(self, "_pipeline", None)
            if pipeline and hasattr(self, "auto_mute_during_tts"):
                try:
                    if self.tts and getattr(self.tts, "is_speaking", False):
                        if self.auto_mute_during_tts:
                            pipeline.pause_input("tts")
                        else:
                            pipeline.resume_input("tts")
                    else:
                        if self.auto_mute_during_tts:
                            pipeline.pause_input("tts")
                        else:
                            pipeline.resume_input("tts")
                except Exception as e:
                    logger.debug(f"Auto-mute reconcile error: {e}")

            if self.tts:
                self.tts._rate = config.TTS_RATE

            if self.hud:
                try:
                    self.hud.attributes("-alpha", new_opacity)
                except Exception as e:
                    logger.debug("HUD opacity set: %s", e)

            if self._pipeline:
                logger.info("LiveApp._apply_settings: starting stt-reload thread")
                threading.Thread(
                    target=self._reload_stt_model,
                    args=(config.WHISPER_MODEL_SIZE, config.WHISPER_DEVICE),
                    daemon=True,
                    name="stt-reload",
                ).start()

            config.WHISPER_LANGUAGE = settings.get("whisper_language", config.WHISPER_LANGUAGE)
            config.DIARIZATION_ENABLED = bool(settings.get("diarization_enabled", config.DIARIZATION_ENABLED))
            config.DIARIZATION_MAX_SPEAKERS = int(settings.get("diarization_max_speakers", config.DIARIZATION_MAX_SPEAKERS))
            config.PROCESS_POLL_INTERVAL = float(settings.get("process_poll_interval", config.PROCESS_POLL_INTERVAL))
            config.TTS_DUCK_LEVEL = float(settings.get("tts_duck_level", config.TTS_DUCK_LEVEL))
            config.TTS_VOLUME = float(settings.get("tts_volume", config.TTS_VOLUME))
            config.WHISPER_IDLE_TIMEOUT = float(settings.get("whisper_idle_timeout", config.WHISPER_IDLE_TIMEOUT))
            config.WHISPER_TRANSCRIBE_TIMEOUT_FACTOR = float(settings.get("whisper_transcribe_timeout_factor", config.WHISPER_TRANSCRIBE_TIMEOUT_FACTOR))
            config.WHISPER_NO_SPEECH_THRESHOLD = float(settings.get("whisper_no_speech_threshold", config.WHISPER_NO_SPEECH_THRESHOLD))
            config.WHISPER_LOG_PROB_THRESHOLD = float(settings.get("whisper_log_prob_threshold", config.WHISPER_LOG_PROB_THRESHOLD))
            config.WHISPER_COMPRESSION_RATIO_THRESHOLD = float(settings.get("whisper_compression_ratio_threshold", config.WHISPER_COMPRESSION_RATIO_THRESHOLD))
            config.WHISPER_CONDITION_ON_PREVIOUS_TEXT = bool(settings.get("whisper_condition_on_previous_text", config.WHISPER_CONDITION_ON_PREVIOUS_TEXT))

            config.save_settings({
                "preset": preset,
                "whisper_model": config.WHISPER_MODEL_SIZE,
                "whisper_device": config.WHISPER_DEVICE,
                "whisper_compute_type": config.WHISPER_COMPUTE_TYPE,
                "whisper_beam_size": config.WHISPER_BEAM_SIZE,
                "whisper_language": config.WHISPER_LANGUAGE,
                "tts_speed": new_speed,
                "hud_opacity": new_opacity,
                "audio_loopback": audio_loopback,
                "audio_mic": audio_mic,
                "stt_enabled": self.stt_enabled,
                "tts_enabled": self.tts_enabled,
                "auto_mute_during_tts": self.auto_mute_during_tts,
                "chunk_duration": config.CHUNK_DURATION,
                "silence_duration_ms": config.SILENCE_DURATION_MS,
                "min_chunk_duration": config.MIN_CHUNK_DURATION,
                "max_chunk_duration": config.MAX_CHUNK_DURATION,
                "diarization_enabled": config.DIARIZATION_ENABLED,
                "diarization_max_speakers": config.DIARIZATION_MAX_SPEAKERS,
                "process_poll_interval": config.PROCESS_POLL_INTERVAL,
                "tts_duck_level": config.TTS_DUCK_LEVEL,
                "tts_volume": config.TTS_VOLUME,
                "whisper_idle_timeout": config.WHISPER_IDLE_TIMEOUT,
                "whisper_transcribe_timeout_factor": config.WHISPER_TRANSCRIBE_TIMEOUT_FACTOR,
                "whisper_no_speech_threshold": config.WHISPER_NO_SPEECH_THRESHOLD,
                "whisper_log_prob_threshold": config.WHISPER_LOG_PROB_THRESHOLD,
                "whisper_compression_ratio_threshold": config.WHISPER_COMPRESSION_RATIO_THRESHOLD,
                "whisper_condition_on_previous_text": config.WHISPER_CONDITION_ON_PREVIOUS_TEXT,
            })

            logger.info(f"Settings applied: preset={preset}, model={config.WHISPER_MODEL_SIZE}, device={config.WHISPER_DEVICE}, tts_rate={config.TTS_RATE}, opacity={new_opacity}")
        except Exception as e:
            logger.error(f"Apply settings error: {e}")

    def _reload_stt_model(self, model_size, device=None):
        logger.info(f"LiveApp._reload_stt_model: size={model_size} device={device}")
        try:
            import config
            if self._pipeline:
                if device is not None:
                    self._pipeline.device = device
                self._pipeline.model_size = model_size
                self._pipeline.loopback_device = config.AUDIO_LOOPBACK_DEVICE
                self._pipeline.microphone_device = config.AUDIO_MIC_DEVICE
                logger.info("LiveApp._reload_stt_model: unloading current model")
                self._pipeline.unload_model()
                logger.info("LiveApp._reload_stt_model: loading new model")
                self._pipeline.load_model()
                logger.info(f"STT model reloaded: {model_size} on {self._pipeline.device}")
        except Exception as e:
            logger.error(f"STT model reload error: {e}")

    def _on_close(self):
        if self._shutting_down:
            return
        self._shutting_down = True
        self._capturing = False
        self._paused = False

        if self._pipeline:
            try:
                self._pipeline.stop()
            except Exception as e:
                logger.debug(f"Pipeline stop error: {e}")
            self._pipeline = None

        self.process_detector.stop()

        try:
            self.diarizer.unload()
        except Exception as e:
            logger.debug(f"Diarizer unload error: {e}")

        if self.hud:
            try:
                self.hud.destroy()
            except Exception as e:
                logger.debug(f"HUD destroy error: {e}")

        try:
            self.database.close()
        except Exception as e:
            logger.debug(f"Database close error: {e}")

        import gc
        try:
            import torch
            torch.cuda.empty_cache()
        except Exception as e:
            logger.debug("CUDA cleanup: %s", e)
        gc.collect()

        self.root.after(100, self._final_close)

    def _final_close(self):
        try:
            self.root.destroy()
        except Exception as e:
            logger.debug("Root destroy: %s", e)

    def run(self):
        logger.info("LiveApp.run: starting HUD and Tkinter mainloop")
        self._start_hud()
        logger.info("LiveApp.run: entering mainloop")
        self.root.mainloop()
        logger.info("LiveApp.run: mainloop exited")


if __name__ == "__main__":
    app = LiveApp()
    app.run()
