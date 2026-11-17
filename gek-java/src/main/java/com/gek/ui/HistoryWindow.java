package com.gek.ui;

import com.gek.db.HistoryRepository;
import com.gek.db.TranslationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

public class HistoryWindow extends JFrame {
    private static final Logger log = LoggerFactory.getLogger(HistoryWindow.class);
    private final HistoryRepository repo;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"ID","Time","Mode","Src","Tgt","Original","Translated"}, 0) {
        @Override public boolean isCellEditable(int r,int c){ return false; }
    };
    private final JTextField searchField = new JTextField(24);
    private JTable tableRef;

    public HistoryWindow(Window owner, HistoryRepository repo) {
        super("GEK - History");
        this.repo = repo;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(980, 520);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(6,6));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Search:"));
        top.add(searchField);
        JButton searchBtn = new JButton("Search");
        JButton allBtn = new JButton("Recent");
        JButton delBtn = new JButton("Delete Selected");
        JButton clearBtn = new JButton("Clear All");
        JButton copyBtn = new JButton("Copy Selected");
        searchBtn.addActionListener(e -> doSearch());
        searchField.addActionListener(e -> doSearch());
        allBtn.addActionListener(e -> loadRecent());
        delBtn.addActionListener(e -> deleteSelected());
        clearBtn.addActionListener(e -> clearAll());
        copyBtn.addActionListener(e -> copySelected());
        top.add(searchBtn); top.add(allBtn); top.add(copyBtn); top.add(delBtn); top.add(clearBtn);
        add(top, BorderLayout.NORTH);

        JTable table = new JTable(model);
        this.tableRef = table;
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(5).setPreferredWidth(360);
        table.getColumnModel().getColumn(6).setPreferredWidth(360);
        add(new JScrollPane(table), BorderLayout.CENTER);

        loadRecent();
    }

    private void loadRecent() { load(repoTry(() -> repo.recent(500))); }
    private void doSearch() {
        String q = searchField.getText();
        if (q == null || q.isBlank()) { loadRecent(); return; }
        load(repoTry(() -> repo.search(q.trim(), 500)));
    }
    private void load(List<TranslationRecord> list) {
        model.setRowCount(0);
        if (list == null) return;
        for (TranslationRecord r : list) {
            model.addRow(new Object[]{r.id(), r.timestamp(), nz(r.sourceName()), nz(r.originalLang()), nz(r.translatedLang()), nz(r.originalText()), nz(r.translatedText())});
        }
    }
    private void deleteSelected() {
        JTable table = findTable();
        if (table == null) return;
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            JOptionPane.showMessageDialog(this, "Select one or more rows to delete.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Delete " + viewRows.length + " selected record(s)?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        try {
            for (int vr : viewRows) {
                int mr = table.convertRowIndexToModel(vr);
                Object idObj = model.getValueAt(mr, 0);
                if (idObj instanceof Number n) repo.delete(n.longValue());
            }
            if (searchField.getText() != null && !searchField.getText().isBlank()) doSearch(); else loadRecent();
        } catch (Exception e) {
            log.warn("delete selected: {}", e.getMessage());
            JOptionPane.showMessageDialog(this, "Delete failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JTable findTable() { return tableRef; }
    private void clearAll() {
        int ok = JOptionPane.showConfirmDialog(this, "Clear all history?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        try { repo.clear(); loadRecent(); } catch (Exception e) { log.warn("clear: {}", e.getMessage()); JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
    }

    private void copySelected() {
        JTable table = findTable();
        if (table == null) return;
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) { JOptionPane.showMessageDialog(this, "Select one or more rows to copy."); return; }
        StringBuilder sb = new StringBuilder();
        for (int vr : viewRows) {
            int mr = table.convertRowIndexToModel(vr);
            String orig = nz(String.valueOf(model.getValueAt(mr, 5)));
            String tr = nz(String.valueOf(model.getValueAt(mr, 6)));
            if (!sb.isEmpty()) sb.append("\n---\n");
            sb.append(orig);
            if (!tr.isBlank() && !tr.equals(orig)) sb.append("\n=> ").append(tr);
        }
        try {
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(sb.toString());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
            JOptionPane.showMessageDialog(this, "Copied " + viewRows.length + " record(s) to clipboard.");
        } catch (Exception e) {
            log.warn("copy: {}", e.getMessage());
            JOptionPane.showMessageDialog(this, "Copy failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    private static String nz(String s){ return s==null?"":s; }
    private interface Op<T>{ T run() throws Exception; }
    private <T> T repoTry(Op<T> op){ try { return op.run(); } catch(Exception e){ log.warn("history op: {}", e.getMessage()); return null; } }
}
