package com.gek.ui;

import com.gek.core.PlatformSupport;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.*;
import java.awt.*;

/**
 * Dedicated non-activating overlay for streaming chat voice-typing partials (separate from live-captions HUD).
 * Shows evolving interim text while speaking; cleared/finalized by the chat typing controller. Never fed by
 * live-captions transcripts.
 */
public class ChatTypingWindow extends JWindow {
    private final JTextArea partial = new JTextArea(2, 40);
    private final JLabel meta = new JLabel("Voice typing");

    public ChatTypingWindow(Window owner) {
        super(owner);
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 90, 120, 190), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        root.setBackground(new Color(24, 28, 34, 235));

        partial.setEditable(false);
        partial.setLineWrap(true);
        partial.setWrapStyleWord(true);
        partial.setColumns(36);
        partial.setRows(2);
        partial.setOpaque(false);
        partial.setForeground(new Color(235, 242, 255));
        partial.setFont(partial.getFont().deriveFont(Font.PLAIN, 13f));
        partial.setBorder(null);

        meta.setForeground(new Color(160, 200, 255));
        meta.setFont(meta.getFont().deriveFont(Font.BOLD, 10f));

        root.add(meta, BorderLayout.NORTH);
        root.add(partial, BorderLayout.CENTER);
        setContentPane(root);
        pack();
        positionBottomCenter();
    }

    /** Update live partial text shown while speaking (chat-style). */
    public void showPartial(String text) {
        SwingUtilities.invokeLater(() -> {
            String t = text == null ? "" : text.trim();
            partial.setText(tail(t, 1200));
            partial.setToolTipText(t.isEmpty() ? null : t);
            meta.setText(t.isEmpty() ? "Voice typing" : "Voice typing (listening…)");
            pack();
            positionBottomCenter();
            if (!isVisible()) {
                setFocusableWindowState(false);
                setVisible(true);
            }
        });
    }

    /** Indicate finalization/injection state briefly or clear. */
    public void showStatus(String statusText) {
        SwingUtilities.invokeLater(() -> {
            String s = statusText == null ? "" : statusText.trim();
            if (s.isEmpty()) {
                partial.setText("");
                partial.setToolTipText(null);
                meta.setText("Voice typing");
            } else {
                meta.setText(s);
            }
            pack();
            positionBottomCenter();
        });
    }

    public void clearPartial() {
        SwingUtilities.invokeLater(() -> {
            partial.setText("");
            partial.setToolTipText(null);
            meta.setText("Voice typing");
            pack();
            positionBottomCenter();
        });
    }

    private void positionBottomCenter() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gd.getDefaultConfiguration());
            int x = b.x + (b.width - getWidth()) / 2;
            int y = b.y + b.height - in.bottom - getHeight() - 16;
            setLocation(Math.max(b.x + 8, x), Math.max(b.y + 8, y));
        } catch (Exception ignored) { setLocationRelativeTo(null); }
    }

    /** Keep the tail (most recent text) visible when content exceeds the display budget. */
    private static String tail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int start = Math.max(0, s.length() - Math.max(0, max - 1));
        return "…" + s.substring(start);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        applyNonActivatingStyleIfWindows();
    }

    private void applyNonActivatingStyleIfWindows() {
        if (!PlatformSupport.isWindows()) return;
        try {
            Pointer p = Native.getComponentPointer(this);
            if (p == null || Pointer.nativeValue(p) == 0) return;
            WinDef.HWND hwnd = new WinDef.HWND(p);
            final int GWL_EXSTYLE = -20;
            final int WS_EX_NOACTIVATE = 0x08000000;
            final int WS_EX_TOOLWINDOW = 0x00000080;
            int ex = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
            int want = ex | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
            if (want != ex) {
                User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, want);
            }
        } catch (Throwable ignored) {
        }
    }
}
