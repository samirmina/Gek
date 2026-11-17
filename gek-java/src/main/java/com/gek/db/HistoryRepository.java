package com.gek.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class HistoryRepository implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HistoryRepository.class);
    private final Connection conn;

    public HistoryRepository(Path dbPath) throws SQLException {
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
        }
        initTables();
    }

    private void initTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
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
            """);
            st.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS translations_fts USING fts5(
                    original_text, translated_text, source_name,
                    content='translations', content_rowid='id'
                )
            """);
            st.execute("""
                CREATE TRIGGER IF NOT EXISTS translations_ai AFTER INSERT ON translations BEGIN
                    INSERT INTO translations_fts(rowid, original_text, translated_text, source_name)
                    VALUES (new.id, new.original_text, new.translated_text, new.source_name);
                END
            """);
            st.execute("""
                CREATE TRIGGER IF NOT EXISTS translations_ad AFTER DELETE ON translations BEGIN
                    INSERT INTO translations_fts(translations_fts, rowid, original_text, translated_text, source_name)
                    VALUES ('delete', old.id, old.original_text, old.translated_text, old.source_name);
                END
            """);
            st.execute("""
                CREATE TRIGGER IF NOT EXISTS translations_au AFTER UPDATE ON translations BEGIN
                    INSERT INTO translations_fts(translations_fts, rowid, original_text, translated_text, source_name)
                    VALUES ('delete', old.id, old.original_text, old.translated_text, old.source_name);
                    INSERT INTO translations_fts(rowid, original_text, translated_text, source_name)
                    VALUES (new.id, new.original_text, new.translated_text, new.source_name);
                END
            """);
        }
    }

    public synchronized long save(TranslationRecord r) throws SQLException {
        String sql = """
            INSERT INTO translations
              (original_text, original_lang, translated_text, translated_lang,
               source_name, source_path, audio_source, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.originalText());
            ps.setString(2, r.originalLang());
            ps.setString(3, r.translatedText());
            ps.setString(4, r.translatedLang());
            ps.setString(5, r.sourceName());
            ps.setString(6, r.sourcePath());
            ps.setString(7, r.audioSource());
            ps.setString(8, r.timestamp());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public synchronized List<TranslationRecord> recent(int limit) throws SQLException {
        String sql = "SELECT * FROM translations ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return map(rs);
            }
        }
    }

    public synchronized List<TranslationRecord> search(String query, int limit) throws SQLException {
        if (query == null || query.trim().isEmpty()) return List.of();
        String sanitized = "\"" + query.replace("\"", "\"\"") + "\"";
        String sql = """
            SELECT t.* FROM translations t
            JOIN translations_fts fts ON t.id = fts.rowid
            WHERE translations_fts MATCH ?
            ORDER BY rank LIMIT ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sanitized);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return map(rs);
            }
        } catch (SQLException e) {
            log.warn("FTS search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    public synchronized int count() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM translations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM translations WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized void clear() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM translations");
        }
    }

    private List<TranslationRecord> map(ResultSet rs) throws SQLException {
        List<TranslationRecord> out = new ArrayList<>();
        while (rs.next()) {
            out.add(new TranslationRecord(
                    rs.getLong("id"),
                    rs.getString("original_text"),
                    rs.getString("original_lang"),
                    rs.getString("translated_text"),
                    rs.getString("translated_lang"),
                    rs.getString("source_name"),
                    rs.getString("source_path"),
                    rs.getString("audio_source"),
                    rs.getString("timestamp")
            ));
        }
        return out;
    }

    @Override public synchronized void close() throws SQLException { conn.close(); }
}
