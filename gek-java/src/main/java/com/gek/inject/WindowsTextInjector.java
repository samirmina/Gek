package com.gek.inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;

/**
 * Caret injection for Windows (validate on Windows).
 * Strategy: save clipboard -> set text -> Ctrl+V via Robot -> restore clipboard.
 * Requires a secured external injection target (never injects into GEK UI).
 */
public class WindowsTextInjector implements TextInjector {
    private static final Logger log = LoggerFactory.getLogger(WindowsTextInjector.class);

    @Override public String name() { return "windows-clipboard-paste"; }

    @Override public boolean inject(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            if (!WindowsFocusManager.restoreForeground()) {
                log.debug("Injection skipped: external target not secured");
                return false;
            }
            return pasteViaClipboard(text);
        } catch (Exception e) {
            log.error("Clipboard paste inject failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean pasteViaClipboard(String text) throws Exception {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable old = null;
        try { old = cb.getContents(null); } catch (Exception ignored) {}
        String oldStr = null; java.awt.Image oldImg = null;
        if (old != null) {
            try { if (old.isDataFlavorSupported(DataFlavor.stringFlavor)) oldStr = (String) old.getTransferData(DataFlavor.stringFlavor); } catch (Exception ignored) {}
            try { if (oldStr == null && old.isDataFlavorSupported(DataFlavor.imageFlavor)) oldImg = (java.awt.Image) old.getTransferData(DataFlavor.imageFlavor); } catch (Exception ignored) {}
        }
        cb.setContents(new StringSelection(text), null);
        sleep(30);
        sendCtrlV();
        sleep(70);
        try {
            if (oldStr != null) cb.setContents(new StringSelection(oldStr), null);
            else if (oldImg != null) cb.setContents(new ImageSelection(oldImg), null);
        } catch (Exception ignored) {}
        return true;
    }

    private void sendCtrlV() throws AWTException {
        Robot r = new Robot();
        r.setAutoDelay(10);
        r.keyPress(KeyEvent.VK_CONTROL);
        r.keyPress(KeyEvent.VK_V);
        r.keyRelease(KeyEvent.VK_V);
        r.keyRelease(KeyEvent.VK_CONTROL);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static class ImageSelection implements Transferable {
        private final java.awt.Image image;
        ImageSelection(java.awt.Image image) { this.image = image; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }

    public static TextInjector forPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return new WindowsTextInjector();
        return ClipboardTextInjector.forPlatform();
    }
}
