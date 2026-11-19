package com.gek.db;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TranslationRecordTest {

    @Test
    void ofNewPopulatesFieldsAndTimestamp() {
        Instant before = Instant.now().minusSeconds(1);
        TranslationRecord r = TranslationRecord.ofNew("orig", "en", "trad", "ar", "dictation", "/p", "mic");
        Instant after = Instant.now().plusSeconds(1);
        assertEquals(0, r.id());
        assertEquals("orig", r.originalText());
        assertEquals("en", r.originalLang());
        assertEquals("trad", r.translatedText());
        assertEquals("ar", r.translatedLang());
        assertEquals("dictation", r.sourceName());
        assertEquals("/p", r.sourcePath());
        assertEquals("mic", r.audioSource());
        assertNotNull(r.timestamp());
        Instant ts = Instant.parse(r.timestamp());
        assertFalse(ts.isBefore(before));
        assertFalse(ts.isAfter(after));
    }

    @Test
    void recordEqualityAndAccessors() {
        TranslationRecord a = new TranslationRecord(1, "o", "en", "t", "ar", "s", "p", "mic", "2020-01-01T00:00:00Z");
        TranslationRecord b = new TranslationRecord(1, "o", "en", "t", "ar", "s", "p", "mic", "2020-01-01T00:00:00Z");
        TranslationRecord c = new TranslationRecord(2, "o", "en", "t", "ar", "s", "p", "mic", "2020-01-01T00:00:00Z");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertTrue(a.toString().contains("TranslationRecord"));
    }
}
