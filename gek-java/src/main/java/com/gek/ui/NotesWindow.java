package com.gek.ui;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class NotesWindow extends JFrame {
    private final Path notesPath;
    private final JTextArea area = new JTextArea();

    public NotesWindow(Window owner, Path notesPath) {
        super("GEK - Notes");
        this.notesPath = notesPath;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(720, 520);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(6,6));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        add(new JScrollPane(area), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton reload = new JButton("Reload");
        JButton save = new JButton("Save");
        reload.addActionListener(e -> load());
        save.addActionListener(e -> save());
        buttons.add(reload); buttons.add(save);
        add(buttons, BorderLayout.SOUTH);
        load();
    }

    private void load() {
        try {
            if (Files.exists(notesPath)) area.setText(Files.readString(notesPath, StandardCharsets.UTF_8));
            else area.setText("");
        } catch (IOException e) { area.setText("Failed to load notes: " + e.getMessage()); }
    }

    private void save() {
        try {
            Files.createDirectories(notesPath.getParent());
            Files.writeString(notesPath, area.getText(), StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "Saved: " + notesPath);
        } catch (IOException e) { JOptionPane.showMessageDialog(this, "Save failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
    }
}
