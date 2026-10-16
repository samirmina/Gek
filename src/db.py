import logging
import sqlite3
import threading
from datetime import datetime
from typing import Optional

from config import DB_PATH

logger = logging.getLogger(__name__)


class Database:
    def __init__(self):
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(str(DB_PATH), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._init_tables()

    def _init_tables(self):
        with self._lock:
            self._conn.execute("""
                CREATE TABLE IF NOT EXISTS translations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    original_text TEXT NOT NULL,
                    original_lang TEXT NOT NULL,
                    translated_text TEXT NOT NULL,
                    translated_lang TEXT NOT NULL,
                    source_name TEXT,
                    source_path TEXT,
                    audio_source TEXT,
                    timestamp TEXT NOT NULL
                )
            """)
            self._conn.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS translations_fts USING fts5(
                    original_text, translated_text, source_name,
                    content='translations', content_rowid='id'
                )
            """)
            self._conn.execute("""
                CREATE TRIGGER IF NOT EXISTS translations_ai AFTER INSERT ON translations BEGIN
                    INSERT INTO translations_fts(rowid, original_text, translated_text, source_name)
                    VALUES (new.id, new.original_text, new.translated_text, new.source_name);
                END
            """)
            self._conn.execute("""
                CREATE TRIGGER IF NOT EXISTS translations_ad AFTER DELETE ON translations BEGIN
                    INSERT INTO translations_fts(translations_fts, rowid, original_text, translated_text, source_name)
                    VALUES ('delete', old.id, old.original_text, old.translated_text, old.source_name);
                END
            """)
            self._conn.execute("""
                CREATE TRIGGER IF NOT EXISTS translations_au AFTER UPDATE ON translations BEGIN
                    INSERT INTO translations_fts(translations_fts, rowid, original_text, translated_text, source_name)
                    VALUES ('delete', old.id, old.original_text, old.translated_text, old.source_name);
                    INSERT INTO translations_fts(rowid, original_text, translated_text, source_name)
                    VALUES (new.id, new.original_text, new.translated_text, new.source_name);
                END
            """)
            self._conn.commit()

    def save(self, original_text: str, original_lang: str, translated_text: str,
             translated_lang: str, source_name: Optional[str] = None,
             source_path: Optional[str] = None, audio_source: Optional[str] = None) -> int:
        logger.debug(f"Database.save: {original_lang}->{translated_lang} text={original_text[:60]!r}")
        with self._lock:
            cursor = self._conn.execute(
                """INSERT INTO translations
                   (original_text, original_lang, translated_text, translated_lang,
                    source_name, source_path, audio_source, timestamp)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (original_text, original_lang, translated_text, translated_lang,
                 source_name, source_path, audio_source, datetime.now().isoformat())
            )
            self._conn.commit()
            return cursor.lastrowid

    def search(self, query: str, limit: int = 50) -> list:
        logger.debug(f"Database.search: query={query!r} limit={limit}")
        sanitized = query.replace('"', '""')
        if not sanitized.strip():
            return []
        fts_query = f'"{sanitized}"'
        with self._lock:
            try:
                rows = self._conn.execute(
                    """SELECT t.* FROM translations t
                       JOIN translations_fts fts ON t.id = fts.rowid
                       WHERE translations_fts MATCH ?
                       ORDER BY rank LIMIT ?""",
                    (fts_query, limit)
                ).fetchall()
            except Exception as e:
                logger.warning("FTS search failed, returning empty: %s", e)
                rows = []
        return [dict(r) for r in rows]

    def get_recent(self, limit: int = 50) -> list:
        logger.debug(f"Database.get_recent: limit={limit}")
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM translations ORDER BY id DESC LIMIT ?",
                (limit,)
            ).fetchall()
        return [dict(r) for r in rows]

    def delete(self, entry_id: int) -> bool:
        logger.debug(f"Database.delete: id={entry_id}")
        with self._lock:
            cursor = self._conn.execute(
                "DELETE FROM translations WHERE id = ?", (entry_id,)
            )
            self._conn.commit()
            return cursor.rowcount > 0

    def close(self):
        logger.debug("Database.close: closing connection")
        with self._lock:
            self._conn.close()
