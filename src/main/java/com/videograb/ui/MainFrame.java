package com.videograb.ui;

import com.videograb.model.DownloadEvent;
import com.videograb.service.ConfigService;
import com.videograb.service.DownloadService;
import com.videograb.service.HistoryService;
import com.videograb.util.Constants;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Main application window
 */
public class MainFrame extends JFrame {
    private final DownloadService downloadService;
    private final HistoryService historyService;
    private final ConfigService configService;
    private final InputPanel inputPanel;
    private final DownloadsPanel downloadsPanel;
    private final HistoryPanel historyPanel;
    private final StatusBar statusBar;
    private final ToastManager toastManager;
    private final JTabbedPane tabbedPane;
    private final BlockingQueue<DownloadEvent> eventQueue;
    private SwingWorker<Void, DownloadEvent> eventListener;
    private String activeTheme;
    private TrayIcon trayIcon;
    private boolean trayAvailable;
    private volatile boolean shuttingDown;

    public MainFrame() {
        super("VideoGrab");
        this.configService = new ConfigService();
        this.historyService = new HistoryService();
        this.activeTheme = configService.getString(Constants.CONFIG_THEME, Constants.DEFAULT_THEME);
        applyTheme(activeTheme);

        int maxConcurrent = configService.getInt(
            Constants.CONFIG_CONCURRENT_DOWNLOADS,
            Constants.DEFAULT_CONCURRENT_DOWNLOADS
        );
        this.downloadService = new DownloadService(Math.max(1, maxConcurrent), historyService);
        this.eventQueue = downloadService.getEventQueue();
        
        // Initialize UI components
        this.inputPanel = new InputPanel(downloadService);
        this.downloadsPanel = new DownloadsPanel(downloadService);
        this.historyPanel = new HistoryPanel(historyService);
        this.statusBar = new StatusBar();
        this.toastManager = new ToastManager(this);
        this.tabbedPane = new JTabbedPane();
        
        // Setup frame
        initializeFrame();
        setupLayout();
        setupEventListeners();
        
        // Start listening for download events
        startEventListener();

        // Surface missing runtime dependencies early.
        checkToolAvailability();
    }

    /**
     * Initialize frame properties
     */
    private void initializeFrame() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(980, 640);
        setLocationRelativeTo(null); // Center on screen
        setMinimumSize(new Dimension(860, 540));

        setupMenuBar();
        setupSystemTray();
    }

    /**
     * Setup the main layout
     */
    private void setupLayout() {
        // Create main panel with border layout
        JPanel mainPanel = new JPanel(new BorderLayout(14, 14));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        mainPanel.setBackground(new Color(243, 246, 250));

        // Add components
        mainPanel.add(inputPanel, BorderLayout.NORTH);
        
        // Create tabbed pane for downloads and history
        tabbedPane.addTab("Active Downloads", downloadsPanel);
        tabbedPane.addTab("History", historyPanel);
        tabbedPane.setBackground(Color.WHITE);
        tabbedPane.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        
        mainPanel.add(statusBar, BorderLayout.SOUTH);
        
        add(mainPanel);
    }

    /**
     * Setup event listeners
     */
    private void setupEventListeners() {
        setupGlobalShortcuts();
        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 1) {
                historyPanel.refreshHistory();
            }
        });

        // Handle window closing
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                if (trayAvailable) {
                    minimizeToTray();
                } else {
                    onClosing();
                }
            }

            @Override
            public void windowIconified(WindowEvent e) {
                if (trayAvailable) {
                    SwingUtilities.invokeLater(() -> minimizeToTray());
                }
            }
        });
    }

    /**
     * Start listening for download events from workers
     */
    private void startEventListener() {
        eventListener = new SwingWorker<Void, DownloadEvent>() {
            @Override
            protected Void doInBackground() {
                while (!isCancelled()) {
                    try {
                        DownloadEvent event = eventQueue.take();
                        SwingUtilities.invokeLater(() -> handleDownloadEvent(event));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return null;
            }
        };
        eventListener.execute();
    }

    /**
     * Handle incoming download events
     */
    private void handleDownloadEvent(DownloadEvent event) {
        statusBar.updateConcurrentDisplay(
            downloadService.getActiveDownloadCount(),
            downloadService.getMaxConcurrentDownloads()
        );

        switch (event.getEventType()) {
            case PROGRESS:
            case STATUS_CHANGE:
                downloadsPanel.updateDownload(event);
                if (event.getEventType() == DownloadEvent.EventType.STATUS_CHANGE
                    && event.getData() instanceof com.videograb.model.Download
                    && ((com.videograb.model.Download) event.getData()).isFinished()) {
                    historyPanel.refreshHistory();
                }
                break;
            case COMPLETED:
                downloadsPanel.updateDownload(event);
                historyPanel.refreshHistory();
                if (event.getData() instanceof com.videograb.model.Download) {
                    com.videograb.model.Download d = (com.videograb.model.Download) event.getData();
                    String label = d.getTitle() != null ? d.getTitle() : d.getUrl();
                    statusBar.showMessage("Download completed: " + label, 3000);
                    toastManager.showSuccess("Completed: " + shorten(label));
                    if (configService.getBoolean(Constants.CONFIG_AUTO_OPEN_FOLDER, true)) {
                        openFolderSilently(d.getSavePath());
                    }
                } else {
                    statusBar.showMessage("Download completed", 3000);
                    toastManager.showSuccess("Download completed");
                }
                break;
            case ERROR:
                downloadsPanel.updateDownload(event);
                historyPanel.refreshHistory();
                if (event.getData() instanceof com.videograb.model.Download) {
                    com.videograb.model.Download d = (com.videograb.model.Download) event.getData();
                    String msg = d.getErrorMessage() != null ? d.getErrorMessage() : "Unknown error";
                    statusBar.showError("Download failed: " + msg, 5000);
                    toastManager.showError("Failed: " + shorten(msg));
                } else {
                    statusBar.showError("Download failed", 5000);
                    toastManager.showError("Download failed");
                }
                break;
            case LOG:
                // Could add to a log panel
                break;
        }
    }

    /**
     * Handle application closing
     */
    private void onClosing() {
        if (shuttingDown) {
            return;
        }

        int activeCount = downloadService.getActiveDownloadCount();
        String message = activeCount > 0
            ? "There are " + activeCount + " active download(s). Exiting now will cancel them.\n\nDo you want to exit?"
            : "Are you sure you want to exit?";

        int result = JOptionPane.showConfirmDialog(
            this,
            message,
            "Confirm Exit",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            shuttingDown = true;
            removeTrayIcon();
            downloadService.shutdown();
            if (eventListener != null && !eventListener.isDone()) {
                eventListener.cancel(true);
            }
            dispose();
        }
    }

    /**
     * Get the download service (for testing)
     */
    public DownloadService getDownloadService() {
        return downloadService;
    }

    private void setupGlobalShortcuts() {
        JRootPane root = getRootPane();
        InputMap inputMap = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = root.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke("control ENTER"), "startDownload");
        actionMap.put("startDownload", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                inputPanel.triggerDownload();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke("control shift H"), "showHistoryTab");
        actionMap.put("showHistoryTab", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                tabbedPane.setSelectedIndex(1);
                historyPanel.refreshHistory();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "focusUrlField");
        actionMap.put("focusUrlField", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                tabbedPane.setSelectedIndex(0);
                inputPanel.focusUrlField();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke("control V"), "globalPaste");
        actionMap.put("globalPaste", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                inputPanel.pasteIntoUrlField();
            }
        });
    }

    private void checkToolAvailability() {
        java.util.Map<String, Boolean> tools = downloadService.getToolAvailability();
        java.util.List<String> missing = new ArrayList<>();
        for (java.util.Map.Entry<String, Boolean> entry : tools.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                missing.add(entry.getKey());
            }
        }

        if (!missing.isEmpty()) {
            String missingText = String.join(", ", missing);
            statusBar.showError("Missing tools: " + missingText, 7000);
            toastManager.showError("Missing tools: " + missingText);
            JOptionPane.showMessageDialog(
                this,
                "Some required tools were not detected: " + missingText + "\n"
                    + "Downloads may fail until they are installed and available on PATH.",
                "Missing Dependencies",
                JOptionPane.WARNING_MESSAGE
            );
        }
    }

    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem showItem = new JMenuItem("Show Window");
        showItem.addActionListener(e -> restoreFromTray());
        fileMenu.add(showItem);

        JMenuItem minimizeItem = new JMenuItem("Minimize To Tray");
        minimizeItem.addActionListener(e -> minimizeToTray());
        fileMenu.add(minimizeItem);
        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> onClosing());
        fileMenu.add(exitItem);

        JMenu viewMenu = new JMenu("View");
        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup themeGroup = new ButtonGroup();

        JRadioButtonMenuItem lightTheme = new JRadioButtonMenuItem("Light");
        JRadioButtonMenuItem darkTheme = new JRadioButtonMenuItem("Dark");
        themeGroup.add(lightTheme);
        themeGroup.add(darkTheme);

        lightTheme.setSelected(!Constants.THEME_DARK.equalsIgnoreCase(activeTheme));
        darkTheme.setSelected(Constants.THEME_DARK.equalsIgnoreCase(activeTheme));

        lightTheme.addActionListener(e -> switchTheme(Constants.THEME_LIGHT));
        darkTheme.addActionListener(e -> switchTheme(Constants.THEME_DARK));

        JCheckBoxMenuItem autoOpenFolderItem = new JCheckBoxMenuItem("Auto Open Folder On Completion");
        autoOpenFolderItem.setSelected(configService.getBoolean(Constants.CONFIG_AUTO_OPEN_FOLDER, true));
        autoOpenFolderItem.addActionListener(e ->
            configService.set(Constants.CONFIG_AUTO_OPEN_FOLDER, autoOpenFolderItem.isSelected())
        );

        themeMenu.add(lightTheme);
        themeMenu.add(darkTheme);
        viewMenu.add(themeMenu);
        viewMenu.addSeparator();
        viewMenu.add(autoOpenFolderItem);

        JMenu settingsMenu = new JMenu("Settings");
        JMenuItem maxConcurrentItem = new JMenuItem("Set Max Concurrent Downloads...");
        maxConcurrentItem.addActionListener(e -> promptAndSaveMaxConcurrent());
        settingsMenu.add(maxConcurrentItem);

        JMenu toolsMenu = new JMenu("Tools");
        JMenuItem diagnosticsItem = new JMenuItem("Runtime Diagnostics");
        diagnosticsItem.addActionListener(e -> showDiagnosticsDialog());
        toolsMenu.add(diagnosticsItem);

        JMenuItem validationChecklistItem = new JMenuItem("Validation Checklist");
        validationChecklistItem.addActionListener(e -> showValidationChecklistDialog());
        toolsMenu.add(validationChecklistItem);

        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(settingsMenu);
        menuBar.add(toolsMenu);
        setJMenuBar(menuBar);
    }

    private void switchTheme(String theme) {
        String normalized = normalizeTheme(theme);
        if (normalized.equals(activeTheme)) {
            return;
        }

        applyTheme(normalized);
        configService.set(Constants.CONFIG_THEME, normalized);
        toastManager.showInfo("Theme switched to " + normalized);
    }

    private void applyTheme(String theme) {
        String normalized = normalizeTheme(theme);
        try {
            if (Constants.THEME_DARK.equals(normalized)) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
            }
            activeTheme = normalized;
        } catch (UnsupportedLookAndFeelException e) {
            activeTheme = Constants.THEME_LIGHT;
        }

        SwingUtilities.updateComponentTreeUI(this);
        repaint();
    }

    private String normalizeTheme(String theme) {
        if (theme == null) {
            return Constants.THEME_LIGHT;
        }
        return Constants.THEME_DARK.equalsIgnoreCase(theme) ? Constants.THEME_DARK : Constants.THEME_LIGHT;
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 72) {
            return text;
        }
        return text.substring(0, 69) + "...";
    }

    private void openFolderSilently(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return;
        }

        try {
            File folder = new File(folderPath);
            if (!folder.exists()) {
                return;
            }
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop.getDesktop().open(folder);
        } catch (Exception ignored) {
            // Optional UX behavior; ignore failures.
        }
    }

    private void setupSystemTray() {
        if (!SystemTray.isSupported()) {
            trayAvailable = false;
            return;
        }

        trayAvailable = true;
        SystemTray tray = SystemTray.getSystemTray();

        PopupMenu popup = new PopupMenu();
        MenuItem openItem = new MenuItem("Open VideoGrab");
        openItem.addActionListener(e -> SwingUtilities.invokeLater(this::restoreFromTray));
        popup.add(openItem);

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> SwingUtilities.invokeLater(this::onClosing));
        popup.add(exitItem);

        Image icon = createTrayIconImage();
        trayIcon = new TrayIcon(icon, "VideoGrab", popup);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> SwingUtilities.invokeLater(this::restoreFromTray));

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            trayAvailable = false;
            trayIcon = null;
        }
    }

    private Image createTrayIconImage() {
        int size = 16;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            size,
            size,
            java.awt.image.BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(30, 144, 255));
            g.fillRoundRect(0, 0, size - 1, size - 1, 5, 5);
            g.setColor(Color.WHITE);
            g.fillPolygon(new int[]{5, 12, 5}, new int[]{4, 8, 12}, 3);
        } finally {
            g.dispose();
        }
        return image;
    }

    private void minimizeToTray() {
        if (!trayAvailable || shuttingDown) {
            setState(JFrame.ICONIFIED);
            return;
        }
        setVisible(false);
        if (trayIcon != null) {
            trayIcon.displayMessage(
                "VideoGrab",
                "Running in system tray. Double-click tray icon to restore.",
                TrayIcon.MessageType.INFO
            );
        }
    }

    private void restoreFromTray() {
        if (!isVisible()) {
            setVisible(true);
        }
        setExtendedState(JFrame.NORMAL);
        toFront();
        requestFocus();
    }

    private void removeTrayIcon() {
        if (trayAvailable && trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    private void showDiagnosticsDialog() {
        java.util.Map<String, String> details = downloadService.getToolDetails();
        StringBuilder message = new StringBuilder();
        message.append("Runtime tool diagnostics:\n\n");

        for (java.util.Map.Entry<String, String> entry : details.entrySet()) {
            String value = entry.getValue();
            boolean available = value != null && !value.toLowerCase().startsWith("not available");
            message.append(entry.getKey())
                .append(": ")
                .append(available ? "OK" : "Missing")
                .append("\n")
                .append("  ")
                .append(value)
                .append("\n\n");
        }

        JOptionPane.showMessageDialog(
            this,
            message.toString(),
            "Runtime Diagnostics",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void promptAndSaveMaxConcurrent() {
        int current = configService.getInt(
            Constants.CONFIG_CONCURRENT_DOWNLOADS,
            Constants.DEFAULT_CONCURRENT_DOWNLOADS
        );

        String input = JOptionPane.showInputDialog(
            this,
            "Set maximum concurrent downloads (1-10).\nTakes effect after restart:",
            String.valueOf(current)
        );

        if (input == null) {
            return;
        }

        String trimmed = input.trim();
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a valid integer between 1 and 10.",
                "Invalid Value",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        if (parsed < 1 || parsed > 10) {
            JOptionPane.showMessageDialog(
                this,
                "Value must be between 1 and 10.",
                "Invalid Value",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        downloadService.setMaxConcurrentDownloads(parsed);
        configService.set(Constants.CONFIG_CONCURRENT_DOWNLOADS, parsed);
        statusBar.showMessage("Max concurrent downloads set to " + parsed, 4000);
        toastManager.showInfo("Max concurrent downloads updated");
    }

    private void showValidationChecklistDialog() {
        Map<String, String> checks = new LinkedHashMap<>();

        checks.put("python available", boolLabel(Boolean.TRUE.equals(downloadService.getToolAvailability().get("python"))));
        checks.put("yt-dlp available", boolLabel(Boolean.TRUE.equals(downloadService.getToolAvailability().get("yt-dlp"))));
        checks.put("ffmpeg available", boolLabel(Boolean.TRUE.equals(downloadService.getToolAvailability().get("ffmpeg"))));
        checks.put("pause/resume supported", boolLabel(downloadService.isPauseResumeSupported()));
        checks.put("system tray supported", boolLabel(SystemTray.isSupported()));
        checks.put("auto-open folder enabled", boolLabel(configService.getBoolean(Constants.CONFIG_AUTO_OPEN_FOLDER, true)));

        StringBuilder message = new StringBuilder();
        message.append("Quick validation checklist:\n\n");
        for (Map.Entry<String, String> entry : checks.entrySet()) {
            message.append("- ")
                .append(entry.getKey())
                .append(": ")
                .append(entry.getValue())
                .append("\n");
        }

        message.append("\nActive downloads: ")
            .append(downloadService.getActiveDownloadCount())
            .append("\nConfigured max concurrent: ")
            .append(configService.getInt(Constants.CONFIG_CONCURRENT_DOWNLOADS, Constants.DEFAULT_CONCURRENT_DOWNLOADS));

        JOptionPane.showMessageDialog(
            this,
            message.toString(),
            "Validation Checklist",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    private String boolLabel(boolean ok) {
        return ok ? "OK" : "Needs Attention";
    }
}