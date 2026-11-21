package com.gek.ui;

import com.gek.core.PlatformSupport;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * Minimal always-on-top floating control (Windows voice-typing style): compact status + mode actions.
 */
public class FloatingControlWindow extends JWindow {
    public enum Mode { VOICE_TYPING, LIVE_MIC, PLAYBACK_TRANSCRIBE }

    @FunctionalInterface public interface ModeAction { void onSelect(Mode mode); }

    private final JLabel status = new JLabel("Ready");
    private final JToggleButton voiceBtn = new JToggleButton("Voice");
    private final JToggleButton liveBtn = new JToggleButton("Live");
    private final JToggleButton playBtn = new JToggleButton("Playback");
    private final ButtonGroup group = new ButtonGroup();
    private ModeAction action;
    private Point dragOffset;

    public FloatingControlWindow(Window owner) {
        super(owner);
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);

        JPanel root = new JPanel(new BorderLayout(6, 0));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70, 180), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        root.setBackground(new Color(28, 28, 28, 235));

        status.setForeground(Color.WHITE);
        status.setFont(status.getFont().deriveFont(Font.BOLD, 11f));
        status.setPreferredSize(new Dimension(120, 18));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        for (JToggleButton b : new JToggleButton[]{voiceBtn, liveBtn, playBtn}) {
            b.setMargin(new Insets(2, 8, 2, 8));
            b.setFocusPainted(false);
            b.setFocusable(false);
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            buttons.add(b);
        }
        group.add(voiceBtn); group.add(liveBtn); group.add(playBtn);

        voiceBtn.setToolTipText("Voice typing (mic → focused text field)");
        liveBtn.setToolTipText("Live mic transcription/translation");
        playBtn.setToolTipText("Transcribe playback/system audio");

        voiceBtn.addActionListener(e -> fire(Mode.VOICE_TYPING, voiceBtn.isSelected()));
        liveBtn.addActionListener(e -> fire(Mode.LIVE_MIC, liveBtn.isSelected()));
        playBtn.addActionListener(e -> fire(Mode.PLAYBACK_TRANSCRIBE, playBtn.isSelected()));

        JButton close = new JButton("×");
        close.setMargin(new Insets(0, 6, 0, 6));
        close.setFocusable(false);
        close.addActionListener(e -> setVisible(false));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.setOpaque(false);
        right.add(close);

        root.add(status, BorderLayout.WEST);
        root.add(buttons, BorderLayout.CENTER);
        root.add(right, BorderLayout.EAST);

        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragOffset = e.getPoint(); }
        };
        MouseMotionAdapter move = new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset != null) {
                    Point p = e.getLocationOnScreen();
                    setLocation(p.x - dragOffset.x, p.y - dragOffset.y);
                }
            }
        };
        root.addMouseListener(drag); root.addMouseMotionListener(move);
        status.addMouseListener(drag); status.addMouseMotionListener(move);

        setContentPane(root);
        pack();
        positionTopCenter();
    }

    private void fire(Mode mode, boolean selected) {
        if (!selected) {
            if (action != null) action.onSelect(null);
            setStatusText("Ready");
            return;
        }
        if (mode == Mode.VOICE_TYPING) { liveBtn.setSelected(false); playBtn.setSelected(false); }
        else if (mode == Mode.LIVE_MIC) { voiceBtn.setSelected(false); playBtn.setSelected(false); }
        else if (mode == Mode.PLAYBACK_TRANSCRIBE) { voiceBtn.setSelected(false); liveBtn.setSelected(false); }
        if (action != null) action.onSelect(mode);
    }

    public void setModeAction(ModeAction action) { this.action = action; }

    public void setActiveMode(Mode mode) {
        SwingUtilities.invokeLater(() -> {
            group.clearSelection();
            if (mode == Mode.VOICE_TYPING) voiceBtn.setSelected(true);
            else if (mode == Mode.LIVE_MIC) liveBtn.setSelected(true);
            else if (mode == Mode.PLAYBACK_TRANSCRIBE) playBtn.setSelected(true);
            if (mode == null) setStatusText("Ready");
        });
    }

    public void clearActive() { setActiveMode(null); }

    public void setStatusText(String text) {
        SwingUtilities.invokeLater(() -> {
            String t = text == null ? "Ready" : text.trim();
            status.setToolTipText(text);
            if (t.length() > 28) t = t.substring(0, 27) + "…";
            status.setText(t.isEmpty() ? "Ready" : t);
            pack();
        });
    }

    private void positionTopCenter() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gd.getDefaultConfiguration());
            int x = b.x + (b.width - getWidth()) / 2;
            int y = b.y + in.top + 12;
            setLocation(x, y);
        } catch (Exception ignored) { setLocationRelativeTo(null); }
    }
}
