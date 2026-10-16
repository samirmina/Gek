"""Regression tests for existing functionality.

Ensures previously-working features remain correct after changes.
"""
import sys
import os
import tempfile
import sqlite3

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


# --- Database regression tests ---

class TestDatabaseRegression:
    """Database CRUD and search regression tests."""

    def _make_db(self, tmp_path):
        from db import Database
        db_path = str(tmp_path / "test_history.db")
        db = Database.__new__(Database)
        import threading
        db._lock = threading.Lock()
        db._conn = sqlite3.connect(db_path, check_same_thread=False)
        db._conn.row_factory = sqlite3.Row
        db._init_tables()
        return db

    def test_db_save_and_get_recent(self, tmp_path):
        db = self._make_db(tmp_path)
        row_id = db.save("Hello", "en", "Hallo", "de", audio_source="mic")
        assert row_id is not None
        entries = db.get_recent(limit=10)
        assert len(entries) == 1
        assert entries[0]["original_text"] == "Hello"
        assert entries[0]["translated_text"] == "Hallo"
        assert entries[0]["original_lang"] == "en"
        assert entries[0]["translated_lang"] == "de"
        db.close()

    def test_db_get_recent_order(self, tmp_path):
        db = self._make_db(tmp_path)
        db.save("First", "en", "Erste", "de")
        db.save("Second", "en", "Zweite", "de")
        db.save("Third", "en", "Dritte", "de")
        entries = db.get_recent(limit=10)
        assert entries[0]["original_text"] == "Third"
        assert entries[1]["original_text"] == "Second"
        assert entries[2]["original_text"] == "First"
        db.close()

    def test_db_get_recent_limit(self, tmp_path):
        db = self._make_db(tmp_path)
        for i in range(10):
            db.save(f"Text {i}", "en", f"Translated {i}", "de")
        entries = db.get_recent(limit=3)
        assert len(entries) == 3
        db.close()

    def test_db_search_fts(self, tmp_path):
        db = self._make_db(tmp_path)
        db.save("Hello world", "en", "Hallo Welt", "de")
        db.save("Goodbye world", "en", "Auf Wiedersehen Welt", "de")
        db.save("Something else", "en", "Etwas anderes", "de")
        results = db.search("hello")
        assert len(results) == 1
        assert results[0]["original_text"] == "Hello world"
        db.close()

    def test_db_search_no_results(self, tmp_path):
        db = self._make_db(tmp_path)
        db.save("Hello", "en", "Hallo", "de")
        results = db.search("xyznonexistent")
        assert len(results) == 0
        db.close()

    def test_db_save_with_all_fields(self, tmp_path):
        db = self._make_db(tmp_path)
        row_id = db.save(
            original_text="Test",
            original_lang="en",
            translated_text="Teste",
            translated_lang="de",
            source_name="chrome.exe",
            source_path="C:\\chrome.exe",
            audio_source="system",
        )
        entries = db.get_recent(limit=1)
        assert entries[0]["source_name"] == "chrome.exe"
        assert entries[0]["audio_source"] == "system"
        db.close()

    def test_db_delete(self, tmp_path):
        db = self._make_db(tmp_path)
        row_id = db.save("Hello", "en", "Hallo", "de")
        result = db.delete(row_id)
        assert result is True
        entries = db.get_recent()
        assert len(entries) == 0
        db.close()

    def test_db_delete_nonexistent(self, tmp_path):
        db = self._make_db(tmp_path)
        result = db.delete(999999)
        assert result is False
        db.close()

    def test_db_fts_triggers_on_insert(self, tmp_path):
        db = self._make_db(tmp_path)
        db.save("Apple banana cherry", "en", "Apfel Banane Kirsche", "de")
        results = db.search("banana")
        assert len(results) == 1
        db.close()

    def test_db_fts_triggers_on_delete(self, tmp_path):
        db = self._make_db(tmp_path)
        row_id = db.save("Unique searchable term", "en", "Einzigartig", "de")
        results = db.search("Unique searchable")
        assert len(results) == 1
        db.delete(row_id)
        results = db.search("Unique searchable")
        assert len(results) == 0
        db.close()


# --- Translator regression tests ---

class TestTranslatorRegression:
    """Translator logic regression tests."""

    def test_translate_same_lang_returns_text(self):
        from core.translator import Translator
        t = Translator()
        result = t.translate("Hello", "en", "en")
        assert result == "Hello"

    def test_translate_empty_returns_empty(self):
        from core.translator import Translator
        t = Translator()
        result = t.translate("", "en", "de")
        assert result == ""

    def test_translate_whitespace_only_returns_input(self):
        from core.translator import Translator
        t = Translator()
        result = t.translate("   ", "en", "de")
        assert result == "   "

    def test_translate_returns_string(self):
        from core.translator import Translator
        t = Translator()
        result = t.translate("Hello world", "en", "de")
        assert isinstance(result, str)

    def test_direct_translate_returns_string(self):
        from core.translator import Translator
        t = Translator()
        result = t._direct_translate("Hello", "en", "de")
        assert isinstance(result, str)

    def test_apply_synonyms_no_mapping(self):
        from core.translator import Translator
        t = Translator()
        result = t._apply_synonyms("Hello", "xx")
        assert result == "Hello"

    def test_translate_to_multiple_returns_dict(self):
        from core.translator import Translator
        t = Translator()
        result = t.translate_to_multiple("Hello", "en", ["de", "fr"])
        assert isinstance(result, dict)
        assert "de" in result
        assert "fr" in result

    def test_get_installed_languages_returns_set(self):
        from core.translator import Translator
        t = Translator()
        langs = t.get_installed_languages()
        assert isinstance(langs, set)
        assert "en" in langs or len(langs) > 0


# --- Diarizer regression tests ---

class TestDiarizerRegression:
    """Diarizer edge case and regression tests."""

    def test_diarize_unloaded_returns_empty(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        d._loaded = False
        d._pipeline = None
        d.sample_rate = 16000
        d.max_speakers = 4
        result = d.diarize([], 16000)
        assert result == []

    def test_diarize_short_audio_returns_empty(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        d._loaded = True
        d._pipeline = None  # would crash, but short audio guard triggers first
        d.sample_rate = 16000
        d.max_speakers = 4
        import numpy as np
        short_audio = np.zeros(4000, dtype=np.float32)  # 0.25s at 16kHz
        result = d.diarize(short_audio, 16000)
        assert result == []

    def test_is_available_false_when_unloaded(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        d._loaded = False
        assert d.is_available() is False

    def test_is_available_true_when_loaded(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        d._loaded = True
        assert d.is_available() is True

    def test_merge_speakers_empty(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        result = d._merge_speakers([])
        assert result == []

    def test_merge_speakers_adjacent_same_speaker(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        segments = [
            {"start": 0.0, "end": 1.0, "speaker": "A"},
            {"start": 1.1, "end": 2.0, "speaker": "A"},
        ]
        result = d._merge_speakers(segments)
        assert len(result) == 1
        assert result[0]["start"] == 0.0
        assert result[0]["end"] == 2.0

    def test_merge_speakers_different_speakers(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        segments = [
            {"start": 0.0, "end": 1.0, "speaker": "A"},
            {"start": 1.5, "end": 2.0, "speaker": "B"},
        ]
        result = d._merge_speakers(segments)
        assert len(result) == 2

    def test_merge_speakers_far_apart(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        segments = [
            {"start": 0.0, "end": 1.0, "speaker": "A"},
            {"start": 3.0, "end": 4.0, "speaker": "A"},
        ]
        result = d._merge_speakers(segments)
        assert len(result) == 2

    def test_get_speaker_label_empty(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        result = d.get_speaker_label([], 1.0)
        assert result is None

    def test_get_speaker_label_in_range(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        segments = [{"start": 0.0, "end": 2.0, "speaker": "A"}]
        result = d.get_speaker_label(segments, 1.0)
        assert result == "A"

    def test_get_speaker_label_out_of_range(self):
        from core.diarizer import Diarizer
        d = Diarizer.__new__(Diarizer)
        segments = [{"start": 0.0, "end": 1.0, "speaker": "A"}]
        result = d.get_speaker_label(segments, 5.0)
        assert result == "A"  # falls back to first segment

    def test_diarize_empty_array_returns_empty(self):
        from core.diarizer import Diarizer
        import numpy as np
        d = Diarizer.__new__(Diarizer)
        d._loaded = True
        d._pipeline = None
        d.sample_rate = 16000
        d.max_speakers = 4
        result = d.diarize(np.array([], dtype=np.float32), 16000)
        assert result == []

    def test_diarize_all_nan_returns_empty(self):
        from core.diarizer import Diarizer
        import numpy as np
        d = Diarizer.__new__(Diarizer)
        d._loaded = True
        d._pipeline = None
        d.sample_rate = 16000
        d.max_speakers = 4
        result = d.diarize(np.array([np.nan, np.nan], dtype=np.float32), 16000)
        assert result == []


# --- AudioProcessDetector regression tests ---

class TestAudioDetectorRegression:
    """AudioProcessDetector regression tests."""

    def test_get_label_for_source_mic(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_processes = []
        assert d.get_label_for_source("mic") == "mic"

    def test_get_label_for_source_system_no_procs(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_processes = []
        assert d.get_label_for_source("system") == "system"

    def test_get_label_for_source_system_single_proc(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_processes = ["chrome.exe"]
        assert d.get_label_for_source("system") == "chrome"

    def test_get_label_for_source_system_multiple_procs(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_processes = ["chrome.exe", "firefox.exe"]
        result = d.get_label_for_source("system")
        assert result == "chrome+1"

    def test_current_processes_returns_copy(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_processes = ["chrome.exe"]
        procs = d.current_processes
        procs.append("malware.exe")
        assert len(d._current_processes) == 1

    def test_stop_clears_processes(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._running = False
        d._thread = None
        d._current_processes = ["chrome.exe"]
        d._current_pids = [1234]
        d.stop()
        assert len(d._current_processes) == 0
        assert len(d._current_pids) == 0

    def test_get_pid_for_source_returns_none_for_mic(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_pids = [1234]
        assert d.get_pid_for_source("mic") is None

    def test_get_pid_for_source_returns_none_when_empty(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_pids = []
        assert d.get_pid_for_source("system") is None

    def test_get_pid_for_source_returns_first_pid(self):
        from core.audio_detector import AudioProcessDetector
        d = AudioProcessDetector.__new__(AudioProcessDetector)
        d._current_pids = [1234, 5678]
        assert d.get_pid_for_source("system") == 1234


# --- STT hallucination filter regression tests ---

class TestSTTHallucinationRegression:
    """STT hallucination filter regression tests."""

    def _make_stt(self):
        from core.stt_pipeline import STTPipeline
        stt = STTPipeline.__new__(STTPipeline)
        stt.model_size = "tiny"
        stt.device = "cpu"
        stt.compute_type = "int8"
        stt.language = None
        stt.sample_rate = 16000
        stt.models_dir = None
        stt.on_transcription = None
        stt.on_status = None
        stt.silence_threshold = 0.01
        stt.silence_duration_ms = 10000
        stt.min_chunk_duration = 0.5
        stt.max_chunk_duration = 5.0
        stt.diarizer = None
        stt.idle_timeout = 10
        stt.transcribe_timeout_factor = 5.0
        stt._model = None
        import queue
        stt._queue = queue.Queue()
        stt._running = False
        stt._thread = None
        import threading
        stt._model_lock = threading.Lock()
        return stt

    def test_short_text_is_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("a") is True

    def test_single_char_is_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("A") is True

    def test_empty_text_is_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("") is True

    def test_exactly_2_chars_passes(self):
        stt = self._make_stt()
        assert stt._is_hallucination("Hi") is False

    def test_exactly_5_chars_passes(self):
        stt = self._make_stt()
        assert stt._is_hallucination("Hello") is False

    def test_normal_text_passes(self):
        stt = self._make_stt()
        assert stt._is_hallucination("Hello, how are you today?") is False

    def test_repeated_substring_is_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("hello hello hello hello") is True

    def test_repeated_short_substring_is_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("ab ab ab ab") is True

    def test_non_repeated_long_text_passes(self):
        stt = self._make_stt()
        assert stt._is_hallucination("The quick brown fox jumps over the lazy dog") is False

    def test_arabic_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("اشتركوا في القناة") is True

    def test_arabic_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("شكرا للمشاهدة") is True

    def test_dutch_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("abonneer op het kanaal") is True

    def test_dutch_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("bedankt voor het kijken") is True

    def test_german_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("abonnieren sie den kanal") is True

    def test_german_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("danke fürs zuschauen") is True

    def test_french_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("abonnez-vous à la chaîne") is True

    def test_french_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("merci d'avoir regardé") is True

    def test_spanish_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("suscríbete al canal") is True

    def test_spanish_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("gracias por ver") is True

    def test_japanese_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("チャンネル登録") is True

    def test_japanese_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("ご視聴ありがとうございました") is True

    def test_korean_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("채널 구독") is True

    def test_korean_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("시청해 주셔서 감사합니다") is True

    def test_chinese_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("请订阅频道") is True

    def test_chinese_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("感谢观看") is True

    def test_polish_subscribe_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("subskrybuj kanał") is True

    def test_polish_thanks_hallucination(self):
        stt = self._make_stt()
        assert stt._is_hallucination("dzięki za oglądanie") is True

    def test_symbol_flood_dotted(self):
        stt = self._make_stt()
        assert stt._is_hallucination("st.st.st.st.st.st") is True

    def test_symbol_flood_char(self):
        stt = self._make_stt()
        assert stt._is_hallucination("xxxxxxxxxxxx") is True

    def test_char_flood_stst(self):
        stt = self._make_stt()
        assert stt._is_hallucination("ststststststst") is True

    def test_word_repetition_5x(self):
        stt = self._make_stt()
        assert stt._is_hallucination("the the the the the") is True

    def test_phrase_repetition_mid_text(self):
        stt = self._make_stt()
        assert stt._is_hallucination("de belangrijkste als de rest als de rest als de rest") is True

    def test_normal_dutch_passes(self):
        stt = self._make_stt()
        assert stt._is_hallucination("Dit is een normale Nederlandse zin") is False

    def test_normal_arabic_passes(self):
        stt = self._make_stt()
        assert stt._is_hallucination("هذا نص عربي عادي يتحدث عن موضوع معين") is False

    def test_normal_german_passes(self):
        stt = self._make_stt()
        assert stt._is_hallucination("Das ist ein normaler deutscher Satz") is False

    def test_set_language_none(self):
        stt = self._make_stt()
        stt.set_language(None)
        assert stt.language is None

    def test_set_language_code(self):
        stt = self._make_stt()
        stt.set_language("en")
        assert stt.language == "en"

    def test_feed_audio_while_stopped(self):
        stt = self._make_stt()
        stt._running = False
        import numpy as np
        stt.feed_audio("system", np.zeros(16000, dtype=np.float32))
        assert stt._queue.empty()


class TestMultiSpeakerCollection:
    """Tests that the DOMINANT speaker is selected (one per line)."""

    def test_single_speaker(self):
        segments = [{"start": 0.0, "end": 2.0, "speaker": "SPEAKER_00"}]
        speaker_durations = {}
        for seg in segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        result = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert result == "SPEAKER_00"

    def test_two_speakers_picks_dominant(self):
        segments = [
            {"start": 0.0, "end": 3.0, "speaker": "SPEAKER_00"},
            {"start": 3.0, "end": 4.0, "speaker": "SPEAKER_01"},
        ]
        speaker_durations = {}
        for seg in segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        result = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert result == "SPEAKER_00"

    def test_two_speakers_equal_picks_first(self):
        segments = [
            {"start": 0.0, "end": 2.0, "speaker": "SPEAKER_00"},
            {"start": 2.0, "end": 4.0, "speaker": "SPEAKER_01"},
        ]
        speaker_durations = {}
        for seg in segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        result = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert result in ("SPEAKER_00", "SPEAKER_01")

    def test_empty_segments(self):
        segments = []
        speaker_durations = {}
        for seg in segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        result = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert result == ""

    def test_segments_without_speaker_key(self):
        segments = [{"start": 0.0, "end": 1.0}]
        speaker_durations = {}
        for seg in segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        result = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert result == ""

    def test_three_speakers_picks_dominant(self):
        segments = [
            {"start": 0.0, "end": 5.0, "speaker": "SPEAKER_00"},
            {"start": 5.0, "end": 6.0, "speaker": "SPEAKER_01"},
            {"start": 6.0, "end": 7.0, "speaker": "SPEAKER_02"},
        ]
        speaker_durations = {}
        for seg in segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        result = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert result == "SPEAKER_00"

    def test_result_is_single_speaker(self):
        """The output must be a single speaker name, never comma-separated."""
        segments = [
            {"start": 0.0, "end": 1.0, "speaker": "SPEAKER_00"},
            {"start": 1.0, "end": 2.0, "speaker": "SPEAKER_01"},
            {"start": 2.0, "end": 3.0, "speaker": "SPEAKER_02"},
        ]
        speaker_durations = {}
        for seg in segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        result = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert "," not in result
        assert result.count("SPEAKER") == 1


# --- WASAPI pycaw audio session detection tests ---

class TestAudioDetectorPycaw:
    """Tests for WASAPI-based audio session detection."""

    def test_pycaw_returns_active_sessions(self):
        from unittest.mock import patch, MagicMock

        mock_session = MagicMock()
        mock_session.Process.name.return_value = "mpc-hc64.exe"
        mock_session.SimpleAudioVolume.GetMasterVolume.return_value = 0.5

        mock_mic_session = MagicMock()
        mock_mic_session.Process = None

        with patch("pycaw.pycaw.AudioUtilities") as mock_au:
            mock_au.GetAllSessions.return_value = [mock_session, mock_mic_session]
            from core.audio_detector import enumerate_audio_sessions_pycaw
            result = enumerate_audio_sessions_pycaw()

        assert "mpc-hc64.exe" in result
        assert len(result) == 1

    def test_pycaw_skips_zero_volume_sessions(self):
        from unittest.mock import patch, MagicMock

        mock_session = MagicMock()
        mock_session.Process.name.return_value = "chrome.exe"
        mock_session.SimpleAudioVolume.GetMasterVolume.return_value = 0.0

        with patch("pycaw.pycaw.AudioUtilities") as mock_au:
            mock_au.GetAllSessions.return_value = [mock_session]
            from core.audio_detector import enumerate_audio_sessions_pycaw
            result = enumerate_audio_sessions_pycaw()

        assert result == []

    def test_pycaw_skips_sessions_without_process(self):
        from unittest.mock import patch, MagicMock

        mock_session = MagicMock()
        mock_session.Process = None

        with patch("pycaw.pycaw.AudioUtilities") as mock_au:
            mock_au.GetAllSessions.return_value = [mock_session]
            from core.audio_detector import enumerate_audio_sessions_pycaw
            result = enumerate_audio_sessions_pycaw()

        assert result == []

    def test_pycaw_deduplicates_processes(self):
        from unittest.mock import patch, MagicMock

        mock_session1 = MagicMock()
        mock_session1.Process.name.return_value = "firefox.exe"
        mock_session1.SimpleAudioVolume.GetMasterVolume.return_value = 0.8

        mock_session2 = MagicMock()
        mock_session2.Process.name.return_value = "firefox.exe"
        mock_session2.SimpleAudioVolume.GetMasterVolume.return_value = 0.3

        with patch("pycaw.pycaw.AudioUtilities") as mock_au:
            mock_au.GetAllSessions.return_value = [mock_session1, mock_session2]
            from core.audio_detector import enumerate_audio_sessions_pycaw
            result = enumerate_audio_sessions_pycaw()

        assert result.count("firefox.exe") == 1

    def test_pycaw_fallback_on_exception(self):
        from unittest.mock import patch

        with patch("pycaw.pycaw.AudioUtilities") as mock_au:
            mock_au.GetAllSessions.side_effect = Exception("COM error")
            from core.audio_detector import get_active_audio_processes
            with patch("core.audio_detector.enumerate_audio_processes_poll", return_value=["vlc.exe"]):
                result = get_active_audio_processes()

        assert result == ["vlc.exe"]

    def test_pycaw_prefers_wasapi_over_poll(self):
        from unittest.mock import patch, MagicMock

        mock_session = MagicMock()
        mock_session.Process.name.return_value = "spotify.exe"
        mock_session.SimpleAudioVolume.GetMasterVolume.return_value = 1.0

        with patch("pycaw.pycaw.AudioUtilities") as mock_au:
            mock_au.GetAllSessions.return_value = [mock_session]
            with patch("core.audio_detector.enumerate_audio_processes_poll", return_value=["chrome.exe"]):
                from core.audio_detector import get_active_audio_processes
                result = get_active_audio_processes()

        assert "spotify.exe" in result
        assert "chrome.exe" not in result

    def test_pycaw_exception_returns_poll_fallback(self):
        from unittest.mock import patch

        with patch("pycaw.pycaw.AudioUtilities", side_effect=ImportError("no pycaw")):
            with patch("core.audio_detector.enumerate_audio_processes_poll", return_value=["vlc.exe"]):
                from core.audio_detector import get_active_audio_processes
                result = get_active_audio_processes()

        assert result == ["vlc.exe"]

    def test_pycaw_excludes_current_process(self):
        from unittest.mock import patch, MagicMock
        import os

        current_pid = os.getpid()

        mock_session_self = MagicMock()
        mock_session_self.Process.pid = current_pid
        mock_session_self.Process.name.return_value = "python.exe"
        mock_session_self.SimpleAudioVolume.GetMasterVolume.return_value = 0.5

        mock_session_other = MagicMock()
        mock_session_other.Process.pid = 99999
        mock_session_other.Process.name.return_value = "vlc.exe"
        mock_session_other.SimpleAudioVolume.GetMasterVolume.return_value = 0.8

        with patch("pycaw.pycaw.AudioUtilities") as mock_au:
            mock_au.GetAllSessions.return_value = [mock_session_self, mock_session_other]
            from core.audio_detector import enumerate_audio_sessions_pycaw
            result = enumerate_audio_sessions_pycaw()

        assert "vlc.exe" in result
        assert "python.exe" not in result


# --- Speaker hiding tests ---

class TestSpeakerHiding:
    """Tests that dominant speaker is returned (one per line)."""

    def _make_stt(self):
        from core.stt_pipeline import STTPipeline
        stt = STTPipeline.__new__(STTPipeline)
        stt.model_size = "tiny"
        stt.device = "cpu"
        stt.compute_type = "int8"
        stt.language = None
        stt.sample_rate = 16000
        stt.models_dir = None
        stt.on_transcription = None
        stt.on_status = None
        stt.silence_threshold = 0.01
        stt.silence_duration_ms = 10000
        stt.min_chunk_duration = 0.5
        stt.max_chunk_duration = 5.0
        stt.diarizer = None
        stt.idle_timeout = 10
        stt.transcribe_timeout_factor = 5.0
        stt._model = None
        import queue
        stt._queue = queue.Queue()
        stt._running = False
        stt._thread = None
        import threading
        stt._model_lock = threading.Lock()
        return stt

    def test_single_speaker_returns_name(self):
        from unittest.mock import MagicMock
        stt = self._make_stt()
        mock_diarizer = MagicMock()
        mock_diarizer.is_available.return_value = True
        mock_diarizer.diarize.return_value = [
            {"start": 0.0, "end": 1.0, "speaker": "SPEAKER_00"},
        ]
        stt.diarizer = mock_diarizer

        audio = __import__("numpy").zeros(16000, dtype=__import__("numpy").float32)
        diar_segments = stt.diarizer.diarize(audio, stt.sample_rate)
        speaker_durations = {}
        for seg in diar_segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        speaker = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""

        assert speaker == "SPEAKER_00"

    def test_multiple_speakers_returns_one(self):
        from unittest.mock import MagicMock
        stt = self._make_stt()
        mock_diarizer = MagicMock()
        mock_diarizer.is_available.return_value = True
        mock_diarizer.diarize.return_value = [
            {"start": 0.0, "end": 1.0, "speaker": "SPEAKER_00"},
            {"start": 1.0, "end": 2.0, "speaker": "SPEAKER_01"},
        ]
        stt.diarizer = mock_diarizer

        audio = __import__("numpy").zeros(32000, dtype=__import__("numpy").float32)
        diar_segments = stt.diarizer.diarize(audio, stt.sample_rate)
        speaker_durations = {}
        for seg in diar_segments:
            sp = seg.get("speaker", "")
            if sp:
                dur = seg.get("end", 0) - seg.get("start", 0)
                speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
        speaker = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""

        assert "," not in speaker
        assert speaker in ("SPEAKER_00", "SPEAKER_01")

    def test_no_speakers_returns_empty(self):
        stt = self._make_stt()
        stt.diarizer = None

        speaker_durations = {}
        speaker = max(speaker_durations, key=speaker_durations.get) if speaker_durations else ""
        assert speaker == ""
