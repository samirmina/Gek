package com.gek.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SynonymReplacerTest {
    @TempDir Path tmp;

    @Test void loadMissingOrNullDirReturnsEmpty() {
        SynonymReplacer r = SynonymReplacer.load(tmp.resolve("missing"));
        assertTrue(r.counts().isEmpty());
        assertEquals("hello", r.apply("hello", "en"));
        assertNotNull(SynonymReplacer.load(null));
    }

    @Test void loadsNestedSynonymsObjectAndAppliesWordBoundaries() throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode root = m.createObjectNode();
        root.put("locale", "en");
        ObjectNode syn = root.putObject("synonyms");
        syn.put("foo", "bar");
        syn.put("tv", "television");
        Files.writeString(tmp.resolve("en.json"), m.writeValueAsString(root));

        SynonymReplacer r = SynonymReplacer.load(tmp);
        assertEquals(Map.of("en", 2), r.counts());
        assertEquals("bar baz", r.apply("foo baz", "en"));
        assertEquals("food", r.apply("food", "en"), "word boundary must prevent partial replace");
        assertEquals("my television", r.apply("my tv", "en"));
        assertEquals("foo", r.apply("foo", "es"), "unknown lang unchanged");
        assertEquals("foo", r.apply("foo", null), "null lang unchanged");
        assertNull(r.apply(null, "en"));
        assertEquals("", r.apply("", "en"));
    }

    @Test void toleratesFlatMapAndSkipsBadFiles() throws Exception {
        Files.writeString(tmp.resolve("de.json"), "{\"hallo\":\"servus\",\"welt\":\"erde\"}");
        Files.writeString(tmp.resolve("bad.json"), "not json {{{");
        // file with empty key case: ensure loader skips empty key but keeps valid entries
        Files.writeString(tmp.resolve("fr.json"), "{\"\":\"x\",\"bon\":\"bien\"}");
        // file with non-textual value ignored
        Files.writeString(tmp.resolve("it.json"), "{\"ciao\":123,\"ok\":\"si\"}");

        SynonymReplacer r = SynonymReplacer.load(tmp);
        assertEquals("servus erde", r.apply("hallo welt", "de"));
        assertEquals("bien", r.apply("bon", "fr"));
        // loader stores textual entries including empty-key; apply skips empty keys
        assertEquals(2, r.counts().get("fr"));
        assertEquals("si", r.apply("ok", "it"));
        assertEquals(1, r.counts().get("it"));
        assertFalse(r.counts().containsKey("bad"));
    }

    @Test void multipleReplacementsApplied() throws Exception {
        Files.writeString(tmp.resolve("en.json"), "{\"a\":\"b\",\"b\":\"c\"}");
        SynonymReplacer r = SynonymReplacer.load(tmp);
        String out = r.apply("a b", "en");
        assertNotNull(out);
        assertTrue(out.contains("c"), "later entries may replace earlier results: " + out);
    }
}
