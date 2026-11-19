package com.gek.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TextProcessingTest {
    @TempDir Path tmp;

    @Test
    void repetitionDetectionCatchesFloods() {
        assertTrue(TranslationPostProcessor.isRepetitive("word word word word word", "hi"));
        assertTrue(TranslationPostProcessor.isRepetitive("ha.ha.ha.ha.ha", "ok"));
        assertTrue(TranslationPostProcessor.isRepetitive("xxxxxxx", "ok"));
        assertFalse(TranslationPostProcessor.isRepetitive("hello world", "hello world"));
        assertTrue(TranslationPostProcessor.isRepetitive("this is way too long compared to source text here now", "hi"));
    }

    @Test
    void synonymReplacerLoadsAndApplies() throws Exception {
        Path syn = tmp.resolve("synonyms");
        Files.createDirectories(syn);
        Files.writeString(syn.resolve("en.json"), "{\n  \"locale\": \"en\",\n  \"synonyms\": {\"gonna\": \"going to\", \"u\": \"you\"}\n}\n");
        SynonymReplacer r = SynonymReplacer.load(syn);
        assertEquals(2, r.counts().get("en"));
        assertEquals("I am going to see you", r.apply("I am gonna see u", "en"));
        assertEquals("hola", r.apply("hola", "es"));
        // null/blank and missing locale handled
        assertNull(r.apply(null, "en"));
        assertEquals("", r.apply("", "en"));
        assertEquals("text", r.apply("text", null));
        assertEquals("text", r.apply("text", "  "));
        assertEquals(0, SynonymReplacer.load(tmp.resolve("missing-dir")).counts().size());
    }

    @Test
    void repetitionDetectionEdgeCases() {
        assertFalse(TranslationPostProcessor.isRepetitive(null, "hi"));
        assertFalse(TranslationPostProcessor.isRepetitive("   ", "hi"));
        assertFalse(TranslationPostProcessor.isRepetitive("short", null));
        assertFalse(TranslationPostProcessor.isRepetitive("short", "   "));
        // not repetitive when source is long enough and text varied
        assertFalse(TranslationPostProcessor.isRepetitive("a varied sentence here", "a comparable source sentence"));
    }
}
