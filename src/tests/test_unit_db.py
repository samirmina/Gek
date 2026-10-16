"""Unit tests for Database module."""
import sys
import os
import sqlite3
import threading

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


def _make_db(tmp_path):
    from db import Database
    db_path = str(tmp_path / "unit_test.db")
    db = Database.__new__(Database)
    db._lock = threading.Lock()
    db._conn = sqlite3.connect(db_path, check_same_thread=False)
    db._conn.row_factory = sqlite3.Row
    db._init_tables()
    return db


class TestDatabaseInit:
    def test_tables_created(self, tmp_path):
        db = _make_db(tmp_path)
        tables = db._conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        ).fetchall()
        table_names = [t[0] for t in tables]
        assert "translations" in table_names
        assert "translations_fts" in table_names
        db.close()

    def test_triggers_created(self, tmp_path):
        db = _make_db(tmp_path)
        triggers = db._conn.execute(
            "SELECT name FROM sqlite_master WHERE type='trigger'"
        ).fetchall()
        trigger_names = [t[0] for t in triggers]
        assert "translations_ai" in trigger_names
        assert "translations_ad" in trigger_names
        assert "translations_au" in trigger_names
        db.close()


class TestDatabaseSave:
    def test_save_returns_id(self, tmp_path):
        db = _make_db(tmp_path)
        row_id = db.save("Hello", "en", "Hallo", "de")
        assert isinstance(row_id, int)
        assert row_id > 0
        db.close()

    def test_save_multiple_returns_sequential_ids(self, tmp_path):
        db = _make_db(tmp_path)
        id1 = db.save("First", "en", "Erste", "de")
        id2 = db.save("Second", "en", "Zweite", "de")
        assert id2 > id1
        db.close()

    def test_save_with_optional_fields(self, tmp_path):
        db = _make_db(tmp_path)
        row_id = db.save(
            "Test", "en", "Teste", "de",
            source_name="chrome.exe",
            source_path="C:\\chrome.exe",
            audio_source="system",
        )
        entry = db.get_recent(limit=1)[0]
        assert entry["source_name"] == "chrome.exe"
        assert entry["source_path"] == "C:\\chrome.exe"
        assert entry["audio_source"] == "system"
        db.close()

    def test_save_without_optional_fields(self, tmp_path):
        db = _make_db(tmp_path)
        row_id = db.save("Test", "en", "Teste", "de")
        entry = db.get_recent(limit=1)[0]
        assert entry["source_name"] is None
        assert entry["source_path"] is None
        assert entry["audio_source"] is None
        db.close()

    def test_save_has_timestamp(self, tmp_path):
        db = _make_db(tmp_path)
        db.save("Test", "en", "Teste", "de")
        entry = db.get_recent(limit=1)[0]
        assert entry["timestamp"] is not None
        assert len(entry["timestamp"]) > 0
        db.close()


class TestDatabaseSearch:
    def test_search_basic(self, tmp_path):
        db = _make_db(tmp_path)
        db.save("Hello world", "en", "Hallo Welt", "de")
        results = db.search("hello")
        assert len(results) == 1
        db.close()

    def test_search_case_insensitive_fts(self, tmp_path):
        db = _make_db(tmp_path)
        db.save("Hello World", "en", "Hallo Welt", "de")
        results = db.search("HELLO")
        assert len(results) == 1
        db.close()

    def test_search_limit(self, tmp_path):
        db = _make_db(tmp_path)
        for i in range(20):
            db.save(f"Common word text {i}", "en", f"Text {i}", "de")
        results = db.search("common", limit=5)
        assert len(results) <= 5
        db.close()

    def test_search_no_match(self, tmp_path):
        db = _make_db(tmp_path)
        db.save("Hello", "en", "Hallo", "de")
        results = db.search("zzznonexistent")
        assert len(results) == 0
        db.close()


class TestDatabaseGetRecent:
    def test_get_recent_default_limit(self, tmp_path):
        db = _make_db(tmp_path)
        for i in range(100):
            db.save(f"Text {i}", "en", f"Text {i}", "de")
        entries = db.get_recent()
        assert len(entries) == 50  # default limit
        db.close()

    def test_get_recent_custom_limit(self, tmp_path):
        db = _make_db(tmp_path)
        for i in range(10):
            db.save(f"Text {i}", "en", f"Text {i}", "de")
        entries = db.get_recent(limit=3)
        assert len(entries) == 3
        db.close()

    def test_get_recent_empty(self, tmp_path):
        db = _make_db(tmp_path)
        entries = db.get_recent()
        assert entries == []
        db.close()

    def test_get_recent_returns_dicts(self, tmp_path):
        db = _make_db(tmp_path)
        db.save("Test", "en", "Teste", "de")
        entries = db.get_recent()
        assert isinstance(entries[0], dict)
        db.close()


class TestDatabaseDelete:
    def test_delete_existing(self, tmp_path):
        db = _make_db(tmp_path)
        row_id = db.save("Test", "en", "Teste", "de")
        assert db.delete(row_id) is True
        assert len(db.get_recent()) == 0
        db.close()

    def test_delete_nonexistent(self, tmp_path):
        db = _make_db(tmp_path)
        assert db.delete(999999) is False
        db.close()

    def test_delete_removes_fts_entry(self, tmp_path):
        db = _make_db(tmp_path)
        row_id = db.save("Unique searchable text", "en", "Unique translation", "de")
        assert len(db.search("Unique")) == 1
        db.delete(row_id)
        assert len(db.search("Unique")) == 0
        db.close()


class TestDatabaseThreadSafety:
    def test_concurrent_saves(self, tmp_path):
        db = _make_db(tmp_path)
        errors = []

        def save_worker(i):
            try:
                db.save(f"Thread {i} text", "en", f"Translated {i}", "de")
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=save_worker, args=(i,)) for i in range(20)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        entries = db.get_recent(limit=100)
        assert len(entries) == 20
        db.close()
