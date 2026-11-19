package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class WindowsFocusManagerLinuxTest {

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = WindowsFocusManager.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test void publicApiOnNonWindows() {
        if (PlatformSupport.isWindows()) return;
        assertDoesNotThrow(WindowsFocusManager::captureForeground);
        assertDoesNotThrow(WindowsFocusManager::captureForegroundIfUseful);
        assertTrue(WindowsFocusManager.restoreForeground());
        assertFalse(WindowsFocusManager.hasExternalTarget());
        assertDoesNotThrow(WindowsFocusManager::clear);
    }

    @Test void privateNullGuardsViaReflection() throws Exception {
        if (PlatformSupport.isWindows()) return;
        // methods take WinDef.HWND; on non-Windows class is loaded but passing null exercises null checks
        Class<?> hwndCls = Class.forName("com.sun.jna.platform.win32.WinDef$HWND");
        assertEquals(false, invokeStatic("isValidWindow", new Class[]{hwndCls}, new Object[]{null}));
        assertEquals(false, invokeStatic("isSelf", new Class[]{hwndCls}, new Object[]{null}));
        assertEquals(false, invokeStatic("isExternalTarget", new Class[]{hwndCls}, new Object[]{null}));
        assertEquals(false, invokeStatic("sameWindow", new Class[]{hwndCls, hwndCls}, new Object[]{null, null}));
    }
}
