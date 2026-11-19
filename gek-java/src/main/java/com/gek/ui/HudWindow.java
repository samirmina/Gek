package com.gek.ui;

import com.gek.core.PlatformSupport;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.*;
import java.awt.*;

public class HudWindow extends JWindow {
    private final JTextArea original = new JTextArea(2, 48);
    private final JTextArea translated = new JTextArea(2, 48);
    private final JLabel meta = new JLabel(" ");

    public HudWindow(Window owner) {
        super(owner);
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 60, 170), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        root.setBackground(new Color(20, 20, 20, 225));

        original.setEditable(false); original.setLineWrap(true); original.setWrapStyleWord(true);
        original.setColumns(34); original.setRows(1);
        translated.setEditable(false); translated.setLineWrap(true); translated.setWrapStyleWord(true);
        translated.setColumns(34); translated.setRows(1);
        for (JTextArea a : new JTextArea[]{original, translated}) {
            a.setOpaque(false);
            a.setForeground(Color.WHITE);
            a.setFont(a.getFont().deriveFont(Font.PLAIN, 12f));
            a.setBorder(null);
        }
        meta.setForeground(new Color(180, 220, 255));
        meta.setFont(meta.getFont().deriveFont(10f));

        JPanel texts = new JPanel(new GridLayout(2, 1, 2, 2));
        texts.setOpaque(false);
        texts.add(wrap("SRC", original));
        texts.add(wrap("TR", translated));
        root.add(meta, BorderLayout.NORTH);
        root.add(texts, BorderLayout.CENTER);
        setContentPane(root);
        pack();
        positionBottomRight();
    }

    private JComponent wrap(String label, JTextArea area) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setForeground(new Color(160, 160, 160));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 10f));
        l.setPreferredSize(new Dimension(28, 16));
        p.add(l, BorderLayout.WEST);
        p.add(area, BorderLayout.CENTER);
        return p;
    }

    private void positionBottomRight() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gd.getDefaultConfiguration());
            int x = b.x + b.width - in.right - getWidth() - 16;
            int y = b.y + b.height - in.bottom - getHeight() - 16;
            setLocation(Math.max(b.x + 8, x), Math.max(b.y + 8, y));
        } catch (Exception ignored) { setLocationRelativeTo(null); }
    }

    public void showTranscript(String src, String tr, String m) {
        SwingUtilities.invokeLater(() -> {
            String s = src == null ? "" : src.trim();
            String t = tr == null ? "" : tr.trim();
            original.setText(trimHud(s, 220));
            translated.setText(trimHud(t, 220));
            original.setToolTipText(s.isEmpty() ? null : s);
            translated.setToolTipText(t.isEmpty() ? null : t);
            meta.setText(m == null ? " " : m);
            pack();
            positionBottomRight();
            if (!isVisible()) {
                setFocusableWindowState(false);
                setVisible(true);
            }
        });
    }

    private static String trimHud(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    public void setHudOpacity(double opacity) {
        float a = (float)Math.max(0.2, Math.min(1.0, opacity));
        try { setOpacity(a); } catch (Throwable ignored) {}
    }

    @Override
    public void addNotify() {
        super.addNotify();
        applyNonActivatingStyleIfWindows();
    }

    /**
     * Best-effort: make HUD non-activating on Windows so it never steals focus from
     * the external injection target (WS_EX_NOACTIVATE).
     */
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
            // non-fatal; Swing focusableWindowState(false) remains as fallback
        }
    }
}
