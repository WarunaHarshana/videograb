package com.videograb.ui;

import com.videograb.model.Download;
import com.videograb.service.HistoryService;
import com.videograb.util.Constants;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Panel for viewing download history.
 */
public class HistoryPanel extends JPanel {
    private final HistoryService historyService;
    private List<Download> displayedHistory;
    private JTable historyTable;
    private DefaultTableModel tableModel;
    private JButton clearButton;
    private JButton exportButton;
    private JTextField searchField;
    private JLabel itemCountLabel;

    public HistoryPanel() {
        this(new HistoryService());
    }

    public HistoryPanel(HistoryService historyService) {
        this.historyService = historyService != null ? historyService : new HistoryService();
        this.displayedHistory = Collections.emptyList();

        initializePanel();
        setupLayout();
        setupEventListeners();
        loadHistoryData();
    }

    private void initializePanel() {
        setBorder(BorderFactory.createTitledBorder("Download History"));
        setLayout(new BorderLayout(10, 10));
    }

    private void setupLayout() {
        JPanel topPanel = new JPanel(new BorderLayout(10, 0));

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        searchPanel.add(new JLabel("Search:"));
        searchField = new JTextField(20);
        searchPanel.add(searchField);
        topPanel.add(searchPanel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        exportButton = new JButton("Export");
        clearButton = new JButton("Clear History");
        buttonPanel.add(exportButton);
        buttonPanel.add(clearButton);
        topPanel.add(buttonPanel, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);

        String[] columnNames = {"Date", "Title", "URL", "Status", "Size"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        historyTable = new JTable(tableModel);
        historyTable.setFillsViewportHeight(true);
        historyTable.getTableHeader().setReorderingAllowed(false);

        JScrollPane tableScrollPane = new JScrollPane(historyTable);
        add(tableScrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        itemCountLabel = new JLabel("0 items");
        bottomPanel.add(itemCountLabel);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void setupEventListeners() {
        clearButton.addActionListener(e -> clearHistory());
        exportButton.addActionListener(e -> exportHistory());

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterHistory();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterHistory();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterHistory();
            }
        });

        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    showDownloadDetails();
                }
            }
        });
    }

    private void loadHistoryData() {
        List<Download> history = historyService.getHistory();
        displayedHistory = new ArrayList<>();
        tableModel.setRowCount(0);

        for (int i = history.size() - 1; i >= 0; i--) {
            Download download = history.get(i);
            tableModel.addRow(toRow(download));
            displayedHistory.add(download);
        }

        updateCountLabel();
    }

    private void filterHistory() {
        String searchText = searchField.getText().toLowerCase().trim();
        if (searchText.isEmpty()) {
            loadHistoryData();
            return;
        }

        List<Download> allHistory = historyService.getHistory();
        displayedHistory = new ArrayList<>();
        tableModel.setRowCount(0);

        for (int i = allHistory.size() - 1; i >= 0; i--) {
            Download download = allHistory.get(i);
            boolean matches =
                (download.getTitle() != null && download.getTitle().toLowerCase().contains(searchText)) ||
                (download.getUrl() != null && download.getUrl().toLowerCase().contains(searchText)) ||
                (download.getStatus() != null && download.getStatus().toLowerCase().contains(searchText));

            if (matches) {
                tableModel.addRow(toRow(download));
                displayedHistory.add(download);
            }
        }

        updateCountLabel();
    }

    private Object[] toRow(Download download) {
        String date = "Unknown";
        if (download.getAddedAt() != null) {
            String raw = download.getAddedAt().toString();
            date = raw.length() >= 19 ? raw.substring(0, 19) : raw;
        }

        return new Object[] {
            date,
            download.getTitle() != null ? download.getTitle() : "Unknown Title",
            download.getUrl() != null ? download.getUrl() : "Unknown URL",
            download.getStatus() != null ? download.getStatus() : "Unknown",
            download.getSize() != null ? download.getSize() : "Unknown Size"
        };
    }

    private void clearHistory() {
        int result = JOptionPane.showConfirmDialog(
            SwingUtilities.getWindowAncestor(this),
            "Are you sure you want to clear all download history?\nThis action cannot be undone.",
            "Confirm Clear History",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            historyService.clearHistory();
            loadHistoryData();
            JOptionPane.showMessageDialog(
                this,
                "Download history cleared successfully",
                "History Cleared",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    private void exportHistory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export History");
        chooser.setSelectedFile(new java.io.File("videograb_history.json"));

        int result = chooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            java.io.File selectedFile = chooser.getSelectedFile();
            try {
                historyService.saveHistory();
                java.nio.file.Files.copy(
                    historyService.getHistoryPath(),
                    selectedFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );
                JOptionPane.showMessageDialog(
                    this,
                    "History exported successfully to:\n" + selectedFile.getAbsolutePath(),
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE
                );
            } catch (Exception e) {
                JOptionPane.showMessageDialog(
                    this,
                    "Error exporting history: " + e.getMessage(),
                    "Export Error",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    private void showDownloadDetails() {
        int selectedRow = historyTable.getSelectedRow();
        if (selectedRow < 0) {
            return;
        }

        int modelIndex = historyTable.convertRowIndexToModel(selectedRow);
        if (modelIndex < 0 || modelIndex >= displayedHistory.size()) {
            return;
        }

        Download download = displayedHistory.get(modelIndex);
        StringBuilder details = new StringBuilder();
        details.append("Title: ").append(download.getTitle() != null ? download.getTitle() : "Unknown").append("\n");
        details.append("URL: ").append(download.getUrl() != null ? download.getUrl() : "Unknown").append("\n");
        details.append("Status: ").append(download.getStatus() != null ? download.getStatus() : "Unknown").append("\n");
        details.append("Added: ").append(download.getAddedAt() != null ? download.getAddedAt().toString() : "Unknown").append("\n");
        if (download.getStartedAt() != null) {
            details.append("Started: ").append(download.getStartedAt().toString()).append("\n");
        }
        if (download.getCompletedAt() != null) {
            details.append("Completed: ").append(download.getCompletedAt().toString()).append("\n");
        }
        details.append("Size: ").append(download.getSize() != null ? download.getSize() : "Unknown").append("\n");
        details.append("Format: ").append(download.getFormat() != null ? download.getFormat() : "Unknown").append("\n");
        if (download.getErrorMessage() != null && !download.getErrorMessage().isEmpty()) {
            details.append("Error: ").append(download.getErrorMessage()).append("\n");
        }

        JOptionPane.showMessageDialog(
            this,
            details.toString(),
            "Download Details",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void updateCountLabel() {
        int count = tableModel.getRowCount();
        itemCountLabel.setText(count + (count == 1 ? " item" : " items"));
    }

    /**
     * Refresh table data from persisted history.
     */
    public void refreshHistory() {
        historyService.loadHistory();
        loadHistoryData();
    }
}
