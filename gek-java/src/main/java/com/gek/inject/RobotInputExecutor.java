package com.gek.inject;

import com.gek.core.PlatformSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;

/**
 * Cross-platform {@link InputExecutor} using AWT {@link Robot} + system clipboard.
 * <ul>
 *   <li>Text insertion: clipboard + paste shortcut (replaces selection at caret, works in most apps/fields).</li>
 *   <li>Editing/navigation: key presses (arrows, backspace/delete, selection chords, Ctrl/Cmd shortcuts).</li>
 *   <li>Mouse primitives: basic click/move hooks for future extension.</li>
 * </ul>
 * Platform note: on Windows/Linux the command modifier maps to Control; on macOS to Meta.
 * On Windows, actions require a secured external injection target via {@link WindowsFocusManager}
 * so input is never delivered to the GEK UI.
 */
public class RobotInputExecutor implements InputExecutor {
    private static final Logger log = LoggerFactory.getLogger(RobotInputExecutor.class);

    @Override public String name() { return "robot"; }

    @Override public boolean execute(InputAction action) {
        if (action == null) return false;
        try {
            // Do not inject into an unintended window (e.g. GEK UI): require secured external target on Windows.
            if (PlatformSupport.isWindows() && !WindowsFocusManager.restoreForeground()) {
                log.debug("Skipping action (external injection target not secured): {}", action);
                return false;
            }
            Robot r = new Robot();
            r.setAutoDelay(8);
            return switch (action.kind()) {
                case TYPE_TEXT -> typeText(r, action.text());
                case BACKSPACE -> repeat(r, KeyEvent.VK_BACK_SPACE, action.count(), 0);
                case DELETE_FORWARD -> repeat(r, KeyEvent.VK_DELETE, action.count(), 0);
                case MOVE_LEFT -> repeat(r, KeyEvent.VK_LEFT, action.count(), 0);
                case MOVE_RIGHT -> repeat(r, KeyEvent.VK_RIGHT, action.count(), 0);
                case MOVE_UP -> repeat(r, KeyEvent.VK_UP, action.count(), 0);
                case MOVE_DOWN -> repeat(r, KeyEvent.VK_DOWN, action.count(), 0);
                case SELECT_LEFT -> repeat(r, KeyEvent.VK_LEFT, action.count(), KeyEvent.VK_SHIFT);
                case SELECT_RIGHT -> repeat(r, KeyEvent.VK_RIGHT, action.count(), KeyEvent.VK_SHIFT);
                case SELECT_UP -> repeat(r, KeyEvent.VK_UP, action.count(), KeyEvent.VK_SHIFT);
                case SELECT_DOWN -> repeat(r, KeyEvent.VK_DOWN, action.count(), KeyEvent.VK_SHIFT);
                case SELECT_WORD_LEFT -> repeatChord(r, KeyEvent.VK_LEFT, action.count(), KeyEvent.VK_SHIFT, ctrlOrCmd());
                case SELECT_WORD_RIGHT -> repeatChord(r, KeyEvent.VK_RIGHT, action.count(), KeyEvent.VK_SHIFT, ctrlOrCmd());
                case SELECT_ALL -> chord(r, KeyEvent.VK_A, ctrlOrCmd());
                case ENTER -> tap(r, KeyEvent.VK_ENTER);
                case TAB -> tap(r, KeyEvent.VK_TAB);
                case ESCAPE -> tap(r, KeyEvent.VK_ESCAPE);
                case UNDO -> chord(r, KeyEvent.VK_Z, ctrlOrCmd());
                case REDO -> redo(r);
                case COPY -> chord(r, KeyEvent.VK_C, ctrlOrCmd());
                case CUT -> chord(r, KeyEvent.VK_X, ctrlOrCmd());
                case PASTE -> chord(r, KeyEvent.VK_V, ctrlOrCmd());
                case KEY_CHORD -> keyChord(r, action.text());
            };
        } catch (Throwable t) {
            log.warn("Input action failed {}: {}", action, t.getMessage());
            return false;
        }
    }

    /** Type/insert text at caret via clipboard paste so it works with Unicode and any focused field. */
    public boolean typeText(Robot r, String text) {
        if (text == null || text.isEmpty()) return true;
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable old = null;
            try { old = cb.getContents(null); } catch (Exception ignored) {}
            String oldStr = null; Image oldImg = null;
            if (old != null) {
                try { if (old.isDataFlavorSupported(DataFlavor.stringFlavor)) oldStr = (String) old.getTransferData(DataFlavor.stringFlavor); } catch (Exception ignored) {}
                try { if (oldStr == null && old.isDataFlavorSupported(DataFlavor.imageFlavor)) oldImg = (Image) old.getTransferData(DataFlavor.imageFlavor); } catch (Exception ignored) {}
            }
            cb.setContents(new StringSelection(text), null);
            sleep(25);
            // Paste: Ctrl+V (Windows/Linux) and Cmd+V on macOS
            if (isMac()) chord(r, KeyEvent.VK_V, KeyEvent.VK_META);
            else chord(r, KeyEvent.VK_V, KeyEvent.VK_CONTROL);
            sleep(60);
            try {
                if (oldStr != null) cb.setContents(new StringSelection(oldStr), null);
                else if (oldImg != null) cb.setContents(new ImageTransferable(oldImg), null);
            } catch (Exception ignored) {}
            return true;
        } catch (Exception e) {
            log.warn("typeText failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean redo(Robot r) {
        if (isMac()) return chord(r, KeyEvent.VK_Z, KeyEvent.VK_META, KeyEvent.VK_SHIFT);
        return chord(r, KeyEvent.VK_Y, KeyEvent.VK_CONTROL);
    }

    private boolean keyChord(Robot r, String spec) {
        if (spec == null || spec.isBlank()) return false;
        String[] parts = spec.toLowerCase(Locale.ROOT).trim().split("\\+");
        int[] mods = new int[parts.length];
        int key = -1; int mi = 0;
        for (String p : parts) {
            String t = p.trim();
            Integer k = mapKey(t);
            if (k == null) continue;
            if (isModifier(k)) mods[mi++] = k; else key = k;
        }
        if (key < 0) return false;
        try {
            for (int i = 0; i < mi; i++) r.keyPress(mods[i]);
            r.keyPress(key); r.keyRelease(key);
            for (int i = mi - 1; i >= 0; i--) r.keyRelease(mods[i]);
            return true;
        } catch (Exception e) { return false; }
    }

    private Integer mapKey(String t) {
        return switch (t) {
            case "ctrl", "control" -> KeyEvent.VK_CONTROL;
            case "shift" -> KeyEvent.VK_SHIFT;
            case "alt", "option" -> KeyEvent.VK_ALT;
            case "meta", "cmd", "command", "win", "windows" -> KeyEvent.VK_META;
            case "enter", "return" -> KeyEvent.VK_ENTER;
            case "tab" -> KeyEvent.VK_TAB;
            case "esc", "escape" -> KeyEvent.VK_ESCAPE;
            case "backspace" -> KeyEvent.VK_BACK_SPACE;
            case "delete", "del" -> KeyEvent.VK_DELETE;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "home" -> KeyEvent.VK_HOME;
            case "end" -> KeyEvent.VK_END;
            case "space" -> KeyEvent.VK_SPACE;
            case "a" -> KeyEvent.VK_A; case "c" -> KeyEvent.VK_C; case "v" -> KeyEvent.VK_V;
            case "x" -> KeyEvent.VK_X; case "z" -> KeyEvent.VK_Z; case "y" -> KeyEvent.VK_Y;
            case "s" -> KeyEvent.VK_S;
            default -> {
                if (t.length() == 1) {
                    char c = Character.toUpperCase(t.charAt(0));
                    if (c >= 'A' && c <= 'Z') yield KeyEvent.getExtendedKeyCodeForChar(c);
                    if (c >= '0' && c <= '9') yield KeyEvent.getExtendedKeyCodeForChar(c);
                }
                yield null;
            }
        };
    }

    private boolean isModifier(int vk) {
        return vk == KeyEvent.VK_CONTROL || vk == KeyEvent.VK_SHIFT || vk == KeyEvent.VK_ALT || vk == KeyEvent.VK_META;
    }

    private boolean repeat(Robot r, int key, int count, int withModifier) {
        int n = Math.max(1, count);
        try {
            if (withModifier != 0) r.keyPress(withModifier);
            for (int i = 0; i < n; i++) { r.keyPress(key); r.keyRelease(key); }
            if (withModifier != 0) r.keyRelease(withModifier);
            return true;
        } catch (Exception e) { if (withModifier != 0) try { r.keyRelease(withModifier); } catch (Exception ignored) {} return false; }
    }

    private boolean repeatChord(Robot r, int key, int count, int mod1, int mod2) {
        int n = Math.max(1, count);
        try {
            r.keyPress(mod1); r.keyPress(mod2);
            for (int i = 0; i < n; i++) { r.keyPress(key); r.keyRelease(key); }
            r.keyRelease(mod2); r.keyRelease(mod1);
            return true;
        } catch (Exception e) { try { r.keyRelease(mod2); r.keyRelease(mod1); } catch (Exception ignored) {} return false; }
    }

    private boolean tap(Robot r, int key) { try { r.keyPress(key); r.keyRelease(key); return true; } catch (Exception e) { return false; } }

    private boolean chord(Robot r, int key, int... modifiers) {
        try {
            for (int m : modifiers) r.keyPress(m);
            r.keyPress(key); r.keyRelease(key);
            for (int i = modifiers.length - 1; i >= 0; i--) r.keyRelease(modifiers[i]);
            return true;
        } catch (Exception e) { for (int i = modifiers.length - 1; i >= 0; i--) try { r.keyRelease(modifiers[i]); } catch (Exception ignored) {} return false; }
    }

    public void mouseMove(Robot r, int x, int y) { r.mouseMove(x, y); }
    public void mouseClick(Robot r, boolean left) {
        int mask = left ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
        r.mousePress(mask); r.mouseRelease(mask);
    }

    private static int ctrlOrCmd() { return isMac() ? KeyEvent.VK_META : KeyEvent.VK_CONTROL; }
    private static boolean isMac() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static InputExecutor forPlatform() { return new RobotInputExecutor(); }

    static class ImageTransferable implements Transferable {
        private final Image image;
        ImageTransferable(Image image) { this.image = image; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
}
