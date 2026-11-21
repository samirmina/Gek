package com.gek.ui;

import com.gek.app.ChatVoiceTypingController;
import com.gek.app.DictationController;
import com.gek.app.LiveTranslateController;
import com.gek.audio.AutoAudioSelector;
import com.gek.audio.CommandLoopbackCapture;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.core.PlatformSupport;
import com.gek.core.SettingsStore;
import com.gek.db.HistoryRepository;
import com.gek.audio.AppDucker;
import com.gek.audio.TtsEngine;
import com.gek.inference.SidecarClient;
import com.gek.inject.GlobalHotkey;
import com.gek.inject.WindowsFocusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Compact main menu + minimal floating control for:
 *  - Voice Typing (translation-oriented, streams translated text into the focused external field/caret; no GEK overlay)
 *  - Live (mic transcription/translation -> HUD overlay only)
 *  - Playback Transcribe (system/playback audio capture -> HUD overlay only)
 * Devices are auto-selected by default (no manual dropdowns).
 */
public class ControlWindow extends JFrame {
    private static final Logger log = LoggerFactory.getLogger(ControlWindow.class);

    private final AppPaths paths;
    private final AppSettings settings;
    private final SettingsStore store;
    private final DictationController dictation;
    private final ChatVoiceTypingController chatTyping;
    private final LiveTranslateController live;
    private final SidecarClient sidecar;
    private final GlobalHotkey hotkey;
    private final HistoryRepository history;
    private final PlatformSupport.Capabilities caps;
    private final TtsEngine ttsEngine;
    private final AppDucker ducker;

    private final JLabel status = new JLabel("Ready");
    private final JToggleButton voiceBtn = new JToggleButton("Voice Typing");
    private final JToggleButton liveBtn = new JToggleButton("Live (Mic)");
    private final JToggleButton playbackBtn = new JToggleButton("Playback");

    private HudWindow hudWindow;
    private FloatingControlWindow floatingWindow;
    private TrayIcon trayIcon;

    public ControlWindow(AppPaths paths, AppSettings settings, SettingsStore store,
                         DictationController dictation, ChatVoiceTypingController chatTyping,
                         LiveTranslateController live,
                         SidecarClient sidecar, GlobalHotkey hotkey, HistoryRepository history,
                         PlatformSupport.Capabilities caps, TtsEngine ttsEngine, AppDucker ducker) {
        super("GEK");
        this.paths = paths; this.settings = settings; this.store = store;
        this.dictation = dictation; this.chatTyping = chatTyping; this.live = live;
        this.sidecar = sidecar; this.hotkey = hotkey; this.history = history;
        this.caps = caps; this.ttsEngine = ttsEngine; this.ducker = ducker;

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setLayout(new BorderLayout(4, 4));
        setJMenuBar(buildMenuBar());

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setFont(status.getFont().deriveFont(Font.BOLD, 12f));
        status.setToolTipText("Voice typing: streams translated text into the focused field (no overlay). Hotkey toggles start/stop; stop leaves text for review/send.");
        root.add(status);
        root.add(Box.createVerticalStrut(6));

        JPanel modes = new JPanel(new GridLayout(1, 3, 6, 0));
        modes.setAlignmentX(Component.LEFT_ALIGNMENT);
        configureModeButton(voiceBtn, "Translation-oriented voice typing into focused field (hotkey toggles start/stop)");
        configureModeButton(liveBtn, "Live mic transcription/translation (HUD overlay)");
        configureModeButton(playbackBtn, "Transcribe playback/system audio (HUD overlay)");
        modes.add(voiceBtn); modes.add(liveBtn); modes.add(playbackBtn);
        root.add(modes);
        root.add(Box.createVerticalStrut(6));

        JLabel hint = new JLabel(autoHint());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setFont(hint.getFont().deriveFont(10.5f));
        hint.setForeground(new Color(90, 90, 90));
        root.add(hint);

        add(root, BorderLayout.CENTER);

        voiceBtn.addActionListener(e -> onModePressed(Mode.VOICE_TYPING, voiceBtn.isSelected()));
        liveBtn.addActionListener(e -> onModePressed(Mode.LIVE_MIC, liveBtn.isSelected()));
        playbackBtn.addActionListener(e -> onModePressed(Mode.PLAYBACK_TRANSCRIBE, playbackBtn.isSelected()));

        // Chat voice typing (translation-oriented, in-field streaming) — no GEK overlay, writes into focused field/caret.
        if (chatTyping != null) {
            chatTyping.setPartialListener(null);
            chatTyping.setStatusListener(s -> onStatus(FloatingControlWindow.Mode.VOICE_TYPING, "[voice] " + s));
        } else if (dictation != null) {
            dictation.setStatusListener(s -> onStatus(FloatingControlWindow.Mode.VOICE_TYPING, "[voice] " + s));
        }

        // Live captions pipeline (overlay-only) — transcripts to HUD only, no caret injection.
        live.setStatusListener(s -> onStatus(activeLiveFloatingMode(), "[audio] " + s));
        live.setTranscriptListener((o, t, m, src) -> {
            if (hudWindow != null && hudWindow.isVisible()) hudWindow.showTranscript(o, t, m + " (" + src + ")");
        });

        setupTray();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (trayIcon != null) setVisible(false); else shutdown(); }
            @Override public void windowClosed(WindowEvent e) { shutdown(); }
        });
        pack();
        setResizable(false);
        setMinimumSize(new Dimension(360, getHeight()));
        setLocationRelativeTo(null);
    }

    public void showPrimaryOverlay() {
        ensureFloating();
        if (floatingWindow != null) floatingWindow.setVisible(true);
        if (trayIcon == null) {
            setVisible(true);
        } else {
            setVisible(false);
        }
        String hk = settings != null && settings.dictation_hotkey != null && !settings.dictation_hotkey.isBlank()
                ? settings.dictation_hotkey : "CTRL+SHIFT+SPACE";
        syncUi(null, "Ready — " + hk + " to voice-type");
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu app = new JMenu("Menu");
        JMenuItem showFloat = new JMenuItem("Show Floating Controls");
        showFloat.addActionListener(e -> { ensureFloating(); floatingWindow.setVisible(true); });
        JMenuItem hud = new JMenuItem("Toggle Transcript HUD");
        hud.addActionListener(e -> toggleHud());
        JMenuItem historyItem = new JMenuItem("History");
        historyItem.addActionListener(e -> new HistoryWindow(this, history).setVisible(true));
        JMenuItem notesItem = new JMenuItem("Notes");
        notesItem.addActionListener(e -> new NotesWindow(this, paths.dataDir().resolve("notes.txt")).setVisible(true));
        JMenuItem settingsItem = new JMenuItem("Settings");
        settingsItem.addActionListener(e -> new SettingsWindow(this, paths, settings, store).setVisible(true));
        JMenuItem hide = new JMenuItem("Hide");
        hide.addActionListener(e -> setVisible(false));
        app.add(showFloat); app.add(hud); app.addSeparator();
        app.add(historyItem); app.add(notesItem); app.add(settingsItem);
        app.addSeparator(); app.add(hide);
        mb.add(app);
        return mb;
    }

    private void configureModeButton(JToggleButton b, String tip) {
        b.setToolTipText(tip);
        b.setMargin(new Insets(6, 8, 6, 8));
        b.setFocusPainted(false);
    }

    private String autoHint() {
        if (PlatformSupport.isWindows())
            return "Use hotkey in target app to capture caret (overlay-first). Devices auto-selected.";
        return "Devices: auto (mic default, playback monitor when available).";
    }

    private enum Mode { VOICE_TYPING, LIVE_MIC, PLAYBACK_TRANSCRIBE }

    private void onModePressed(Mode mode, boolean selected) {
        if (!selected) {
            stopAllModes();
            WindowsFocusManager.clear();
            syncUi(null, "Ready");
            return;
        }
        if (PlatformSupport.isWindows() && !WindowsFocusManager.hasExternalTarget()) {
            WindowsFocusManager.captureForeground();
        }
        if (mode != Mode.VOICE_TYPING) voiceBtn.setSelected(false);
        if (mode != Mode.LIVE_MIC) liveBtn.setSelected(false);
        if (mode != Mode.PLAYBACK_TRANSCRIBE) playbackBtn.setSelected(false);
        stopAllModes();
        switch (mode) {
            case VOICE_TYPING -> startVoiceTyping();
            case LIVE_MIC -> startLiveMic();
            case PLAYBACK_TRANSCRIBE -> startPlaybackTranscribe();
        }
    }

    private void startVoiceTyping() {
        if (PlatformSupport.isWindows() && !WindowsFocusManager.hasExternalTarget()) {
            String hk = settings != null && settings.dictation_hotkey != null ? settings.dictation_hotkey : "CTRL+SHIFT+SPACE";
            syncUi(null, "Place caret in target app, then press " + hk + " (GEK UI cannot capture cursor)");
            voiceBtn.setSelected(false);
            return;
        }
        syncUi(FloatingControlWindow.Mode.VOICE_TYPING, "Starting voice typing...");
        new Thread(() -> {
            try {
                if (chatTyping != null) {
                    chatTyping.start();
                } else {
                    dictation.start();
                }
                SwingUtilities.invokeLater(() -> syncUi(FloatingControlWindow.Mode.VOICE_TYPING, "Voice typing (in-field, auto mic)"));
            } catch (Exception ex) {
                log.warn("voice typing start failed: {}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> { syncUi(null, "Voice typing failed: " + rootMsg(ex)); voiceBtn.setSelected(false); });
            }
        }, "gek-voice-start").start();
    }

    private void startLiveMic() {
        syncUi(FloatingControlWindow.Mode.LIVE_MIC, "Starting live mic...");
        new Thread(() -> {
            try {
                live.startMic();
                SwingUtilities.invokeLater(() -> syncUi(FloatingControlWindow.Mode.LIVE_MIC, "Live (auto mic)"));
            } catch (Exception ex) {
                log.warn("live mic start failed: {}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> { syncUi(null, "Live failed: " + rootMsg(ex)); liveBtn.setSelected(false); });
            }
        }, "gek-live-mic-start").start();
    }

    private void startPlaybackTranscribe() {
        syncUi(FloatingControlWindow.Mode.PLAYBACK_TRANSCRIBE, "Starting playback capture...");
        new Thread(() -> {
            try {
                String src = AutoAudioSelector.resolveLoopbackSource(settings);
                if (!CommandLoopbackCapture.isSupported() || (PlatformSupport.isWindows() && (src == null || src.isBlank()))) {
                    String msg = PlatformSupport.isWindows()
                            ? "No playback capture device auto-detected. Enable Stereo Mix/virtual cable or set audio_loopback."
                            : "Playback capture unavailable (install pw-record/parec/ffmpeg).";
                    SwingUtilities.invokeLater(() -> { syncUi(null, msg); playbackBtn.setSelected(false); });
                    return;
                }
                CommandLoopbackCapture lb = new CommandLoopbackCapture(16000, v(settings.chunk_duration, 0.25f), src);
                live.startLoopback(lb);
                String label = src == null ? "default" : src;
                SwingUtilities.invokeLater(() -> syncUi(FloatingControlWindow.Mode.PLAYBACK_TRANSCRIBE, "Playback: " + shortPreview(label, 22)));
            } catch (Exception ex) {
                log.warn("playback start failed: {}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> { syncUi(null, "Playback failed: " + rootMsg(ex)); playbackBtn.setSelected(false); });
            }
        }, "gek-playback-start").start();
    }

    private void stopAllModes() {
        try { if (chatTyping != null) chatTyping.stop(); } catch (Exception ignored) {}
        try { dictation.stop(); } catch (Exception ignored) {}
        try { live.stop(); } catch (Exception ignored) {}
    }

    private void syncUi(FloatingControlWindow.Mode fmode, String msg) {
        status.setText(msg == null ? "Ready" : msg);
        if (fmode == null) {
            voiceBtn.setSelected(false); liveBtn.setSelected(false); playbackBtn.setSelected(false);
        }
        if (floatingWindow != null) {
            if (fmode == null) floatingWindow.clearActive(); else floatingWindow.setActiveMode(fmode);
            floatingWindow.setStatusText(msg);
        }
    }

    private void onStatus(FloatingControlWindow.Mode mode, String msg) {
        SwingUtilities.invokeLater(() -> {
            String ui = compactStatus(msg);
            status.setText(shortPreview(ui, 60));
            if (floatingWindow != null) {
                if (mode != null) floatingWindow.setActiveMode(mode);
                floatingWindow.setStatusText(shortPreview(ui.replace("[voice] ", "").replace("[audio] ", ""), 28));
            }
        });
    }

    /** Keep compact/floating UI free of transcript text; show operational state only. */
    private static String compactStatus(String msg) {
        if (msg == null) return "Ready";
        String s = msg.trim();
        boolean voice = s.startsWith("[voice] ");
        String body = s.replaceFirst("^\\[(voice|audio)\\]\\s*", "");
        String lower = body.toLowerCase();
        if (voice) {
            if (lower.startsWith("ready:")) return "[voice] Ready (in field)";
            if (lower.startsWith("injected:")) return "[voice] Ready (in field)";
            if (lower.startsWith("not-injected")) return "[voice] Not injected (target not secured)";
            if (lower.startsWith("command:")) return "[voice] " + body;
            if (lower.startsWith("command-failed")) return "[voice] " + body;
            if (lower.startsWith("discarded")) return "[voice] Discarded";
            if (lower.startsWith("translation unavailable") || lower.startsWith("translation failed")) return "[voice] " + body;
            if (lower.startsWith("error:")) return "[voice] Error";
            if (lower.startsWith("listening")) return "[voice] Listening";
            if (lower.startsWith("starting") || lower.startsWith("stopping") || lower.startsWith("stopped")) return "[voice] " + body;
            return "[voice] Active";
        }
        if (lower.startsWith("listening")) return "[audio] Listening";
        if (lower.startsWith("starting") || lower.startsWith("stopping") || lower.startsWith("stopped")) return "[audio] " + body;
        if (lower.startsWith("error:")) return "[audio] Error";
        if (body.startsWith("[") && body.contains("]")) return "[audio] Transcribing";
        return "[audio] " + (body.isBlank() ? "Active" : "Active");
    }

    private FloatingControlWindow.Mode activeLiveFloatingMode() {
        if (playbackBtn.isSelected()) return FloatingControlWindow.Mode.PLAYBACK_TRANSCRIBE;
        if (liveBtn.isSelected()) return FloatingControlWindow.Mode.LIVE_MIC;
        return null;
    }

    private void ensureFloating() {
        if (floatingWindow != null) return;
        floatingWindow = new FloatingControlWindow(this);
        floatingWindow.setModeAction(mode -> {
            if (mode == null) { stopAllModes(); syncUi(null, "Ready"); return; }
            switch (mode) {
                case VOICE_TYPING -> { voiceBtn.setSelected(true); onModePressed(Mode.VOICE_TYPING, true); }
                case LIVE_MIC -> { liveBtn.setSelected(true); onModePressed(Mode.LIVE_MIC, true); }
                case PLAYBACK_TRANSCRIBE -> { playbackBtn.setSelected(true); onModePressed(Mode.PLAYBACK_TRANSCRIBE, true); }
            }
        });
    }

    private void toggleHud() {
        if (hudWindow == null) hudWindow = new HudWindow(this);
        hudWindow.setHudOpacity(settings.hud_opacity != null ? settings.hud_opacity : 0.95);
        hudWindow.setVisible(!hudWindow.isVisible());
    }

    public void toggleDictationFromHotkey() {
        WindowsFocusManager.captureForeground();
        SwingUtilities.invokeLater(() -> {
            boolean activate = !voiceBtn.isSelected();
            voiceBtn.setSelected(activate);
            onModePressed(Mode.VOICE_TYPING, activate);
        });
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(30, 144, 255)); g.fillOval(1, 1, 14, 14); g.dispose();
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("Show Menu (avoid during typing)"); show.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); setState(Frame.NORMAL); }));
            MenuItem floatItem = new MenuItem("Floating Controls"); floatItem.addActionListener(e -> SwingUtilities.invokeLater(() -> { ensureFloating(); floatingWindow.setVisible(true); }));
            MenuItem toggle = new MenuItem("Toggle Voice Typing"); toggle.addActionListener(e -> toggleDictationFromHotkey());
            MenuItem quit = new MenuItem("Quit"); quit.addActionListener(e -> { shutdown(); dispose(); System.exit(0); });
            menu.add(show); menu.add(floatItem); menu.add(toggle); menu.addSeparator(); menu.add(quit);
            trayIcon = new TrayIcon(img, "GEK", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> SwingUtilities.invokeLater(() -> { ensureFloating(); if (floatingWindow != null) floatingWindow.setVisible(true); }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) { log.debug("Tray init failed: {}", e.getMessage()); }
    }

    private void shutdown() {
        try { if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) {}
        try { if (hotkey != null) hotkey.close(); } catch (Exception ignored) {}
        try { if (chatTyping != null) chatTyping.close(); } catch (Exception ignored) {}
        try { dictation.close(); } catch (Exception ignored) {}
        try { live.close(); } catch (Exception ignored) {}
    }

    private static float v(Double x, float d) { return x == null ? d : x.floatValue(); }
    private static String shortPreview(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)) + "…";
    }
    private static String rootMsg(Throwable t) {
        Throwable c = t; while (c != null && c.getCause() != null) c = c.getCause();
        if (c == null) return "error";
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
