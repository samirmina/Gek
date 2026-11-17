package com.gek.ui;

import com.gek.app.DictationController;
import com.gek.app.LiveTranslateController;
import com.gek.audio.AudioDevices;
import com.gek.audio.AudioSource;
import com.gek.audio.NoopLoopbackCapture;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.core.PlatformSupport;
import com.gek.core.SettingsStore;
import com.gek.db.HistoryRepository;
import com.gek.audio.AppDucker;
import com.gek.audio.TtsEngine;
import com.gek.inference.SidecarClient;
import com.gek.inject.GlobalHotkey;
import com.gek.inject.TextInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class ControlWindow extends JFrame {
    private static final Logger log = LoggerFactory.getLogger(ControlWindow.class);
    private final AppPaths paths;
    private final AppSettings settings;
    private final SettingsStore store;
    private final DictationController dictation;
    private final LiveTranslateController live;
    private final SidecarClient sidecar;
    private final GlobalHotkey hotkey;
    private final HistoryRepository history;
    private final PlatformSupport.Capabilities caps;
    private final TextInjector injector;
    private final TtsEngine ttsEngine;
    private final AppDucker ducker;
    private final JLabel status = new JLabel("Ready");
    private final JTextArea logArea = new JTextArea(12, 80);
    private final JButton dictBtn = new JButton("Start Dictation");
    private final JButton liveBtn = new JButton("Start Live (mic)");
    private final JComboBox<String> micBox = new JComboBox<>();
    private final JComboBox<String> liveSourceBox = new JComboBox<>(new String[]{"mic","loopback"});
    private final JTextField hotkeyField;
    private final JComboBox<String> insertMode;
    private final JCheckBox ttsEnabledBox = new JCheckBox("TTS enabled");
    private final JCheckBox duckingBox = new JCheckBox("Auto duck/restore (platform-limited)");
    private HudWindow hudWindow;
    private TrayIcon trayIcon;

    public ControlWindow(AppPaths paths, AppSettings settings, SettingsStore store,
                         DictationController dictation, LiveTranslateController live,
                         SidecarClient sidecar, GlobalHotkey hotkey, HistoryRepository history,
                         PlatformSupport.Capabilities caps, TextInjector injector, TtsEngine ttsEngine, AppDucker ducker) {
        super("GEK Java");
        this.paths = paths; this.settings = settings; this.store = store;
        this.dictation = dictation; this.live = live; this.sidecar = sidecar; this.hotkey = hotkey; this.history = history;
        this.caps = caps; this.injector = injector; this.ttsEngine = ttsEngine; this.ducker = ducker;
        this.hotkeyField = new JTextField(nvl(settings.dictation_hotkey, "CTRL+SHIFT+SPACE"), 12);
        this.insertMode = new JComboBox<>(new String[]{"transcript", "translated"});
        insertMode.setSelectedItem(nvl(settings.dictation_insert_mode, "transcript"));

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(1, 3, 1, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        micBox.setPrototypeDisplayValue("input-device-name");
        liveSourceBox.setPrototypeDisplayValue("loopback");
        hotkeyField.setColumns(10);
        hotkeyField.setToolTipText("Global dictation hotkey");

        int r = 0;
        c.gridy = r; c.weightx = 0; c.gridx = 0; top.add(compactLabel("Status"), c);
        c.gridx = 1; c.weightx = 1; status.setFont(status.getFont().deriveFont(Font.BOLD, 12f));
        status.setToolTipText("Current runtime status"); top.add(status, c);
        c.gridx = 2; c.weightx = 0; top.add(compactLabel("Mode"), c);
        c.gridx = 3; c.weightx = 0.6; top.add(compactLabel(platformSummary()), c); r++;

        c.gridy = r; c.gridx = 0; c.weightx = 0; top.add(compactLabel("Mic"), c);
        c.gridx = 1; c.weightx = 1; populateMics(); top.add(micBox, c);
        c.gridx = 2; c.weightx = 0; top.add(compactLabel("Live"), c);
        c.gridx = 3; c.weightx = 0.6; liveSourceBox.setToolTipText("Live input source"); top.add(liveSourceBox, c); r++;

        c.gridy = r; c.gridx = 0; c.weightx = 0; top.add(compactLabel("Hotkey"), c);
        c.gridx = 1; c.weightx = 1; top.add(hotkeyField, c);
        c.gridx = 2; c.weightx = 0; top.add(compactLabel("Insert"), c);
        c.gridx = 3; c.weightx = 0.6; insertMode.setToolTipText("Dictation insert mode"); top.add(insertMode, c); r++;

        ttsEnabledBox.setSelected(Boolean.TRUE.equals(settings.tts_enabled));
        duckingBox.setSelected(Boolean.TRUE.equals(settings.auto_mute_during_tts));
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        flags.setOpaque(false);
        flags.add(ttsEnabledBox); flags.add(duckingBox);
        flags.add(compactLabel("Inject: " + (injector != null ? injector.name() : "none")));
        flags.add(compactLabel("TTS: " + (ttsEngine != null ? ttsEngine.getClass().getSimpleName() : "none")));
        c.gridy = r; c.gridx = 0; c.gridwidth = 4; c.weightx = 1; top.add(flags, c); r++;

        JLabel pathsLabel = compactLabel("Root: " + shortenPath(paths.projectRoot()) + "  •  DB: " + paths.dbPath().getFileName());
        pathsLabel.setToolTipText("Project: " + paths.projectRoot() + " | Models: " + paths.modelsDir() + " | DB: " + paths.dbPath());
        c.gridy = r; c.gridx = 0; c.gridwidth = 4; c.weightx = 1; top.add(pathsLabel, c); r++;

        JLabel wsl2 = compactLabel("WSL2: WSLg clipboard/paste may work; no Windows-global hotkey/inject, WASAPI loopback, or app ducking. Live loopback = Linux/Pulse/PipeWire/ffmpeg only.");
        wsl2.setForeground(new Color(95, 95, 95));
        wsl2.setToolTipText("Captures Linux-side audio sources when available. Full Windows-host parity requires a Windows session.");
        c.gridy = r; c.gridx = 0; c.gridwidth = 4; c.weightx = 1; top.add(wsl2, c);
        add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setRows(8);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        add(logScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        dictBtn.addActionListener(e -> toggleDictation());
        liveBtn.addActionListener(e -> toggleLive());
        JButton hudBtn = new JButton("HUD");
        JButton warmBtn = new JButton("Warmup");
        JButton pingBtn = new JButton("Ping");
        JButton saveBtn = new JButton("Save");
        JButton historyBtn = new JButton("History");
        JButton notesBtn = new JButton("Notes");
        JButton settingsBtn = new JButton("Settings");
        hudBtn.addActionListener(e -> toggleHud());
        warmBtn.addActionListener(e -> warmup());
        pingBtn.addActionListener(e -> pingSidecar());
        saveBtn.addActionListener(e -> { saveSettings(); appendStatus("Settings saved."); });
        historyBtn.addActionListener(e -> new HistoryWindow(this, history).setVisible(true));
        notesBtn.addActionListener(e -> new NotesWindow(this, paths.dataDir().resolve("notes.txt")).setVisible(true));
        settingsBtn.addActionListener(e -> { new SettingsWindow(this, paths, settings, store).setVisible(true); syncFromSettings(); });
        for (AbstractButton b : new AbstractButton[]{dictBtn, liveBtn, hudBtn, warmBtn, pingBtn, saveBtn, historyBtn, notesBtn, settingsBtn}) {
            b.setMargin(new Insets(2, 8, 2, 8));
        }
        if (sidecar == null) {
            warmBtn.setEnabled(false);
            pingBtn.setEnabled(false);
            warmBtn.setToolTipText("Sidecar not enabled (default is Java-native mode). Use --use-sidecar or set inference_mode=sidecar for temporary fallback.");
            pingBtn.setToolTipText("Sidecar not enabled (default is Java-native mode). Use --use-sidecar or set inference_mode=sidecar for temporary fallback.");
        }
        buttons.add(dictBtn); buttons.add(liveBtn); buttons.add(hudBtn); buttons.add(warmBtn); buttons.add(pingBtn); buttons.add(saveBtn); buttons.add(historyBtn); buttons.add(notesBtn); buttons.add(settingsBtn);
        add(buttons, BorderLayout.SOUTH);

        dictation.setStatusListener(s -> appendStatus("[dictation] " + s));
        live.setStatusListener(s -> appendStatus("[live] " + s));
        live.setTranscriptListener((o,t,m,src) -> {
            appendStatus(m + " [" + src + "] | " + o + " => " + t);
            if (hudWindow != null && hudWindow.isVisible()) hudWindow.showTranscript(o, t, m + " (" + src + ")");
        });

        setupTray();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (trayIcon != null) setVisible(false); else shutdown(); }
            @Override public void windowClosed(WindowEvent e) { shutdown(); }
        });
        pack();
        Dimension pref = getPreferredSize();
        setSize(Math.min(Math.max(pref.width, 760), 920), Math.min(Math.max(pref.height, 360), 520));
        setMinimumSize(new Dimension(680, 320));
        setLocationRelativeTo(null);
    }

    private String platformSummary() {
        String os = String.valueOf(System.getProperty("os.name"));
        if (os.length() > 18) os = os.substring(0, 18);
        return os + (caps.wsl() ? " (WSL)" : "") + (caps.wslg() ? "+WSLg" : "") + (caps.gui() ? " gui" : " headless-env");
    }

    private static JLabel compactLabel(String text) {
        JLabel l = new JLabel(text == null ? "" : text);
        l.setFont(l.getFont().deriveFont(11f));
        return l;
    }

    private static String shortenPath(Object path) {
        String s = path == null ? "" : String.valueOf(path);
        if (s.length() <= 48) return s;
        return "…" + s.substring(s.length() - 47);
    }

    private void populateMics() {
        micBox.removeAllItems(); micBox.addItem("");
        try { for (String n : AudioDevices.inputDeviceNames()) micBox.addItem(n); } catch (Throwable t) { log.debug("mic enum: {}", t.getMessage()); }
        if (settings.audio_mic != null) micBox.setSelectedItem(settings.audio_mic);
    }
    private void syncFromSettings() {
        hotkeyField.setText(nvl(settings.dictation_hotkey, "CTRL+SHIFT+SPACE"));
        insertMode.setSelectedItem(nvl(settings.dictation_insert_mode, "transcript"));
        if (settings.audio_mic != null) micBox.setSelectedItem(settings.audio_mic);
        ttsEnabledBox.setSelected(Boolean.TRUE.equals(settings.tts_enabled));
        duckingBox.setSelected(Boolean.TRUE.equals(settings.auto_mute_during_tts));
    }

    private void appendStatus(String s) {
        SwingUtilities.invokeLater(() -> {
            status.setText(s.length()>140?s.substring(0,137)+"...":s);
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void saveSettings() {
        settings.audio_mic = String.valueOf(micBox.getSelectedItem());
        settings.dictation_hotkey = hotkeyField.getText();
        settings.dictation_insert_mode = String.valueOf(insertMode.getSelectedItem());
        settings.tts_enabled = ttsEnabledBox.isSelected();
        settings.auto_mute_during_tts = duckingBox.isSelected();
        store.save(settings);
    }

    private void toggleHud() {
        if (hudWindow == null) hudWindow = new HudWindow(this);
        hudWindow.setHudOpacity(settings.hud_opacity != null ? settings.hud_opacity : 0.95);
        if (hudWindow.isVisible()) hudWindow.setVisible(false);
        else hudWindow.setVisible(true);
    }

    private void toggleDictation() {
        saveSettings();
        if (dictation.isRunning()) { dictation.stop(); dictBtn.setText("Start Dictation"); }
        else {
            dictBtn.setEnabled(false); dictBtn.setText("Starting...");
            new Thread(() -> {
                try { dictation.start(); SwingUtilities.invokeLater(() -> { dictBtn.setText("Stop Dictation"); dictBtn.setEnabled(true); }); }
                catch (Exception ex) { log.error("start dictation", ex); SwingUtilities.invokeLater(() -> { appendStatus("Start failed: " + ex.getMessage()); dictBtn.setText("Start Dictation"); dictBtn.setEnabled(true); }); }
            }, "gek-dictation-start").start();
        }
    }

    private void toggleLive() {
        saveSettings();
        if (live.isRunning()) { live.stop(); liveBtn.setText("Start Live (mic)"); return; }
        liveBtn.setEnabled(false); liveBtn.setText("Starting...");
        String src = String.valueOf(liveSourceBox.getSelectedItem());
        new Thread(() -> {
            try {
                if ("loopback".equalsIgnoreCase(src)) {
                    if (com.gek.audio.CommandLoopbackCapture.isSupported()) {
                        com.gek.audio.CommandLoopbackCapture lb = new com.gek.audio.CommandLoopbackCapture(16000, v(settings.chunk_duration, 0.25f), settings.audio_loopback);
                        live.startLoopback(lb);
                        appendStatus("Live loopback started via " + lb.name() + " (Linux monitor; captures Linux-side audio, not Windows host).");
                    } else {
                        appendStatus("Loopback capture unavailable on this system (no Pulse/PipeWire/ffmpeg recorder found). Install pw-record/parec/ffmpeg and ensure a monitor source. Falling back to mic.");
                        live.startMic();
                    }
                } else {
                    live.startMic();
                }
                SwingUtilities.invokeLater(() -> { liveBtn.setText("Stop Live"); liveBtn.setEnabled(true); });
            } catch (Exception ex) {
                log.error("start live", ex);
                SwingUtilities.invokeLater(() -> { appendStatus("Start failed: " + ex.getMessage()); liveBtn.setText("Start Live (mic)"); liveBtn.setEnabled(true); });
            }
        }, "gek-live-start").start();
    }
    private static float v(Double x,float d){return x==null?d:x.floatValue();}

    private void warmup() {
        if (sidecar == null) { appendStatus("Sidecar not enabled (Java-native mode). Use --use-sidecar or set inference_mode=sidecar for temporary fallback."); return; }
        saveSettings();
        appendStatus("Warming sidecar (loads existing local models). Python ML deps must be installed for real inference.");
        new Thread(() -> {
            try {
                sidecar.start();
                com.fasterxml.jackson.databind.JsonNode r = sidecar.warmupDetailed().join();
                boolean w = r.path("whisper").asBoolean(false);
                boolean a = r.path("argos").asBoolean(false);
                boolean d = r.path("diarizer").asBoolean(false);
                com.fasterxml.jackson.databind.JsonNode errs = r.path("errors");
                String msg = "Sidecar warmup: whisper=" + w + " argos=" + a + " diarizer=" + d;
                if (errs != null && errs.isObject() && errs.size() > 0) {
                    java.util.List<String> parts = new java.util.ArrayList<>();
                    errs.fieldNames().forEachRemaining(k -> parts.add(k + ": " + errs.path(k).asText()));
                    msg += " errors={" + String.join("; ", parts) + "}";
                }
                appendStatus(msg);
            } catch (Exception e) { log.warn("warmup failed", e); appendStatus("Warmup error: " + rootMsg(e)); }
        }, "gek-warmup").start();
    }

    private void pingSidecar() {
        if (sidecar == null) { appendStatus("Sidecar not enabled (Java-native mode). Use --use-sidecar or set inference_mode=sidecar for temporary fallback."); return; }
        appendStatus("Pinging sidecar...");
        new Thread(() -> {
            try { boolean ok = sidecar.ping().join(); appendStatus(ok ? "Sidecar ping OK." : "Sidecar ping failed."); }
            catch (Exception e) { appendStatus("Sidecar ping error: " + rootMsg(e)); }
        }, "gek-ping").start();
    }

    public void toggleDictationFromHotkey() { SwingUtilities.invokeLater(this::toggleDictation); }

    private void setupTray() {
        if (!SystemTray.isSupported()) { appendStatus("System tray not supported in this environment."); return; }
        try {
            Image img = new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = (Graphics2D) img.getGraphics(); g.setColor(new Color(30,144,255)); g.fillOval(1,1,14,14); g.dispose();
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("Show"); show.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); setState(Frame.NORMAL); toFront(); }));
            MenuItem toggle = new MenuItem("Toggle Dictation"); toggle.addActionListener(e -> toggleDictationFromHotkey());
            MenuItem quit = new MenuItem("Quit"); quit.addActionListener(e -> { shutdown(); dispose(); System.exit(0); });
            menu.add(show); menu.add(toggle); menu.addSeparator(); menu.add(quit);
            trayIcon = new TrayIcon(img, "GEK Java", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); toFront(); }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) { appendStatus("Tray init failed: " + e.getMessage()); }
    }

    private void shutdown() {
        try { if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) {}
        try { if (hotkey!=null) hotkey.close(); } catch (Exception ignored) {}
        try { dictation.close(); } catch (Exception ignored) {}
        try { live.close(); } catch (Exception ignored) {}
    }
    private static String nvl(String v,String d){ return (v==null||v.isBlank())?d:v; }
    private static String rootMsg(Exception e){ Throwable c = e; while (c.getCause()!=null) c=c.getCause(); return c.getMessage()==null?c.toString():c.getMessage(); }
}
