"""Unit tests for STT Engine (hallucination filter + queue logic)."""
import sys
import os
import queue
import threading

import numpy as np
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


def _make_stt(**overrides):
    from core.stt_pipeline import STTPipeline
    stt = STTPipeline.__new__(STTPipeline)
    defaults = dict(
        model_size="tiny", device="cpu", compute_type="int8",
        language=None, sample_rate=16000, models_dir=None,
        on_transcription=None, on_status=None,
        silence_threshold=0.01, silence_duration_ms=10000,
        min_chunk_duration=0.5, max_chunk_duration=5.0,
        carryover_duration=0.2,
        diarizer=None, idle_timeout=0, transcribe_timeout_factor=0.0,
        beam_size=1, no_speech_threshold=0.6,
        log_prob_threshold=-1.0, compression_ratio_threshold=2.4,
        condition_on_previous_text=False,
        loopback_device=None, microphone_device=None,
    )
    defaults.update(overrides)
    for k, v in defaults.items():
        setattr(stt, k, v)
    stt._model = None
    stt._queue = queue.Queue(maxsize=30)
    stt._transcribe_queue = queue.Queue()
    stt._running = False
    stt._thread = None
    stt._model_lock = threading.Lock()
    stt._transcribe_workers = []
    stt._pause_reasons = set()
    stt._state_lock = threading.RLock()
    stt._buffer_reset_event = threading.Event()
    return stt


class TestHallucinationFilter:
    def test_empty_is_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("") is True

    def test_single_char_is_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("a") is True

    def test_two_chars_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("ab") is False

    def test_exactly_2_chars_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("OK") is False

    def test_exactly_5_chars_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("Hello") is False

    def test_normal_sentence_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("The quick brown fox jumps over the lazy dog") is False

    def test_repeated_2char_substring(self):
        stt = _make_stt()
        assert stt._is_hallucination("ab ab ab ab") is True

    def test_repeated_3char_substring(self):
        stt = _make_stt()
        assert stt._is_hallucination("ha ha ha ha ha") is True

    def test_repeated_long_substring(self):
        stt = _make_stt()
        assert stt._is_hallucination("hello hello hello hello hello") is True

    def test_non_repeated_similar_words_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("hello help her horse hat") is False

    def test_repetition_at_start(self):
        stt = _make_stt()
        assert stt._is_hallucination("bye bye bye bye done ok") is True

    def test_repetition_at_end_detected(self):
        stt = _make_stt()
        assert stt._is_hallucination("ok done blah blah blah blah") is True

    def test_thanks_for_watching_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("Thanks for watching!") is True

    def test_thank_you_for_watching_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("Thank you for watching!") is True

    def test_please_subscribe_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("Please subscribe to my channel") is True

    def test_see_you_next_time_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("See you next time!") is True

    def test_bye_bye_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("Bye bye!") is True

    def test_like_and_subscribe_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("Like and subscribe!") is True

    def test_chinese_subtitles_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("字幕") is True

    def test_normal_text_not_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("This is a normal transcription of speech") is False

    def test_case_insensitive_hallucination(self):
        stt = _make_stt()
        assert stt._is_hallucination("THANKS FOR WATCHING") is True

    def test_arabic_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("اشتركوا في القناة") is True

    def test_arabic_thanks(self):
        stt = _make_stt()
        assert stt._is_hallucination("شكرا للمشاهدة") is True

    def test_dutch_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("abonneer op het kanaal") is True

    def test_dutch_thanks(self):
        stt = _make_stt()
        assert stt._is_hallucination("bedankt voor het kijken") is True

    def test_german_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("abonnieren sie den kanal") is True

    def test_french_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("abonnez-vous à la chaîne") is True

    def test_spanish_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("suscríbete al canal") is True

    def test_japanese_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("チャンネル登録") is True

    def test_japanese_thanks(self):
        stt = _make_stt()
        assert stt._is_hallucination("ご視聴ありがとうございました") is True

    def test_korean_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("채널 구독") is True

    def test_korean_thanks(self):
        stt = _make_stt()
        assert stt._is_hallucination("시청해 주셔서 감사합니다") is True

    def test_chinese_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("请订阅频道") is True

    def test_chinese_thanks(self):
        stt = _make_stt()
        assert stt._is_hallucination("感谢观看") is True

    def test_polish_subscribe(self):
        stt = _make_stt()
        assert stt._is_hallucination("subskrybuj kanał") is True

    def test_polish_thanks(self):
        stt = _make_stt()
        assert stt._is_hallucination("dzięki za oglądanie") is True

    def test_symbol_flood_dotted(self):
        stt = _make_stt()
        assert stt._is_hallucination("st.st.st.st.st.st") is True

    def test_symbol_flood_ha(self):
        stt = _make_stt()
        assert stt._is_hallucination("ha.ha.ha.ha.ha") is True

    def test_char_flood(self):
        stt = _make_stt()
        assert stt._is_hallucination("xxxxxxxxxxxx") is True

    def test_char_flood_stst(self):
        stt = _make_stt()
        assert stt._is_hallucination("ststststststst") is True

    def test_word_repetition_5x(self):
        stt = _make_stt()
        assert stt._is_hallucination("the the the the the") is True

    def test_phrase_repetition_mid_text(self):
        stt = _make_stt()
        assert stt._is_hallucination("de belangrijkste als de rest als de rest als de rest") is True

    def test_normal_dutch_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("Dit is een normale Nederlandse zin") is False

    def test_normal_arabic_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("هذا نص عربي عادي يتحدث عن موضوع معين") is False

    def test_normal_german_passes(self):
        stt = _make_stt()
        assert stt._is_hallucination("Das ist ein normaler deutscher Satz") is False


class TestQueueLogic:
    def test_feed_audio_queues_data(self):
        stt = _make_stt()
        stt._running = True
        audio = np.zeros(16000, dtype=np.float32)
        stt.feed_audio("system", audio)
        assert not stt._queue.empty()

    def test_feed_audio_while_stopped(self):
        stt = _make_stt()
        stt._running = False
        audio = np.zeros(16000, dtype=np.float32)
        stt.feed_audio("system", audio)
        assert stt._queue.empty()

    def test_feed_audio_drops_oldest_when_full(self):
        stt = _make_stt()
        stt._running = True
        audio = np.zeros(100, dtype=np.float32)
        for _ in range(35):  # queue maxsize=30
            stt.feed_audio("system", audio)
        assert stt._queue.qsize() <= 30

    def test_feed_audio_source_label_preserved(self):
        stt = _make_stt()
        stt._running = True
        audio = np.zeros(100, dtype=np.float32)
        stt.feed_audio("mic", audio)
        label, data = stt._queue.get_nowait()
        assert label == "mic"


class TestSTTSetLanguage:
    def test_set_language_none(self):
        stt = _make_stt()
        stt.set_language(None)
        assert stt.language is None

    def test_set_language_code(self):
        stt = _make_stt()
        stt.set_language("en")
        assert stt.language == "en"

    def test_set_language_overwrites(self):
        stt = _make_stt()
        stt.set_language("en")
        stt.set_language("ar")
        assert stt.language == "ar"


class TestSTTRMS:
    def test_rms_empty(self):
        stt = _make_stt()
        assert stt._rms(np.array([], dtype=np.float32)) == 0.0

    def test_rms_silence(self):
        stt = _make_stt()
        assert stt._rms(np.zeros(16000, dtype=np.float32)) == 0.0

    def test_rms_loud(self):
        stt = _make_stt()
        audio = np.ones(16000, dtype=np.float32)
        rms = stt._rms(audio)
        assert rms > 0.9

    def test_is_silence_below_threshold(self):
        stt = _make_stt(silence_threshold=0.01)
        assert stt._is_silence(np.zeros(16000, dtype=np.float32)) is True

    def test_is_silence_above_threshold(self):
        stt = _make_stt(silence_threshold=0.01)
        audio = np.ones(16000, dtype=np.float32) * 0.5
        assert stt._is_silence(audio) is False


class TestUnloadModel:
    def test_unload_sets_model_none(self):
        stt = _make_stt()
        stt._model = "fake_model"
        stt.unload_model()
        assert stt._model is None

    def test_unload_when_already_none(self):
        stt = _make_stt()
        stt._model = None
        stt.unload_model()
        assert stt._model is None

    def test_unload_calls_cuda_empty_cache(self):
        from unittest.mock import patch
        stt = _make_stt(device="cuda")
        stt._model = "fake_model"
        with patch("core.stt_pipeline.gc") as mock_gc:
            with patch("torch.cuda.empty_cache") as mock_cache:
                stt.unload_model()
                mock_cache.assert_called_once()
                mock_gc.collect.assert_called_once()

    def test_unload_skips_cuda_cache_on_cpu(self):
        from unittest.mock import patch
        stt = _make_stt(device="cpu")
        stt._model = "fake_model"
        with patch("core.stt_pipeline.gc") as mock_gc:
            stt.unload_model()
            mock_gc.collect.assert_called_once()

    def test_unload_fires_status_callback(self):
        statuses = []
        stt = _make_stt(on_status=lambda s: statuses.append(s))
        stt._model = "fake_model"
        stt.unload_model()
        assert any("unloaded" in s.lower() for s in statuses)

    def test_stop_calls_unload(self):
        from unittest.mock import patch
        stt = _make_stt()
        stt._model = "fake_model"
        stt._running = True
        with patch.object(stt, "unload_model") as mock_unload:
            stt.stop()
            mock_unload.assert_called()


class TestIdleTimeout:
    def test_idle_timeout_default(self):
        stt = _make_stt()
        assert stt.idle_timeout == 0

    def test_idle_timeout_configurable(self):
        stt = _make_stt(idle_timeout=5)
        assert stt.idle_timeout == 5

    def test_idle_timeout_zero_means_never_unload(self):
        stt = _make_stt(idle_timeout=0)
        assert stt.idle_timeout == 0


class TestTranscribeTimeout:
    def test_timeout_factor_default(self):
        stt = _make_stt()
        assert stt.transcribe_timeout_factor == 0.0

    def test_timeout_factor_configurable(self):
        stt = _make_stt(transcribe_timeout_factor=3.0)
        assert stt.transcribe_timeout_factor == 3.0


class TestModelReload:
    def test_do_transcribe_skips_when_model_none(self):
        stt = _make_stt()
        stt._model = None
        stt._do_transcribe([np.zeros(16000, dtype=np.float32)], "system")
        assert stt._model is None

    def test_do_transcribe_reloads_on_slow(self):
        from unittest.mock import MagicMock, patch
        stt = _make_stt(transcribe_timeout_factor=2.0)
        mock_model = MagicMock()
        audio = np.ones(16000, dtype=np.float32)
        mock_segment = MagicMock()
        mock_segment.text = "hello world"
        mock_model.transcribe.return_value = (iter([mock_segment]), MagicMock(language="en"))
        stt._model = mock_model
        call_count = [0]
        def fake_monotonic():
            call_count[0] += 1
            if call_count[0] <= 1:
                return 0.0
            return 100.0
        with patch.object(stt, "unload_model") as mock_unload:
            with patch("core.stt_pipeline.time.monotonic", side_effect=fake_monotonic):
                stt._do_transcribe([audio], "system")
                mock_unload.assert_called()

    def test_do_transcribe_skips_reload_when_factor_zero(self):
        from unittest.mock import MagicMock, patch
        stt = _make_stt(transcribe_timeout_factor=0.0)
        mock_model = MagicMock()
        audio = np.ones(16000, dtype=np.float32)
        mock_segment = MagicMock()
        mock_segment.text = "hello world"
        mock_model.transcribe.return_value = (iter([mock_segment]), MagicMock(language="en"))
        stt._model = mock_model
        call_count = [0]

        def fake_monotonic():
            call_count[0] += 1
            if call_count[0] <= 1:
                return 0.0
            return 100.0

        with patch.object(stt, "unload_model") as mock_unload:
            with patch("core.stt_pipeline.time.monotonic", side_effect=fake_monotonic):
                stt._do_transcribe([audio], "system")
                mock_unload.assert_not_called()


class TestPauseResume:
    def test_pause_adds_reason(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input("manual")
        assert "manual" in stt._pause_reasons

    def test_pause_sets_paused_state(self):
        stt = _make_stt()
        stt._running = True
        assert stt.is_input_paused is False
        stt.pause_input("manual")
        assert stt.is_input_paused is True

    def test_pause_clears_queues(self):
        stt = _make_stt()
        stt._running = True
        stt._queue.put("item")
        stt._transcribe_queue.put("item")
        stt.pause_input("manual")
        assert stt._queue.empty()
        assert stt._transcribe_queue.empty()

    def test_pause_sets_buffer_reset_event(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input("manual")
        assert stt._buffer_reset_event.is_set()

    def test_resume_removes_reason(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input("manual")
        assert stt.is_input_paused is True
        stt.resume_input("manual")
        assert stt.is_input_paused is False

    def test_multiple_pause_reasons(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input("manual")
        stt.pause_input("tts")
        assert stt.is_input_paused is True
        assert "manual" in stt._pause_reasons
        assert "tts" in stt._pause_reasons

    def test_resume_one_reason_keeps_paused(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input("manual")
        stt.pause_input("tts")
        stt.resume_input("manual")
        assert stt.is_input_paused is True
        assert "tts" in stt._pause_reasons

    def test_pause_default_reason_is_manual(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input()
        assert "manual" in stt._pause_reasons

    def test_resume_default_reason_is_manual(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input("manual")
        stt.resume_input()
        assert stt.is_input_paused is False

    def test_is_listening_true_when_running_and_not_paused(self):
        stt = _make_stt()
        stt._running = True
        assert stt.is_listening is True

    def test_is_listening_false_when_paused(self):
        stt = _make_stt()
        stt._running = True
        stt.pause_input("manual")
        assert stt.is_listening is False

    def test_is_listening_false_when_not_running(self):
        stt = _make_stt()
        stt._running = False
        assert stt.is_listening is False
