"""TTS engine with async playback, per-app audio ducking, and multi-language voice support.

Uses a pending-text pattern: only the latest translation ever plays.
If a new translation arrives while TTS is still speaking, the old one
is discarded and the new one plays immediately after the current finishes.
"""

import os
import tempfile
import threading
import logging
import subprocess
import ctypes

logger = logging.getLogger(__name__)

CREATE_NO_WINDOW = 0x08000000

try:
    hr = ctypes.windll.ole32.CoInitializeEx(None, 0x2)
    if hr == -2147417850:
        pass
    elif hr < 0:
        try:
            ctypes.windll.ole32.CoInitialize(None)
        except Exception:
            pass
except Exception:
    try:
        ctypes.windll.ole32.CoInitialize(None)
    except Exception:
        pass

SAPI5_VOICE_MAP = {
    "en": "David",
    "ar": "Naayf",
    "de": "Hedda",
    "nl": "Frank",
    "fr": "Hortense",
    "ja": "Haruka",
    "ko": "Heami",
    "zh": "Huihui",
    "es": "Helena",
    "pl": "Paulina",
}


class TTSEngine:
    def __init__(self, rate=150, volume=1.0, on_speaking_state_changed=None):
        self._lock = threading.RLock()
        self._pending = None
        self._pending_app_pid = None
        self._playing = False
        self._tts_thread = None
        self._pyttsx3_lock = threading.Lock()
        self._play_proc = None
        self._rate = rate
        self._volume = volume
        self._installed_voices = {}
        self._stop_event = threading.Event()
        self._cancelling = False
        self._current_ducker = None
        self._current_app_name = None
        self._ducked_current = False
        self._detect_voices()
        self.on_speaking_state_changed = on_speaking_state_changed

    def _detect_voices(self):
        logger.info("TTSEngine._detect_voices: detecting installed voices")
        try:
            with self._pyttsx3_lock:
                import pyttsx3
                engine = pyttsx3.init()
                voices = engine.getProperty("voices")
                for v in voices:
                    vid = v.id.lower()
                    for lang_code, expected_id in SAPI5_VOICE_MAP.items():
                        if expected_id.lower() in vid:
                            self._installed_voices[lang_code] = v.id
                            break
                engine.stop()
            logger.info(f"Detected TTS voices: {list(self._installed_voices.keys())}")
        except Exception as e:
            logger.debug(f"Voice detection failed: {e}")

    def get_installed_voices(self):
        return dict(self._installed_voices)

    def has_voice(self, lang_code):
        return lang_code in self._installed_voices

    @property
    def is_speaking(self):
        return bool(self._playing)

    def _notify_speaking_state(self, is_speaking):
        callback = getattr(self, "on_speaking_state_changed", None)
        if callback is None:
            return
        try:
            callback(is_speaking)
        except Exception as e:
            logger.debug(f"TTSEngine speaking-state callback error: {e}")

    def _set_playing(self, is_playing):
        with self._lock:
            if self._playing == is_playing:
                return
            self._playing = is_playing
        self._notify_speaking_state(is_playing)

    def speak(self, text, lang_code=None):
        logger.debug(f"TTSEngine.speak: lang={lang_code!r} text={text[:60]!r}")
        """Synchronous TTS (blocks). Used by history playback."""
        temp_wav = None
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
                temp_wav = f.name
            with self._pyttsx3_lock:
                import pyttsx3
                engine = pyttsx3.init()
                engine.setProperty("rate", self._rate)
                engine.setProperty("volume", self._volume)
                if lang_code and lang_code in self._installed_voices:
                    engine.setProperty("voice", self._installed_voices[lang_code])
                engine.save_to_file(text, temp_wav)
                engine.runAndWait()
                engine.stop()

            if not os.path.exists(temp_wav) or os.path.getsize(temp_wav) == 0:
                logger.warning("TTS WAV file empty or missing")
                return

            ps_cmd = f"(New-Object Media.SoundPlayer '{temp_wav}').PlaySync()"
            subprocess.run(
                ["powershell", "-c", ps_cmd],
                timeout=30,
                creationflags=CREATE_NO_WINDOW,
            )
        except Exception as e:
            logger.error(f"TTS speak error: {e}")
        finally:
            if temp_wav and os.path.exists(temp_wav):
                try:
                    os.unlink(temp_wav)
                except OSError as e:
                    logger.debug("TTS temp cleanup: %s", e)

    def speak_async(self, text, ducker=None, app_name=None, lang_code=None, app_pid=None):
        logger.debug(f"TTSEngine.speak_async: lang={lang_code!r} text={text[:60]!r}")
        """Queue text for async TTS. Only the latest text plays."""
        with self._lock:
            self._stop_event.clear()
            self._pending_app_pid = app_pid
            if self._playing and self._tts_thread and self._tts_thread.is_alive():
                self._pending = (text, ducker, app_name, lang_code)
                self._notify_speaking_state(True)
                return
            self._pending = (text, ducker, app_name, lang_code)
            self._playing = True
            self._tts_thread = threading.Thread(
                target=self._speak_worker,
                daemon=True,
                name="tts-worker",
            )
            logger.debug("TTSEngine.speak_async: starting tts-worker")
            self._tts_thread.start()
        self._notify_speaking_state(self._playing)

    def _speak_worker(self):
        logger.debug(f"TTSEngine._speak_worker: started")
        try:
            ctypes.windll.ole32.CoInitializeEx(None, 0x2)
        except OSError:
            pass
        try:
            while not self._stop_event.is_set():
                with self._lock:
                    if self._pending is None:
                        self._playing = False
                        logger.debug("TTSEngine._speak_worker: no pending, exiting")
                        self._notify_speaking_state(False)
                        return
                    text, ducker, app_name, lang_code = self._pending
                    self._pending = None
                self._generate_and_play(text, ducker, app_name, lang_code, app_pid=self._pending_app_pid)
                if self._stop_event.is_set():
                    self._playing = False
                    self._notify_speaking_state(False)
                    return
                self._notify_speaking_state(self._playing)
        finally:
            try:
                ctypes.windll.ole32.CoUninitialize()
            except OSError:
                pass

    def _generate_and_play(self, text, ducker, app_name, lang_code=None, app_pid=None):
        logger.debug(f"TTSEngine._generate_and_play: lang={lang_code!r} text={text[:60]!r}")
        with self._lock:
            if self._stop_event.is_set():
                return
            self._current_ducker = ducker
            self._current_app_name = app_name
            self._ducked_current = False
        temp_wav = None
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
                temp_wav = f.name
            with self._pyttsx3_lock:
                import pyttsx3
                engine = pyttsx3.init()
                engine.setProperty("rate", self._rate)
                engine.setProperty("volume", self._volume)
                if lang_code and lang_code in self._installed_voices:
                    engine.setProperty("voice", self._installed_voices[lang_code])
                engine.save_to_file(text, temp_wav)
                engine.runAndWait()
                engine.stop()
            if not os.path.exists(temp_wav) or os.path.getsize(temp_wav) == 0:
                logger.warning("TTS WAV file empty or missing")
                return
            with self._lock:
                if self._stop_event.is_set():
                    return
            if ducker and app_name:
                ducked = ducker.duck(app_name, pid=app_pid)
                with self._lock:
                    self._ducked_current = ducked
            with self._lock:
                if self._stop_event.is_set():
                    if self._ducked_current and ducker and app_name:
                        try:
                            ducker.restore(app_name, pid=app_pid)
                        except Exception:
                            pass
                    self._ducked_current = False
                    self._current_ducker = None
                    self._current_app_name = None
                    self._play_proc = None
                    return
                ps_cmd = f"(New-Object Media.SoundPlayer '{temp_wav}').PlaySync()"
                self._play_proc = subprocess.Popen(
                    ["powershell", "-c", ps_cmd],
                    creationflags=CREATE_NO_WINDOW,
                )
            self._play_proc.wait()
        except Exception as e:
            logger.error(f"TTS worker error: {e}")
        finally:
            with self._lock:
                was_ducked = self._ducked_current
                self._ducked_current = False
                self._current_ducker = None
                self._current_app_name = None
                self._play_proc = None
            if was_ducked and ducker and app_name:
                try:
                    ducker.restore(app_name, pid=app_pid)
                except Exception as e:
                    logger.debug("Ducker restore failed: %s", e)
            if temp_wav and os.path.exists(temp_wav):
                try:
                    os.unlink(temp_wav)
                except OSError as e:
                    logger.debug("TTS temp cleanup: %s", e)

    def cancel(self):
        logger.debug("TTSEngine.cancel: stopping current playback")
        self._cancelling = True
        self._stop_event.set()
        with self._lock:
            self._pending = None
            self._playing = False
            proc = self._play_proc
        if proc and proc.poll() is None:
            try:
                proc.kill()
            except OSError as e:
                logger.debug("TTS cancel kill: %s", e)
        if self._tts_thread and self._tts_thread.is_alive():
            self._tts_thread.join(timeout=1)
        self._cancelling = False
        self._notify_speaking_state(False)

    def stop(self):
        logger.info("TTSEngine.stop: stopping all TTS")
        self._stop_event.set()
        with self._lock:
            self._pending = None
            self._playing = False
            proc = self._play_proc
        if proc and proc.poll() is None:
            try:
                proc.kill()
            except OSError as e:
                logger.debug("TTS stop kill: %s", e)
        if self._tts_thread and self._tts_thread.is_alive():
            self._tts_thread.join(timeout=2)
        self._notify_speaking_state(False)
        logger.debug("TTSEngine.stop: complete")
