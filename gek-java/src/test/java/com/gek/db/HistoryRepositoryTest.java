package com.gek.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HistoryRepositoryTest {
    @TempDir Path tmp;

    @Test
    void saveSearchCountDeleteClear() throws Exception {
        try (HistoryRepository repo = new HistoryRepository(tmp.resolve("h.db"))) {
            assertEquals(0, repo.count());
            long id = repo.save(TranslationRecord.ofNew("hello world", "en", "مرحبا", "ar", "dictation", null, "mic"));
            assertTrue(id > 0);
            assertEquals(1, repo.count());
            assertEquals(1, repo.recent(10).size());
            assertEquals(1, repo.search("hello", 10).size());
            repo.delete(id);
            assertEquals(0, repo.count());
            repo.save(TranslationRecord.ofNew("a","en","b","ar","live",null,"mic"));
            repo.save(TranslationRecord.ofNew("c","en","d","ar","live",null,"mic"));
            assertEquals(2, repo.count());
            repo.clear();
            assertEquals(0, repo.count());
        }
    }
}
