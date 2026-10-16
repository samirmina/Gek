"""Performance benchmarks for critical code paths.

These tests measure throughput and latency to catch regressions.
"""
import sys
import os
import tempfile
import sqlite3
import time

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


class TestHallucinationCheckPerformance:
    """Benchmark _is_hallucination() throughput."""

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

    def test_hallucination_check_10k_texts_under_1s(self):
        stt = self._make_stt()
        texts = [
            "Hello, how are you doing today?",
            "The weather is nice outside",
            "ab ab ab ab ab",
            "Short",
            "This is a longer sentence that should definitely pass the filter",
            "ha ha ha ha ha ha",
            "a",
            "OK",
            "Good morning everyone",
            "xyz xyz xyz xyz xyz xyz",
        ] * 1000

        start = time.perf_counter()
        for text in texts:
            stt._is_hallucination(text)
        elapsed = time.perf_counter() - start

        assert elapsed < 1.0, f"Hallucination check took {elapsed:.3f}s for 10k texts"

    def test_hallucination_check_performance_summary(self, capsys):
        stt = self._make_stt()
        texts = [
            "Hello, how are you doing today?",
            "ab ab ab ab",
            "a",
            "The quick brown fox jumps over the lazy dog",
            "ha ha ha ha ha",
        ] * 2000

        start = time.perf_counter()
        for text in texts:
            stt._is_hallucination(text)
        elapsed = time.perf_counter() - start

        throughput = len(texts) / elapsed
        with capsys.disabled():
            print(f"\n  Hallucination check: {len(texts)} texts in {elapsed:.4f}s ({throughput:.0f} texts/sec)")


class TestDatabasePerformance:
    """Benchmark database insert and search throughput."""

    def _make_db(self, tmp_path):
        from db import Database
        db_path = str(tmp_path / "perf_test.db")
        db = Database.__new__(Database)
        import threading
        db._lock = threading.Lock()
        db._conn = sqlite3.connect(db_path, check_same_thread=False)
        db._conn.row_factory = sqlite3.Row
        db._init_tables()
        return db

    def test_db_insert_100_under_2s(self, tmp_path):
        db = self._make_db(tmp_path)
        start = time.perf_counter()
        for i in range(100):
            db.save(f"Original text number {i}", "en", f"Translated text number {i}", "de")
        elapsed = time.perf_counter() - start
        db.close()
        assert elapsed < 2.0, f"DB insert took {elapsed:.3f}s for 100 inserts"

    def test_db_search_50_under_2s(self, tmp_path):
        db = self._make_db(tmp_path)
        for i in range(50):
            db.save(f"Hello world number {i}", "en", f"Hallo Welt Nummer {i}", "de")
        start = time.perf_counter()
        for i in range(50):
            db.search("hello")
        elapsed = time.perf_counter() - start
        db.close()
        assert elapsed < 2.0, f"DB search took {elapsed:.3f}s for 50 searches"

    def test_db_performance_summary(self, tmp_path, capsys):
        db = self._make_db(tmp_path)

        start = time.perf_counter()
        for i in range(200):
            db.save(f"Performance test text {i}", "en", f"Performance translated {i}", "de")
        insert_elapsed = time.perf_counter() - start

        start = time.perf_counter()
        for i in range(100):
            db.search("performance")
        search_elapsed = time.perf_counter() - start

        db.close()
        with capsys.disabled():
            print(f"\n  DB insert: 200 rows in {insert_elapsed:.4f}s ({200/insert_elapsed:.0f} rows/sec)")
            print(f"  DB search: 100 queries in {search_elapsed:.4f}s ({100/search_elapsed:.0f} queries/sec)")


class TestTranslatorPerformance:
    """Benchmark translator synonym lookup and translation."""

    def test_synonym_lookup_performance(self, capsys):
        from core.translator import Translator
        t = Translator()
        texts = [
            "Hello world, this is a test",
            "Good morning everyone",
            "The quick brown fox",
            "Machine translation is amazing",
            "Voice recognition works well",
        ] * 2000

        start = time.perf_counter()
        for text in texts:
            t._apply_synonyms(text, "en")
        elapsed = time.perf_counter() - start

        with capsys.disabled():
            print(f"\n  Synonym lookup: {len(texts)} texts in {elapsed:.4f}s ({len(texts)/elapsed:.0f} texts/sec)")

    def test_translate_same_lang_instant(self):
        from core.translator import Translator
        t = Translator()
        start = time.perf_counter()
        for _ in range(1000):
            t.translate("Hello", "en", "en")
        elapsed = time.perf_counter() - start
        assert elapsed < 1.0, f"Same-lang translate took {elapsed:.3f}s for 1000 calls"


class TestHeightCalculationPerformance:
    """Benchmark HUD height calculation (called on every panel change)."""

    def _make_hud_helper(self):
        """Create minimal object with _calc_height logic."""
        from ui.hud import BASE_HEIGHT, TEXT_PANEL_HEIGHT, COLOR_FILTER_BAR_HEIGHT
        class H:
            _text_panel_visible = True
        h = H()
        h._calc_height = lambda: (
            BASE_HEIGHT + COLOR_FILTER_BAR_HEIGHT
            - (TEXT_PANEL_HEIGHT if not h._text_panel_visible else 0)
        )
        return h

    def test_calc_height_10k_calls_under_1s(self):
        h = self._make_hud_helper()
        start = time.perf_counter()
        for _ in range(10000):
            h._calc_height()
        elapsed = time.perf_counter() - start
        assert elapsed < 1.0, f"_calc_height took {elapsed:.3f}s for 10k calls"
