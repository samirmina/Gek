package com.gek.app;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliRunnerHelpersTest {

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = CliRunner.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void hasAndArgVal() throws Exception {
        String[] args = {"--input", "a.wav", "--translate", "--lang", "en"};
        assertTrue((boolean) invoke("has", new Class[]{String[].class, String.class}, args, "--translate"));
        assertTrue((boolean) invoke("has", new Class[]{String[].class, String.class}, args, "--TRANSLATE"));
        assertFalse((boolean) invoke("has", new Class[]{String[].class, String.class}, args, "--missing"));
        // current implementation does not guard null args array
        Exception ex = assertThrows(Exception.class, () -> invoke("has", new Class[]{String[].class, String.class}, null, "--x"));
        Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : ex;
        assertTrue(cause instanceof NullPointerException);

        assertEquals("a.wav", invoke("argVal", new Class[]{String[].class, String.class, String.class}, args, "--input", "d"));
        assertEquals("en", invoke("argVal", new Class[]{String[].class, String.class, String.class}, args, "--LANG", "d"));
        assertEquals("d", invoke("argVal", new Class[]{String[].class, String.class, String.class}, args, "--nope", "d"));
        assertEquals("d", invoke("argVal", new Class[]{String[].class, String.class, String.class}, new String[]{"--x"}, "--x", "d"));
    }

    @Test
    void normAndOneLine() throws Exception {
        assertNull(invoke("norm", new Class[]{String.class}, (Object) null));
        assertNull(invoke("norm", new Class[]{String.class}, "  "));
        assertNull(invoke("norm", new Class[]{String.class}, "auto"));
        assertNull(invoke("norm", new Class[]{String.class}, "Detected"));
        assertEquals("en", invoke("norm", new Class[]{String.class}, " en "));
        assertEquals("es", invoke("norm", new Class[]{String.class}, "es"));

        assertEquals("", invoke("oneLine", new Class[]{String.class}, (Object) null));
        // replaces '\n' then '\r' with spaces (no collapsing)
        assertEquals("a b  c", invoke("oneLine", new Class[]{String.class}, "a\nb\r c"));
        String longS = "x".repeat(130);
        String out = (String) invoke("oneLine", new Class[]{String.class}, longS);
        assertEquals(120, out.length());
        assertTrue(out.endsWith("..."));
    }

    @Test
    void sanitizeTranslation() throws Exception {
        assertNull(invoke("sanitizeTranslation", new Class[]{String.class}, (Object) null));
        assertEquals("", invoke("sanitizeTranslation", new Class[]{String.class}, "   "));
        assertEquals("hello world", invoke("sanitizeTranslation", new Class[]{String.class}, "  hello\u2581  world  "));
        assertEquals("a b", invoke("sanitizeTranslation", new Class[]{String.class}, "a\u00A0\u2047b"));
        assertEquals("line1\nline2", invoke("sanitizeTranslation", new Class[]{String.class}, " line1  \n  line2 "));
        // preserves internal empty lines but trims per-line and outer whitespace
        assertEquals("a\n\nb", invoke("sanitizeTranslation", new Class[]{String.class}, " a \n \n b "));
        assertEquals("plain", invoke("sanitizeTranslation", new Class[]{String.class}, "plain"));
    }

    @Test
    void requireSidecarThrowsWhenUnavailable() throws Exception {
        Method m = CliRunner.class.getDeclaredMethod("requireSidecar", com.gek.inference.SidecarClient.class, String.class);
        m.setAccessible(true);
        Exception ex = assertThrows(Exception.class, () -> m.invoke(null, null, "--transcribe-file"));
        Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : ex;
        assertTrue(cause instanceof com.gek.inference.InferenceUnavailableException);
        assertTrue(cause.getMessage().contains("--transcribe-file"));
        assertTrue(cause.getMessage().contains("No usable native runtime"));
    }

    @Test
    void speakerForIndex() throws Exception {
        Class<?> segCl = Class.forName("com.gek.inference.DiarizationEngine$Segment");
        java.lang.reflect.Constructor<?> c = segCl.getDeclaredConstructor(double.class, double.class, String.class);
        Object s0 = c.newInstance(0.0, 2.0, "SPEAKER_00");
        Object s1 = c.newInstance(2.0, 4.0, "SPEAKER_01");
        Object sNull = c.newInstance(4.0, 5.0, null);
        Method m = CliRunner.class.getDeclaredMethod("speakerForIndex", List.class, int.class, int.class, double.class);
        m.setAccessible(true);

        assertEquals("SPEAKER_00", m.invoke(null, null, 0, 4, 4.0));
        assertEquals("SPEAKER_00", m.invoke(null, List.of(), 0, 4, 4.0));
        assertEquals("SPEAKER_00", m.invoke(null, List.of(s0), 0, 4, 4.0));
        assertEquals("SPEAKER_00", m.invoke(null, List.of(sNull), 0, 4, 4.0));

        List<Object> segs = List.of(s0, s1);
        assertEquals("SPEAKER_00", m.invoke(null, segs, 0, 4, 4.0)); // mid 0.5
        assertEquals("SPEAKER_00", m.invoke(null, segs, 1, 4, 4.0)); // mid 1.5
        assertEquals("SPEAKER_01", m.invoke(null, segs, 2, 4, 4.0)); // mid 2.5
        assertEquals("SPEAKER_01", m.invoke(null, segs, 3, 4, 4.0)); // mid 3.5

        // audioDur 0 => mid 0, pick containing/nearest
        assertEquals("SPEAKER_00", m.invoke(null, segs, 0, 4, 0.0));
        // nearest fallback (gap)
        Object g0 = c.newInstance(0.0, 1.0, "A");
        Object g1 = c.newInstance(3.0, 4.0, "B");
        assertEquals("A", m.invoke(null, List.of(g0, g1), 0, 2, 4.0)); // mid 1.0 in A
        assertEquals("B", m.invoke(null, List.of(g0, g1), 1, 2, 4.0)); // mid 3.0 in B
    }
}
