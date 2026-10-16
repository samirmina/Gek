"""Unit tests for TTS Engine (multi-language voice support)."""
import sys
import os
import threading
import ctypes

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


def _make_tts(**overrides):
    from core.tts_engine import TTSEngine
    tts = TTSEngine.__new__(TTSEngine)
    defaults = dict(
        _rate=150,
        _volume=1.0,
    )
    defaults.update(overrides)
    for k, v in defaults.items():
        setattr(tts, k, v)
    tts._lock = threading.Lock()
    tts._pending = None
    tts._pending_app_pid = None
    tts._playing = False
    tts._tts_thread = None
    tts._pyttsx3_lock = threading.Lock()
    tts._play_proc = None
    tts._installed_voices = {}
    tts._stop_event = threading.Event()
    tts._current_ducker = None
    tts._current_app_name = None
    tts._cancelling = False
    return tts


class TestTTSVoiceMap:
    def test_voice_map_has_all_languages(self):
        from core.tts_engine import SAPI5_VOICE_MAP
        expected = {"en", "ar", "de", "nl", "fr", "ja", "ko", "zh", "es", "pl"}
        assert set(SAPI5_VOICE_MAP.keys()) == expected

    def test_voice_map_values_are_strings(self):
        from core.tts_engine import SAPI5_VOICE_MAP
        for lang, voice_id in SAPI5_VOICE_MAP.items():
            assert isinstance(voice_id, str)
            assert len(voice_id) > 0


class TestTTSEngineInit:
    def test_default_rate(self):
        tts = _make_tts()
        assert tts._rate == 150

    def test_custom_rate(self):
        tts = _make_tts(_rate=200)
        assert tts._rate == 200

    def test_default_volume(self):
        tts = _make_tts()
        assert tts._volume == 1.0

    def test_custom_volume(self):
        tts = _make_tts(_volume=0.5)
        assert tts._volume == 0.5

    def test_empty_installed_voices(self):
        tts = _make_tts()
        assert tts._installed_voices == {}

    def test_get_installed_voices_empty(self):
        tts = _make_tts()
        assert tts.get_installed_voices() == {}

    def test_has_voice_false_when_empty(self):
        tts = _make_tts()
        assert tts.has_voice("en") is False

    def test_has_voice_true_when_installed(self):
        tts = _make_tts()
        tts._installed_voices = {"en": "TTS_MS_EN-US_DAVID_11.0"}
        assert tts.has_voice("en") is True

    def test_has_voice_false_for_missing(self):
        tts = _make_tts()
        tts._installed_voices = {"en": "TTS_MS_EN-US_DAVID_11.0"}
        assert tts.has_voice("ar") is False


class TestTTSAsync:
    def test_speak_async_sets_pending(self):
        tts = _make_tts()
        tts.speak_async("hello", lang_code="en")
        assert tts._pending is not None
        assert tts._pending[0] == "hello"
        assert tts._pending[3] == "en"
        tts.cancel()

    def test_speak_async_overwrites_pending(self):
        tts = _make_tts()
        tts._playing = True
        tts.speak_async("first", lang_code="en")
        tts.speak_async("second", lang_code="ar")
        assert tts._pending[0] == "second"
        assert tts._pending[3] == "ar"
        tts._playing = False
        tts.cancel()

    def test_speak_async_starts_thread(self):
        tts = _make_tts()
        assert tts._playing is False
        tts.speak_async("hello", lang_code="en")
        assert tts._playing is True
        tts.cancel()
        import time
        time.sleep(0.1)

    def test_speak_async_none_lang_code(self):
        tts = _make_tts()
        tts.speak_async("hello", lang_code=None)
        assert tts._pending is not None
        assert tts._pending[3] is None
        tts.cancel()

    def test_stop_clears_pending(self):
        tts = _make_tts()
        tts.speak_async("hello", lang_code="en")
        tts.stop()
        assert tts._pending is None


class TestTTSSpeakingState:
    def test_is_speaking_false_when_idle(self):
        tts = _make_tts()
        assert tts.is_speaking is False

    def test_is_speaking_true_when_playing(self):
        tts = _make_tts()
        tts._playing = True
        assert tts.is_speaking is True

    def test_callback_called_on_speaking_start(self):
        tts = _make_tts()
        events = []
        tts.on_speaking_state_changed = lambda s: events.append(s)
        tts._set_playing(True)
        assert True in events

    def test_callback_called_on_speaking_stop(self):
        tts = _make_tts()
        events = []
        tts.on_speaking_state_changed = lambda s: events.append(s)
        tts._playing = True
        tts._set_playing(False)
        assert False in events

    def test_callback_no_crash_when_none(self):
        tts = _make_tts()
        tts.on_speaking_state_changed = None
        tts._set_playing(True)
        assert tts._playing is True

    def test_callback_no_crash_on_exception(self):
        tts = _make_tts()
        def bad_callback(s):
            raise RuntimeError("fail")
        tts.on_speaking_state_changed = bad_callback
        tts._set_playing(True)
        assert tts._playing is True


class TestTTSLifecycle:
    def test_cancelling_initialized_false(self):
        tts = _make_tts()
        assert tts._cancelling is False

    def test_stop_clears_pending_and_playing(self):
        tts = _make_tts()
        tts._pending = ("text", None, None, None)
        tts._playing = True
        tts.stop()
        assert tts._pending is None
        assert tts._playing is False

    def test_cancel_sets_cancelling_flag(self):
        tts = _make_tts()
        tts._pending = ("text", None, None, None)
        tts._playing = True
        tts.cancel()
        assert tts._cancelling is False
        assert tts._pending is None
        assert tts._playing is False

    def test_speak_async_does_not_overlap_workers(self):
        tts = _make_tts()
        tts._playing = True
        tts._tts_thread = threading.Thread(target=lambda: None)
        tts._tts_thread.start()
        tts.speak_async("first", lang_code="en")
        tts.speak_async("second", lang_code="en")
        assert tts._pending[0] == "second"
        tts.cancel()

    def test_speak_async_stores_app_pid(self):
        tts = _make_tts()
        assert tts._pending_app_pid is None
        tts.speak_async("hello", lang_code="en", app_pid=1234)
        assert tts._pending_app_pid == 1234
        tts.cancel()

    def test_worker_initializes_com(self):
        tts = _make_tts()
        calls = []
        def fake_coinit(*args):
            calls.append(args)
        def fake_coinit_none(*args):
            calls.append(("none",))
        ole = ctypes.windll.ole32
        orig_ex = ole.CoInitializeEx
        orig_none = ole.CoInitialize
        ole.CoInitializeEx = fake_coinit
        ole.CoInitialize = fake_coinit_none
        try:
            tts._pending = ("text", None, None, None)
            tts._playing = True
            tts._stop_event.clear()
            worker = threading.Thread(target=tts._speak_worker, daemon=True)
            worker.start()
            worker.join(timeout=2)
            assert len(calls) > 0
        finally:
            ole.CoInitializeEx = orig_ex
            ole.CoInitialize = orig_none
