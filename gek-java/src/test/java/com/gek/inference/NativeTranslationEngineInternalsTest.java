package com.gek.inference;

import com.gek.core.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NativeTranslationEngineInternalsTest {

    private static AppPaths resolvePaths(Path cwd) throws Exception {
        Method m = AppPaths.class.getDeclaredMethod("resolve", Path.class, String.class);
        m.setAccessible(true);
        return (AppPaths) m.invoke(null, cwd, null);
    }

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = NativeTranslationEngine.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void staticHelpersNormalizeTrimSanitizeExtract() throws Exception {
        assertNull(invokeStatic("normalize", new Class[]{String.class}, (Object) null));
        assertNull(invokeStatic("normalize", new Class[]{String.class}, "  "));
        assertEquals("en", invokeStatic("normalize", new Class[]{String.class}, " EN "));

        assertEquals("", invokeStatic("stripOuter", new Class[]{String.class}, "  \n"));
        assertEquals("hi", invokeStatic("stripOuter", new Class[]{String.class}, "  hi  "));

        assertEquals("a b", invokeStatic("sanitize", new Class[]{String.class}, "a\u2581 b"));
        assertEquals("x\ny", invokeStatic("sanitize", new Class[]{String.class}, " x  \n y "));
        assertNull(invokeStatic("sanitize", new Class[]{String.class}, (Object) null));

        assertEquals("hello\nworld", invokeStatic("extractOutput", new Class[]{String.class}, "hello\nworld\n"));
        assertEquals("", invokeStatic("extractOutput", new Class[]{String.class}, (Object) null));

        assertEquals("abc", invokeStatic("trim", new Class[]{String.class, int.class}, "abc", 10));
        assertEquals("ab...", invokeStatic("trim", new Class[]{String.class, int.class}, "abcdef", 5));
    }

    @Test
    void constructorAndBasicTranslateBranches(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root.resolve("gek-java"));
        Files.createDirectories(root.resolve("resources").resolve("data"));
        Files.createDirectories(root.resolve("resources").resolve("models").resolve("argos").resolve("packages"));
        AppPaths paths = resolvePaths(root);
        NativeTranslationEngine eng = new NativeTranslationEngine(paths);
        assertNotNull(eng.reason());
        eng.runtimeUsable();
        eng.inJvm();

        assertEquals("  hi  ", eng.translate("  hi  ", "en", "en").join());
        assertEquals("", eng.translate("", "en", "es").join());

        if (!eng.inJvm()) {
            assertThrows(Exception.class, () -> eng.translate("hello", "auto", "es").join());
            assertThrows(Exception.class, () -> eng.translate("hello", null, "es").join());
            assertThrows(Exception.class, () -> eng.translate("hello", "en", null).join());
        }
        eng.close();
    }
}
