package com.gek.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryRepositoryMoreTest {
    @TempDir Path tmp;

    @Test
    void persistsAcrossReopenAndOrdersRecentByNewestFirst() throws Exception {
        Path db = tmp.resolve("h.db");
        long id1, id2, id3;
        try (HistoryRepository repo = new HistoryRepository(db)) {
            id1 = repo.save(TranslationRecord.ofNew("one", "en", "1", "ar", "a", null, "mic"));
            id2 = repo.save(TranslationRecord.ofNew("two", "en", "2", "ar", "b", null, "mic"));
            id3 = repo.save(TranslationRecord.ofNew("three", "en", "3", "ar", "c", null, "loopback"));
        }
        try (HistoryRepository repo = new HistoryRepository(db)) {
            assertEquals(3, repo.count());
            List<TranslationRecord> recent = repo.recent(10);
            assertEquals(3, recent.size());
            assertEquals(id3, recent.get(0).id());
            assertEquals(id2, recent.get(1).id());
            assertEquals(id1, recent.get(2).id());
            assertEquals("three", recent.get(0).originalText());
            assertEquals("loopback", recent.get(0).audioSource());
        }
    }

    @Test
    void recentLimitAndSearchEdgeCases() throws Exception {
        try (HistoryRepository repo = new HistoryRepository(tmp.resolve("h.db"))) {
            repo.save(TranslationRecord.ofNew("hello world", "en", "hola mundo", "es", "live", null, "mic"));
            repo.save(TranslationRecord.ofNew("goodbye", "en", "adios", "es", "dictation", null, "mic"));
            assertEquals(1, repo.recent(1).size());
            assertEquals(0, repo.recent(0).size());
            assertTrue(repo.search(null, 10).isEmpty());
            assertTrue(repo.search("   ", 10).isEmpty());
            assertEquals(1, repo.search("hola", 10).size(), "search should match translated text via FTS when available");
            assertTrue(repo.search("zzznomatchtoken", 10).isEmpty());
        }
    }

    @Test
    void deleteIsIsolatedAndClearEmpties() throws Exception {
        try (HistoryRepository repo = new HistoryRepository(tmp.resolve("h.db"))) {
            long a = repo.save(TranslationRecord.ofNew("a", "en", "a1", "ar", "s", null, "mic"));
            long b = repo.save(TranslationRecord.ofNew("b", "en", "b1", "ar", "s", null, "mic"));
            repo.delete(a);
            assertEquals(1, repo.count());
            List<TranslationRecord> left = repo.recent(10);
            assertEquals(1, left.size());
            assertEquals(b, left.get(0).id());
            repo.clear();
            assertEquals(0, repo.count());
            assertTrue(repo.recent(10).isEmpty());
        }
    }

    @Test
    void saveReturnsGeneratedId() throws Exception {
        try (HistoryRepository repo = new HistoryRepository(tmp.resolve("h.db"))) {
            long id = repo.save(TranslationRecord.ofNew("x", "en", "y", "ar", "s", "/p", "mic"));
            assertTrue(id > 0);
            assertEquals(1, repo.recent(10).get(0).id() == id ? 1 : 0);
        }
    }
}
