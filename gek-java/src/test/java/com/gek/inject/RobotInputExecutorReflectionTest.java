package com.gek.inject;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class RobotInputExecutorReflectionTest {

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = RobotInputExecutor.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test void basicInfoAndNullExecute() {
        RobotInputExecutor ex = new RobotInputExecutor();
        assertEquals("robot", ex.name());
        assertNotNull(RobotInputExecutor.forPlatform());
        assertFalse(ex.execute(null));
        // in headless env Robot creation fails -> returns false; on GUI hosts with a secured
        // foreground target (permissive policy: GEK or any app) Robot may execute and return true.
        // Avoid sending a real key press in headed environments.
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            assertFalse(ex.execute(InputAction.enter()));
        } else {
            // execute() must not throw regardless of target/robot availability
            assertDoesNotThrow(() -> ex.execute(InputAction.backspace(1)));
        }
    }

    @Test void keyMappingAndHelpers() throws Exception {
        RobotInputExecutor ex = new RobotInputExecutor();
        // mapKey
        assertEquals(KeyEvent.VK_CONTROL, invoke(ex, "mapKey", new Class[]{String.class}, "ctrl"));
        assertEquals(KeyEvent.VK_CONTROL, invoke(ex, "mapKey", new Class[]{String.class}, "control"));
        assertEquals(KeyEvent.VK_SHIFT, invoke(ex, "mapKey", new Class[]{String.class}, "shift"));
        assertEquals(KeyEvent.VK_ALT, invoke(ex, "mapKey", new Class[]{String.class}, "alt"));
        assertEquals(KeyEvent.VK_META, invoke(ex, "mapKey", new Class[]{String.class}, "cmd"));
        assertEquals(KeyEvent.VK_META, invoke(ex, "mapKey", new Class[]{String.class}, "win"));
        assertEquals(KeyEvent.VK_ENTER, invoke(ex, "mapKey", new Class[]{String.class}, "enter"));
        assertEquals(KeyEvent.VK_TAB, invoke(ex, "mapKey", new Class[]{String.class}, "tab"));
        assertEquals(KeyEvent.VK_ESCAPE, invoke(ex, "mapKey", new Class[]{String.class}, "esc"));
        assertEquals(KeyEvent.VK_BACK_SPACE, invoke(ex, "mapKey", new Class[]{String.class}, "backspace"));
        assertEquals(KeyEvent.VK_DELETE, invoke(ex, "mapKey", new Class[]{String.class}, "del"));
        assertEquals(KeyEvent.VK_LEFT, invoke(ex, "mapKey", new Class[]{String.class}, "left"));
        assertEquals(KeyEvent.VK_RIGHT, invoke(ex, "mapKey", new Class[]{String.class}, "right"));
        assertEquals(KeyEvent.VK_UP, invoke(ex, "mapKey", new Class[]{String.class}, "up"));
        assertEquals(KeyEvent.VK_DOWN, invoke(ex, "mapKey", new Class[]{String.class}, "down"));
        assertEquals(KeyEvent.VK_HOME, invoke(ex, "mapKey", new Class[]{String.class}, "home"));
        assertEquals(KeyEvent.VK_END, invoke(ex, "mapKey", new Class[]{String.class}, "end"));
        assertEquals(KeyEvent.VK_SPACE, invoke(ex, "mapKey", new Class[]{String.class}, "space"));
        assertEquals(KeyEvent.VK_A, invoke(ex, "mapKey", new Class[]{String.class}, "a"));
        assertEquals(KeyEvent.VK_C, invoke(ex, "mapKey", new Class[]{String.class}, "c"));
        assertEquals(KeyEvent.VK_V, invoke(ex, "mapKey", new Class[]{String.class}, "v"));
        assertEquals(KeyEvent.VK_X, invoke(ex, "mapKey", new Class[]{String.class}, "x"));
        assertEquals(KeyEvent.VK_Z, invoke(ex, "mapKey", new Class[]{String.class}, "z"));
        assertEquals(KeyEvent.VK_Y, invoke(ex, "mapKey", new Class[]{String.class}, "y"));
        assertEquals(KeyEvent.VK_S, invoke(ex, "mapKey", new Class[]{String.class}, "s"));
        assertNotNull(invoke(ex, "mapKey", new Class[]{String.class}, "5"));
        assertNull(invoke(ex, "mapKey", new Class[]{String.class}, "f12"));
        assertNull(invoke(ex, "mapKey", new Class[]{String.class}, "unknown"));

        assertTrue((Boolean) invoke(ex, "isModifier", new Class[]{int.class}, KeyEvent.VK_CONTROL));
        assertTrue((Boolean) invoke(ex, "isModifier", new Class[]{int.class}, KeyEvent.VK_SHIFT));
        assertTrue((Boolean) invoke(ex, "isModifier", new Class[]{int.class}, KeyEvent.VK_ALT));
        assertTrue((Boolean) invoke(ex, "isModifier", new Class[]{int.class}, KeyEvent.VK_META));
        assertFalse((Boolean) invoke(ex, "isModifier", new Class[]{int.class}, KeyEvent.VK_A));

        Method isMac = RobotInputExecutor.class.getDeclaredMethod("isMac");
        isMac.setAccessible(true);
        Method ctrlOrCmd = RobotInputExecutor.class.getDeclaredMethod("ctrlOrCmd");
        ctrlOrCmd.setAccessible(true);
        String old = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            assertTrue((Boolean) isMac.invoke(null));
            assertEquals(KeyEvent.VK_META, (int) ctrlOrCmd.invoke(null));
            System.setProperty("os.name", "Linux");
            assertFalse((Boolean) isMac.invoke(null));
            assertEquals(KeyEvent.VK_CONTROL, (int) ctrlOrCmd.invoke(null));
        } finally {
            if (old != null) System.setProperty("os.name", old);
        }
    }

    @Test void keyChordAndRobotHelpersWithNullRobot() throws Exception {
        RobotInputExecutor ex = new RobotInputExecutor();
        // keyChord parses then fails on null robot press -> returns false but covers parsing paths
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, "ctrl+c"}));
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, "shift+left"}));
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, "a"}));
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, "ctrl+shift+z"}));
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, ""}));
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, "   "}));
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, null}));
        assertFalse((Boolean) invoke(ex, "keyChord", new Class[]{java.awt.Robot.class, String.class}, new Object[]{null, "ctrl+bogus"}));

        // repeat/tap/chord/repeatChord/redo with null robot exercise loops and exception handling
        assertFalse((Boolean) invoke(ex, "repeat", new Class[]{java.awt.Robot.class, int.class, int.class, int.class}, new Object[]{null, KeyEvent.VK_BACK_SPACE, 3, 0}));
        assertFalse((Boolean) invoke(ex, "repeat", new Class[]{java.awt.Robot.class, int.class, int.class, int.class}, new Object[]{null, KeyEvent.VK_LEFT, 2, KeyEvent.VK_SHIFT}));
        assertFalse((Boolean) invoke(ex, "repeat", new Class[]{java.awt.Robot.class, int.class, int.class, int.class}, new Object[]{null, KeyEvent.VK_RIGHT, 0, 0}));
        assertFalse((Boolean) invoke(ex, "tap", new Class[]{java.awt.Robot.class, int.class}, new Object[]{null, KeyEvent.VK_ENTER}));

        Method chord = RobotInputExecutor.class.getDeclaredMethod("chord", java.awt.Robot.class, int.class, int[].class);
        chord.setAccessible(true);
        assertFalse((Boolean) chord.invoke(ex, null, KeyEvent.VK_A, new int[]{KeyEvent.VK_CONTROL}));
        assertFalse((Boolean) chord.invoke(ex, null, KeyEvent.VK_A, new int[]{}));

        assertFalse((Boolean) invoke(ex, "repeatChord", new Class[]{java.awt.Robot.class, int.class, int.class, int.class, int.class}, new Object[]{null, KeyEvent.VK_LEFT, 2, KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL}));

        String old = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            assertFalse((Boolean) invoke(ex, "redo", new Class[]{java.awt.Robot.class}, new Object[]{null}));
            System.setProperty("os.name", "Linux");
            assertFalse((Boolean) invoke(ex, "redo", new Class[]{java.awt.Robot.class}, new Object[]{null}));
        } finally {
            if (old != null) System.setProperty("os.name", old);
        }

        // typeText: null/empty return true before robot use; non-empty hits clipboard in headless -> likely exception/false
        assertTrue(ex.typeText(null, null));
        assertTrue(ex.typeText(null, ""));
        try { ex.typeText(null, "hi"); } catch (Throwable ignored) {}

        // mouse methods have no try-catch
        assertThrows(Throwable.class, () -> ex.mouseMove(null, 1, 2));
        assertThrows(Throwable.class, () -> ex.mouseClick(null, true));
        assertThrows(Throwable.class, () -> ex.mouseClick(null, false));
    }

    @Test void imageTransferable() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Class<?> cls = Class.forName("com.gek.inject.RobotInputExecutor$ImageTransferable");
        var ctor = cls.getDeclaredConstructor(java.awt.Image.class);
        ctor.setAccessible(true);
        Object t = ctor.newInstance(img);
        java.awt.datatransfer.Transferable tr = (java.awt.datatransfer.Transferable) t;
        assertTrue(tr.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor));
        assertArrayEquals(new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor}, tr.getTransferDataFlavors());
        assertSame(img, tr.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor));
        assertThrows(java.awt.datatransfer.UnsupportedFlavorException.class,
                () -> tr.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor));
    }
}
