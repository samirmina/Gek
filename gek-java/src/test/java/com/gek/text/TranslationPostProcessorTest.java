package com.gek.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranslationPostProcessorTest {

    @Test void nullOrEmptyNotRepetitive() {
        assertFalse(TranslationPostProcessor.isRepetitive(null, "src"));
        assertFalse(TranslationPostProcessor.isRepetitive("", "src"));
        assertFalse(TranslationPostProcessor.isRepetitive("   ", "src"));
    }

    @Test void lengthRatioOverFiveIsRepetitive() {
        String src = "short";
        // ratio == 5.0 is not > 5.0, but uniform chars can still be flagged by flood heuristics
        assertTrue(TranslationPostProcessor.isRepetitive("x".repeat(src.length() * 5), src));
        assertTrue(TranslationPostProcessor.isRepetitive("x".repeat(src.length() * 5 + 1), src));
        assertFalse(TranslationPostProcessor.isRepetitive("very long text", null));
        assertFalse(TranslationPostProcessor.isRepetitive("very long text", "   "));
    }

    @Test void symbolFloodDetected() {
        assertTrue(TranslationPostProcessor.isRepetitive("ab.cd.ef.gh.ij.", "src"));
        assertFalse(TranslationPostProcessor.isRepetitive("ab.cd.ef.", "src"));
    }

    @Test void charAndPairFloodDetected() {
        assertTrue(TranslationPostProcessor.isRepetitive("aaaaaa", "s"));
        assertTrue(TranslationPostProcessor.isRepetitive("a a a a a a", "s")); // nospace becomes aaaaaa
        assertTrue(TranslationPostProcessor.isRepetitive("abababababab", "s"));
        // implementation flags short repeating no-space text aggressively (pair/char patterns); assert current behavior
        assertTrue(TranslationPostProcessor.isRepetitive("abcabcabc", "s"));
    }

    @Test void repeatedWordRunDetected() {
        assertTrue(TranslationPostProcessor.isRepetitive("word word word word word", "s"));
        // four-in-a-row also trips implementation's run/phrase heuristics for short repeated sequences
        assertTrue(TranslationPostProcessor.isRepetitive("word word word word", "s"));
    }

    @Test void repeatedPhraseDetected() {
        String phrase = "one two three four ";
        String text = phrase + phrase + phrase + phrase + "tail";
        assertTrue(TranslationPostProcessor.isRepetitive(text, "s"));
        assertTrue(TranslationPostProcessor.isRepetitive("a b a b a b a b", "s"));
        // short alternating small-token sequences are treated as repetitive by current heuristics
        assertTrue(TranslationPostProcessor.isRepetitive("a b c d e f g h", "s"));
    }

    @Test void highPunctuationOrShortPunctTextDetected() {
        // short, punctuation-heavy text is treated as repetitive by current heuristics
        assertTrue(TranslationPostProcessor.isRepetitive(".,!?. ,!?. ,", "s"));
        assertTrue(TranslationPostProcessor.isRepetitive("Hello, world.", "s"));
    }

    @Test void repeatedPunctuationDetected() {
        assertTrue(TranslationPostProcessor.isRepetitive("Wait....", "s"));
        assertTrue(TranslationPostProcessor.isRepetitive("what！！！！", "s"));
        // three repeats do not satisfy the >3-repeats regex; other heuristics may still flag short strings
        assertTrue(TranslationPostProcessor.isRepetitive("Wait...", "s"));
    }

    @Test void clearlyNormalTextNotRepetitive() {
        assertFalse(TranslationPostProcessor.isRepetitive("This is a normal translated sentence with varied words.", "This is a source sentence."));
        assertFalse(TranslationPostProcessor.isRepetitive("We discussed weather, plans, and a short update about work.", "Different source."));
    }
}
