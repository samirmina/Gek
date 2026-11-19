package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

class WindowsTextInjectorMoreTest {

    @Test void basicAndInjectPaths() {
        WindowsTextInjector inj = new WindowsTextInjector();
        assertEquals("windows-clipboard-paste", inj.name());
        assertFalse(inj.inject(null));
        assertFalse(inj.inject(""));
        // non-empty: on non-Windows restoreForeground returns true, then paste attempts clipboard/robot and fails in headless -> returns false
        if (!PlatformSupport.isWindows()) {
            assertDoesNotThrow(() -> inj.inject("hello"));
        }

        TextInjector fp = WindowsTextInjector.forPlatform();
        assertNotNull(fp);
        assertNotNull(fp.name());
    }

    @Test void imageSelectionTransferable() throws Exception {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        Class<?> cls = Class.forName("com.gek.inject.WindowsTextInjector$ImageSelection");
        Constructor<?> ctor = cls.getDeclaredConstructor(java.awt.Image.class);
        ctor.setAccessible(true);
        Object obj = ctor.newInstance(img);
        java.awt.datatransfer.Transferable t = (java.awt.datatransfer.Transferable) obj;
        assertTrue(t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor));
        assertArrayEquals(new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor}, t.getTransferDataFlavors());
        assertSame(img, t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor));
        assertThrows(java.awt.datatransfer.UnsupportedFlavorException.class,
                () -> t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor));
    }
}
