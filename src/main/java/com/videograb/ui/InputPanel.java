package com.videograb.ui;

import com.videograb.service.DownloadService;
import com.videograb.service.ConfigService;
import com.videograb.model.Download;
import com.videograb.util.URLDetector;
import com.videograb.util.Constants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.DataFlavor;
import java.net.URI;
import java.net.URISyntaxException;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel for URL input and download options
 */
public class InputPanel extends JPanel {
    private final DownloadService downloadService;
    private final ConfigService configService;
    private final JTextField urlField;
    private final JCheckBox batchCheckbox;
    private final JButton browseButton;
    private final JTextField pathField;
    private final JButton downloadButton;
    private final JComboBox<String> formatCombo;
    private final JCheckBox cookiesCheckbox;
    private final JComboBox<String> browserCombo;
    private final JCheckBox playlistCheckbox;
    private final JButton advancedSettingsButton;
    private final JPanel advancedSettingsPanel;
    private final JComboBox<String> outputFormatCombo;
    private final JTextField speedLimitField;
    private final JCheckBox subtitleCheckbox;
    private final JTextField subtitleLangsField;
    private final JTextField proxyField;
    private final JCheckBox noCertCheckCheckbox;
    private boolean advancedVisible = false;

    public InputPanel(DownloadService downloadService) {
        this.downloadService = downloadService;
        this.configService = new ConfigService();
        
        // Initialize components
        this.urlField = new JTextField(40);
        this.batchCheckbox = new JCheckBox("Batch Mode (URLs separated by new lines)");
        this.browseButton = new JButton("Browse");
        this.pathField = new JTextField(30);
        this.downloadButton = new JButton("Download");
        this.formatCombo = new JComboBox<>(new String[]{
            "Best Quality (Recommended)",
            "1080p",
            "720p", 
            "480p",
            "Audio Only",
            "Best Video+Audio"
        });
        this.cookiesCheckbox = new JCheckBox("Use Browser Cookies");
        this.browserCombo = new JComboBox<>(new String[]{
            "Chrome",
            "Firefox", 
            "Edge",
            "Opera",
            "Brave"
        });
        this.playlistCheckbox = new JCheckBox("Download Full Playlist");
        this.advancedSettingsButton = new JButton("Advanced Settings");
        this.advancedSettingsPanel = new JPanel();
        this.outputFormatCombo = new JComboBox<>(new String[]{"mp4", "mkv", "webm"});
        this.speedLimitField = new JTextField(12);
        this.subtitleCheckbox = new JCheckBox("Download Subtitles");
        this.subtitleLangsField = new JTextField(8);
        this.proxyField = new JTextField(18);
        this.noCertCheckCheckbox = new JCheckBox("Skip Certificate Validation");
        
        // Setup panel
        initializePanel();
        setupLayout();
        setupEventListeners();
        loadSettings();
    }

    /**
     * Initialize panel properties
     */
    private void initializePanel() {
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        setBackground(new Color(245, 247, 249));
        setLayout(new BorderLayout(10, 10));
        
        // Set placeholder text for URL field
        urlField.setPlaceholder("Enter video URL (YouTube, Vimeo, TikTok, etc.) or paste multiple URLs for batch mode");
        
        // Set default path
        pathField.setText(System.getProperty("user.home") + "/Downloads");
        pathField.setEditable(false);
        
        // Disable browser combo initially
        browserCombo.setEnabled(false);

        batchCheckbox.setOpaque(false);
        cookiesCheckbox.setOpaque(false);
        playlistCheckbox.setOpaque(false);
        subtitleCheckbox.setOpaque(false);
        noCertCheckCheckbox.setOpaque(false);

        downloadButton.setBackground(new Color(77, 140, 187));
        downloadButton.setForeground(Color.WHITE);
        downloadButton.setFocusPainted(false);
        downloadButton.setPreferredSize(new Dimension(170, 42));

        // FlatLaf styling hints for rounded, modern controls.
        downloadButton.putClientProperty("JButton.buttonType", "roundRect");
        downloadButton.putClientProperty("JComponent.arc", 999);
        urlField.putClientProperty("JComponent.arc", 18);
        pathField.putClientProperty("JComponent.arc", 14);
        formatCombo.putClientProperty("JComponent.arc", 14);
        browseButton.putClientProperty("JComponent.arc", 14);
        advancedSettingsButton.putClientProperty("JButton.buttonType", "roundRect");
        advancedSettingsButton.putClientProperty("JComponent.arc", 14);

        urlField.setPreferredSize(new Dimension(urlField.getPreferredSize().width, 40));
        pathField.setPreferredSize(new Dimension(pathField.getPreferredSize().width, 38));
        formatCombo.setPreferredSize(new Dimension(230, 38));

        // Initially hide advanced settings
        advancedSettingsPanel.setVisible(false);
        subtitleLangsField.setText("en");
        speedLimitField.setPlaceholder("e.g. 1M or 500K");
        proxyField.setPlaceholder("http://user:pass@host:port");

        setupDragAndDrop();
    }

    private void setupDragAndDrop() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor)
                    || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                try {
                    List<String> urls = new ArrayList<>();

                    if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        String text = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                        if (text != null) {
                            for (String line : text.split("\\r?\\n")) {
                                String trimmed = line.trim();
                                if (isValidHttpUrl(trimmed)) {
                                    urls.add(trimmed);
                                }
                            }
                        }
                    }

                    if (urls.isEmpty() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        List<java.io.File> files = (List<java.io.File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                        for (java.io.File file : files) {
                            String candidate = file.toURI().toString();
                            if (isValidHttpUrl(candidate)) {
                                urls.add(candidate);
                            }
                        }
                    }

                    if (urls.isEmpty()) {
                        return false;
                    }

                    if (batchCheckbox.isSelected() || urls.size() > 1) {
                        urlField.setText(String.join(System.lineSeparator(), urls));
                    } else {
                        urlField.setText(urls.get(0));
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };

        urlField.setTransferHandler(handler);
    }

    /**
     * Setup the layout
     */
    private void setupLayout() {
        // Main vertical layout
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        JPanel topCard = createSectionPanel();
        topCard.setLayout(new BoxLayout(topCard, BoxLayout.Y_AXIS));

        JPanel urlPanel = new JPanel(new BorderLayout(8, 0));
        urlPanel.setOpaque(false);
        urlPanel.add(new JLabel("URL:"), BorderLayout.WEST);
        urlPanel.add(urlField, BorderLayout.CENTER);
        urlPanel.add(downloadButton, BorderLayout.EAST);
        topCard.add(urlPanel);
        add(topCard);
        add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel pathRow = new JPanel(new GridBagLayout());
        pathRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        pathRow.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 0, 0, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;

        gbc.gridx = 0;
        gbc.weightx = 0;
        pathRow.add(new JLabel("Save to:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        pathRow.add(pathField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 0, 0, 14);
        pathRow.add(browseButton, gbc);

        gbc.gridx = 3;
        gbc.insets = new Insets(0, 0, 0, 8);
        pathRow.add(new JLabel("Quality:"), gbc);

        gbc.gridx = 4;
        pathRow.add(formatCombo, gbc);

        gbc.gridx = 5;
        gbc.insets = new Insets(0, 6, 0, 0);
        pathRow.add(batchCheckbox, gbc);

        add(pathRow);
        add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel optionsCard = createSectionPanel();
        optionsCard.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        optionsCard.add(advancedSettingsButton);
        add(optionsCard);
        add(Box.createRigidArea(new Dimension(0, 8)));
        
        // Advanced settings panel (initially hidden)
        advancedSettingsPanel.setLayout(new BoxLayout(advancedSettingsPanel, BoxLayout.Y_AXIS));
        advancedSettingsPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(219, 224, 229)),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        advancedSettingsPanel.setBackground(Color.WHITE);
        JPanel advancedGrid = new JPanel(new GridLayout(3, 2, 8, 8));
        advancedGrid.setOpaque(false);
        advancedGrid.add(new JLabel("Output Format:"));
        advancedGrid.add(outputFormatCombo);
        advancedGrid.add(new JLabel("Speed Limit:"));
        advancedGrid.add(speedLimitField);
        advancedGrid.add(new JLabel("Subtitle Langs:"));
        advancedGrid.add(subtitleLangsField);
        advancedSettingsPanel.add(advancedGrid);

        JPanel advancedFlags = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        advancedFlags.setOpaque(false);
        advancedFlags.add(cookiesCheckbox);
        advancedFlags.add(browserCombo);
        advancedFlags.add(playlistCheckbox);
        advancedFlags.add(subtitleCheckbox);
        advancedFlags.add(noCertCheckCheckbox);
        advancedSettingsPanel.add(advancedFlags);

        JPanel proxyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        proxyPanel.setOpaque(false);
        proxyPanel.add(new JLabel("Proxy:"));
        proxyPanel.add(proxyField);
        advancedSettingsPanel.add(proxyPanel);
        add(advancedSettingsPanel);
    }

    private JPanel createSectionPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(219, 224, 229)),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /**
     * Setup event listeners
     */
    private void setupEventListeners() {
        // URL field - detect platform on change
        urlField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkURL(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkURL(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkURL(); }
        });
        
        // Batch mode checkbox
        batchCheckbox.addActionListener(e -> {
            urlField.setPlaceholder(batchCheckbox.isSelected() 
                ? "Enter multiple URLs (one per line)" 
                : "Enter video URL");
        });
        
        // Cookies checkbox
        cookiesCheckbox.addActionListener(e -> 
            browserCombo.setEnabled(cookiesCheckbox.isSelected())
        );
        
        // Browse button
        browseButton.addActionListener(e -> browseDirectory());
        
        // Advanced settings toggle
        advancedSettingsButton.addActionListener(e -> {
            advancedVisible = !advancedVisible;
            advancedSettingsPanel.setVisible(advancedVisible);
            advancedSettingsButton.setText(advancedVisible ? "Hide Advanced Settings" : "Advanced Settings");
            // Revalidate to update layout
            revalidate();
            repaint();
        });
        
        // Download button
        downloadButton.addActionListener(e -> startDownload());
    }

    /**
     * Check URL and provide feedback
     */
    private void checkURL() {
        String url = urlField.getText().trim();
        if (!url.isEmpty()) {
            String platform = URLDetector.detectType(url);
            urlField.setToolTipText("Detected: " + platform);

            if (isValidHttpUrl(url)) {
                urlField.setBackground(Color.WHITE);
            } else {
                urlField.setBackground(new Color(255, 230, 230)); // Light red
            }
        } else {
            urlField.setBackground(Color.WHITE);
            urlField.setToolTipText(null);
        }
    }

    /**
     * Browse for download directory
     */
    private void browseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Download Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            configService.set(Constants.CONFIG_DOWNLOAD_PATH, pathField.getText().trim());
        }
    }

    /**
     * Start the download process
     */
    private void startDownload() {
        String urlText = urlField.getText().trim();
        if (urlText.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a URL to download",
                "Input Error",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }
        
        if (batchCheckbox.isSelected()) {
            startBatchDownload(urlText);
        } else {
            startSingleDownload(urlText);
        }
    }

    public void triggerDownload() {
        startDownload();
    }

    public void focusUrlField() {
        urlField.requestFocusInWindow();
        urlField.selectAll();
    }

    public void pasteIntoUrlField() {
        if (!(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent)) {
            urlField.requestFocusInWindow();
        }
        urlField.paste();
    }

    /**
     * Start a single download
     */
    private void startSingleDownload(String url) {
        if (!isValidHttpUrl(url)) {
            JOptionPane.showMessageDialog(
                this,
                "URL must start with http:// or https://",
                "Input Error",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        Download download = new Download();
        download.setUrl(url);

        applySelectedOptions(download);
        
        boolean queued = downloadService.addDownload(download);
        if (!queued) {
            JOptionPane.showMessageDialog(
                this,
                "This URL is already queued or downloading with the same options.",
                "Duplicate Download",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        
        // Clear input for next download
        urlField.setText("");
        urlField.requestFocusInWindow();
    }

    /**
     * Start batch download (multiple URLs)
     */
    private void startBatchDownload(String urlText) {
        String[] urls = urlText.split("\\r?\\n");
        int validCount = 0;
        int duplicateCount = 0;
        int invalidCount = 0;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        
        for (String url : urls) {
            String trimmed = url.trim();
            if (!trimmed.isEmpty()) {
                try {
                    if (!isValidHttpUrl(trimmed)) {
                        invalidCount++;
                        continue;
                    }
                    if (!seen.add(trimmed)) {
                        duplicateCount++;
                        continue;
                    }
                    Download download = new Download();
                    download.setUrl(trimmed);
                    applySelectedOptions(download);
                    if (downloadService.addDownload(download)) {
                        validCount++;
                    } else {
                        duplicateCount++;
                    }
                } catch (Exception e) {
                    invalidCount++;
                    System.err.println("Invalid URL skipped: " + trimmed);
                }
            }
        }
        
        if (validCount > 0) {
            StringBuilder msg = new StringBuilder();
            msg.append(validCount).append(" URLs added to download queue");
            if (duplicateCount > 0 || invalidCount > 0) {
                msg.append("\nSkipped: ");
                if (duplicateCount > 0) {
                    msg.append(duplicateCount).append(" duplicate");
                }
                if (invalidCount > 0) {
                    if (duplicateCount > 0) {
                        msg.append(", ");
                    }
                    msg.append(invalidCount).append(" invalid");
                }
            }
            JOptionPane.showMessageDialog(
                this,
                msg.toString(),
                "Batch Download",
                JOptionPane.INFORMATION_MESSAGE
            );
            urlField.setText("");
            urlField.requestFocusInWindow();
        } else {
            JOptionPane.showMessageDialog(
                this,
                "No valid URLs found",
                "Input Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Load settings from config service
     */
    private void loadSettings() {
        String configuredPath = configService.getString(
            Constants.CONFIG_DOWNLOAD_PATH,
            System.getProperty("user.home") + "/Downloads"
        );
        pathField.setText(configuredPath);

        String configuredFormat = configService.getString("defaultFormat", "best");
        switch (configuredFormat) {
            case "1080":
                formatCombo.setSelectedItem("1080p");
                break;
            case "720":
                formatCombo.setSelectedItem("720p");
                break;
            case "480":
                formatCombo.setSelectedItem("480p");
                break;
            case "audio":
                formatCombo.setSelectedItem("Audio Only");
                break;
            default:
                formatCombo.setSelectedItem("Best Quality (Recommended)");
                break;
        }
    }

    private void applySelectedOptions(Download download) {
        String selectedFormat = (String) formatCombo.getSelectedItem();
        download.setFormat(mapFormatKey(selectedFormat));
        download.setSavePath(pathField.getText().trim());
        download.setUseCookies(cookiesCheckbox.isSelected());
        download.setCookieBrowser(((String) browserCombo.getSelectedItem()).toLowerCase());
        download.setPlaylist(playlistCheckbox.isSelected());
        download.setOutputFormat((String) outputFormatCombo.getSelectedItem());
        download.setWriteSubs(subtitleCheckbox.isSelected());
        download.setSubLangs(subtitleLangsField.getText().trim().isEmpty() ? "en" : subtitleLangsField.getText().trim());
        download.setNoCertCheck(noCertCheckCheckbox.isSelected());
        download.setSpeedLimit(speedLimitField.getText().trim());
        download.setProxy(proxyField.getText().trim());

        configService.set(Constants.CONFIG_DOWNLOAD_PATH, download.getSavePath());
        configService.set("defaultFormat", download.getFormat());
    }

    private String mapFormatKey(String label) {
        if (label == null) return "best";
        switch (label) {
            case "1080p":
                return "1080";
            case "720p":
                return "720";
            case "480p":
                return "480";
            case "Audio Only":
                return "audio";
            case "Best Video+Audio":
            case "Best Quality":
            case "Best Quality (Recommended)":
            default:
                return "best";
        }
    }

    private boolean isValidHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null
                && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Custom JTextField with placeholder support
     */
    private static class JTextField extends javax.swing.JTextField {
        private String placeholder;

        public JTextField(int columns) {
            super(columns);
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            
            if (placeholder == null || placeholder.isEmpty() || getText().length() > 0) {
                return;
            }
            
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(getDisabledTextColor());
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int x = getInsets().left;
            int y = ((getHeight() - fm.getHeight()) / 2) + fm.getAscent();
            g2.drawString(placeholder, x, y);
            g2.dispose();
        }
    }
}