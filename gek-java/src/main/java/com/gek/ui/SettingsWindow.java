package com.gek.ui;

import com.gek.audio.AudioDevices;
import com.gek.core.AppPaths;
import com.gek.core.AppSettings;
import com.gek.core.SettingsStore;

import javax.swing.*;
import java.awt.*;

public class SettingsWindow extends JDialog {
    private final AppSettings s;
    private final SettingsStore store;
    private final JTextField whisperModel = new JTextField(12);
    private final JTextField whisperDevice = new JTextField(8);
    private final JTextField whisperCompute = new JTextField(8);
    private final JTextField sourceLang = new JTextField(8);
    private final JTextField targetLang = new JTextField(8);
    private final JTextField hotkey = new JTextField(16);
    private final JComboBox<String> insertMode = new JComboBox<>(new String[]{"transcript","translated"});
    private final JComboBox<String> mic = new JComboBox<>();
    private final JTextField sidecarPython = new JTextField(24);
    private final JComboBox<String> inferenceMode = new JComboBox<>(new String[]{"native","sidecar"});
    private final JCheckBox stt = new JCheckBox("STT enabled");
    private final JCheckBox tts = new JCheckBox("TTS enabled");
    private final JCheckBox diar = new JCheckBox("Diarization enabled");

    public SettingsWindow(Window owner, AppPaths paths, AppSettings s, SettingsStore store) {
        super(owner, "GEK - Settings", ModalityType.APPLICATION_MODAL);
        this.s = s; this.store = store;
        setSize(640, 420); setLocationRelativeTo(owner);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        GridBagConstraints c = new GridBagConstraints(); c.insets=new Insets(4,4,4,4); c.anchor=GridBagConstraints.WEST; c.fill=GridBagConstraints.HORIZONTAL;
        int r=0;
        addRow(form,c,r++,"Whisper model", whisperModel, s.whisper_model);
        addRow(form,c,r++,"Whisper device", whisperDevice, s.whisper_device);
        addRow(form,c,r++,"Compute type", whisperCompute, s.whisper_compute_type);
        addRow(form,c,r++,"Source lang (auto/xx)", sourceLang, nz(s.source_language,"auto"));
        addRow(form,c,r++,"Target lang", targetLang, nz(s.target_language,"en"));
        addRow(form,c,r++,"Dictation hotkey", hotkey, nz(s.dictation_hotkey,"CTRL+SHIFT+SPACE"));
        c.gridx=0;c.gridy=r; form.add(new JLabel("Dictation insert"),c); c.gridx=1; insertMode.setSelectedItem(nz(s.dictation_insert_mode,"transcript")); form.add(insertMode,c); r++;
        mic.addItem(""); try { for (String n: AudioDevices.inputDeviceNames()) mic.addItem(n);} catch(Exception ignored){} if(s.audio_mic!=null) mic.setSelectedItem(s.audio_mic);
        c.gridx=0;c.gridy=r; form.add(new JLabel("Mic device"),c); c.gridx=1; form.add(mic,c); r++;
        c.gridx=0;c.gridy=r; form.add(new JLabel("Inference mode"),c); c.gridx=1; inferenceMode.setSelectedItem(nz(s.inference_mode,"native")); form.add(inferenceMode,c); r++;
        addRow(form,c,r++,"Sidecar Python (override)", sidecarPython, nz(s.sidecar_python,""));
        stt.setSelected(Boolean.TRUE.equals(s.stt_enabled)); tts.setSelected(Boolean.TRUE.equals(s.tts_enabled)); diar.setSelected(Boolean.TRUE.equals(s.diarization_enabled));
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT,8,0)); flags.add(stt); flags.add(tts); flags.add(diar);
        c.gridx=0;c.gridy=r; c.gridwidth=2; form.add(flags,c); r++;

        c.gridx=0;c.gridy=r; c.gridwidth=2; form.add(new JLabel("Models dir: "+paths.modelsDir()),c); r++;
        c.gridx=0;c.gridy=r; c.gridwidth=2; form.add(new JLabel("DB: "+paths.dbPath()+"  (changes persist to resources/data/settings.json)"),c);

        add(new JScrollPane(form), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton save = new JButton("Save"); JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> { apply(); store.save(s); dispose(); });
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel); buttons.add(save);
        add(buttons, BorderLayout.SOUTH);
    }
    private void addRow(JPanel p, GridBagConstraints c, int r, String label, JTextField f, String val){
        c.gridwidth=1; c.gridx=0; c.gridy=r; p.add(new JLabel(label),c); c.gridx=1; f.setText(val==null?"":val); p.add(f,c);
    }
    private void apply(){
        s.whisper_model = t(whisperModel.getText(), s.whisper_model);
        s.whisper_device = t(whisperDevice.getText(), s.whisper_device);
        s.whisper_compute_type = t(whisperCompute.getText(), s.whisper_compute_type);
        s.source_language = t(sourceLang.getText(), "auto");
        s.target_language = t(targetLang.getText(), "en");
        s.dictation_hotkey = t(hotkey.getText(), s.dictation_hotkey);
        s.dictation_insert_mode = String.valueOf(insertMode.getSelectedItem());
        s.audio_mic = String.valueOf(mic.getSelectedItem());
        s.inference_mode = String.valueOf(inferenceMode.getSelectedItem());
        s.sidecar_python = sidecarPython.getText()==null?"":sidecarPython.getText().trim();
        s.stt_enabled = stt.isSelected(); s.tts_enabled = tts.isSelected(); s.diarization_enabled = diar.isSelected();
    }
    private static String t(String v, String d){ if(v==null) return d; String x=v.trim(); return x.isEmpty()?d:x; }
    private static String nz(String v, String d){ return (v==null||v.isBlank())?d:v; }
}
