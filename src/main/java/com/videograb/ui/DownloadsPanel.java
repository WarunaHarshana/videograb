package com.videograb.ui;

import com.videograb.model.Download;
import com.videograb.model.DownloadEvent;
import com.videograb.service.DownloadService;
import com.videograb.util.Constants;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel displaying active downloads as cards
 */
public class DownloadsPanel extends JPanel {
    private static final int PREVIEW_WIDTH = 104;
    private static final int PREVIEW_HEIGHT = 58;
    private static final Pattern VIMEO_ID_PATTERN = Pattern.compile("(?:video/)?(\\d+)");

    private final DownloadService downloadService;
    private final Map<String, DownloadCard> downloadCards;
    private final Map<String, ImageIcon> previewCache;
    private final Set<String> previewLoading;
    private final ImageIcon fallbackPreviewIcon;
    private JPanel downloadsContainer;
    private JScrollPane scrollPane;

    public DownloadsPanel(DownloadService downloadService) {
        this.downloadService = downloadService;
        this.downloadCards = new HashMap<>();
        this.previewCache = new ConcurrentHashMap<>();
        this.previewLoading = ConcurrentHashMap.newKeySet();
        this.fallbackPreviewIcon = createFallbackPreviewIcon();

        // Setup panel
        initializePanel();
        setupLayout();
    }

    /**
     * Initialize panel properties
     */
    private void initializePanel() {
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(new Color(243, 246, 250));
        setLayout(new BorderLayout());
        
        // Create scrollable container for download cards
        downloadsContainer = new JPanel();
        downloadsContainer.setLayout(new BoxLayout(downloadsContainer, BoxLayout.Y_AXIS));
        
        scrollPane = new JScrollPane(downloadsContainer);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(new Color(243, 246, 250));
    }

    /**
     * Setup the layout
     */
    private void setupLayout() {
        add(scrollPane, BorderLayout.CENTER);
        
        // Show empty state when no downloads
        showEmptyState();
    }

    /**
     * Show empty state message
     */
    private void showEmptyState() {
        downloadsContainer.removeAll();
        JLabel emptyLabel = new JLabel("No active downloads", SwingConstants.CENTER);
        emptyLabel.setForeground(Color.GRAY);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(50, 0, 50, 0));
        downloadsContainer.add(emptyLabel);
        downloadsContainer.revalidate();
        downloadsContainer.repaint();
    }

    /**
     * Update or add a download card based on event
     */
    public void updateDownload(DownloadEvent event) {
        String downloadId = event.getDownloadId();
        DownloadCard card = downloadCards.get(downloadId);
        
        switch (event.getEventType()) {
            case STATUS_CHANGE:
            case PROGRESS:
                if (!(event.getData() instanceof Download)) {
                    break;
                }
                if (card == null) {
                    // New download - create card
                    Download download = (Download) event.getData();
                    card = new DownloadCard(download);
                    downloadCards.put(downloadId, card);
                    
                    // Add to container (at top for newest first)
                    if (downloadCards.size() == 1) {
                        downloadsContainer.removeAll();
                    }
                    downloadsContainer.add(card, 0);
                    downloadsContainer.revalidate();
                    downloadsContainer.repaint();
                } else {
                    // Existing download - update card
                    Download download = (Download) event.getData();
                    card.updateDownload(download);
                    
                    // If completed/failed, remove after delay
                    if (download.isFinished()) {
                        // Remove after 5 seconds
                        Timer timer = new Timer(5000, e -> {
                            removeDownloadCard(downloadId);
                            ((Timer) e.getSource()).stop();
                        });
                        timer.setRepeats(false);
                        timer.start();
                    }
                }
                break;

            case COMPLETED:
                if (card != null && event.getData() instanceof Download) {
                    card.updateDownload((Download) event.getData());
                }
                break;
                
            case ERROR:
                if (card == null) {
                    // Create error card for failed download
                    Download download;
                    if (event.getData() instanceof Download) {
                        download = (Download) event.getData();
                    } else {
                        download = new Download();
                        download.setUrl("Unknown");
                        download.setStatus("FAILED");
                        download.setErrorMessage(String.valueOf(event.getData()));
                    }
                    card = new DownloadCard(download);
                    downloadCards.put(downloadId, card);
                    
                    downloadsContainer.add(card, 0);
                    downloadsContainer.revalidate();
                    downloadsContainer.repaint();
                    
                    // Auto-remove error cards after 10 seconds
                    Timer timer = new Timer(10000, e -> {
                        removeDownloadCard(downloadId);
                        ((Timer) e.getSource()).stop();
                    });
                    timer.setRepeats(false);
                    timer.start();
                } else {
                    if (event.getData() instanceof Download) {
                        card.updateDownload((Download) event.getData());
                    } else {
                        card.download.setStatus(Constants.STATUS_FAILED);
                        card.download.setErrorMessage(String.valueOf(event.getData()));
                        card.refreshCardUI();
                    }
                }
                break;
                
            default:
                if (card != null && event.getData() instanceof Download) {
                    card.updateDownload((Download) event.getData());
                }
                break;
        }
    }

    /**
     * Remove a download card from the UI
     */
    private void removeDownloadCard(String downloadId) {
        DownloadCard card = downloadCards.remove(downloadId);
        if (card != null) {
            downloadsContainer.remove(card);
            downloadsContainer.revalidate();
            downloadsContainer.repaint();
            
            // Show empty state if no downloads left
            if (downloadCards.isEmpty()) {
                showEmptyState();
            }
        }
    }

    /**
     * Clear all downloads
     */
    public void clearAll() {
        for (String id : downloadCards.keySet()) {
            DownloadCard card = downloadCards.get(id);
            if (card != null) {
                downloadsContainer.remove(card);
            }
        }
        downloadCards.clear();
        downloadsContainer.revalidate();
        downloadsContainer.repaint();
        showEmptyState();
    }

    /**
     * Download card component showing individual download progress
     */
    private class DownloadCard extends JPanel {
        private final Download download;
        private final JLabel titleLabel;
        private final JLabel previewLabel;
        private final JProgressBar progressBar;
        private final JLabel statusLabel;
        private final JLabel speedLabel;
        private final JLabel sizeLabel;
        private final JLabel etaLabel;
        private final JButton pauseButton;
        private final JButton cancelButton;
        private final JButton retryButton;

        public DownloadCard(Download download) {
            this.download = download;
            
            // Initialize components
            this.titleLabel = new JLabel(download.getTitle() != null ? download.getTitle() : "Processing...");
            this.previewLabel = new JLabel("", SwingConstants.CENTER);
            this.progressBar = new JProgressBar(0, 100);
            this.statusLabel = new JLabel(download.getStatus());
            this.speedLabel = new JLabel("");
            this.sizeLabel = new JLabel("");
            this.etaLabel = new JLabel("");
            this.pauseButton = new JButton("Pause");
            this.cancelButton = new JButton("Cancel");
            this.retryButton = new JButton("Retry");
            
            // Setup card
            initializeCard();
            setupLayout();
            setupEventListeners();
            updateDownload(download); // Initial update
        }

        /**
         * Initialize card properties
         */
        private void initializeCard() {
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(219, 224, 229)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
            ));
            setBackground(Color.WHITE);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
            putClientProperty("JComponent.arc", 16);

            previewLabel.setPreferredSize(new Dimension(PREVIEW_WIDTH, PREVIEW_HEIGHT));
            previewLabel.setMinimumSize(new Dimension(PREVIEW_WIDTH, PREVIEW_HEIGHT));
            previewLabel.setOpaque(true);
            previewLabel.setBackground(new Color(226, 234, 243));
            previewLabel.setForeground(new Color(88, 104, 122));
            previewLabel.setBorder(BorderFactory.createLineBorder(new Color(210, 219, 228)));
            previewLabel.putClientProperty("JComponent.arc", 12);
            previewLabel.setIcon(fallbackPreviewIcon);

            // Setup progress bar
            progressBar.setStringPainted(false);
            progressBar.setPreferredSize(new Dimension(260, 8));
            progressBar.putClientProperty("JProgressBar.arc", 999);

            // Setup buttons
            pauseButton.setFocusable(false);
            cancelButton.setFocusable(false);
            retryButton.setFocusable(false);
            pauseButton.putClientProperty("JButton.buttonType", "roundRect");
            cancelButton.putClientProperty("JButton.buttonType", "roundRect");
            retryButton.putClientProperty("JButton.buttonType", "roundRect");

            // Initially hide retry button
            retryButton.setVisible(false);
        }

        /**
         * Setup card layout
         */
        private void setupLayout() {
            setLayout(new BorderLayout(12, 8));

            add(previewLabel, BorderLayout.WEST);

            JPanel leftPanel = new JPanel();
            leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
            leftPanel.setOpaque(false);

            // Title
            JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            titlePanel.setOpaque(false);
            titlePanel.add(titleLabel);
            leftPanel.add(titlePanel);
            
            // Progress bar
            JPanel progressPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
            progressPanel.setOpaque(false);
            progressPanel.add(progressBar);
            leftPanel.add(progressPanel);
            
            // Info line (speed, size, eta)
            JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
            infoPanel.setOpaque(false);
            infoPanel.add(statusLabel);
            infoPanel.add(speedLabel);
            infoPanel.add(sizeLabel);
            infoPanel.add(etaLabel);
            leftPanel.add(infoPanel);
            
            add(leftPanel, BorderLayout.CENTER);
            
            // Right side - buttons
            JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new FlowLayout(FlowLayout.RIGHT, 6, 22));
            buttonPanel.setOpaque(false);
            buttonPanel.add(pauseButton);
            buttonPanel.add(cancelButton);
            buttonPanel.add(retryButton);

            add(buttonPanel, BorderLayout.EAST);
        }

        /**
         * Setup event listeners for buttons
         */
        private void setupEventListeners() {
            pauseButton.addActionListener(e -> togglePause());
            cancelButton.addActionListener(e -> cancelDownload());
            retryButton.addActionListener(e -> retryDownload());
        }

        public void updateDownload(Download download) {
            this.download.setUrl(download.getUrl());
            this.download.setTitle(download.getTitle());
            this.download.setStatus(download.getStatus());
            this.download.setProgress(download.getProgress());
            this.download.setSpeed(download.getSpeed());
            this.download.setEta(download.getEta());
            this.download.setSize(download.getSize());
            this.download.setFilename(download.getFilename());
            this.download.setFormat(download.getFormat());
            this.download.setSavePath(download.getSavePath());
            this.download.setUseCookies(download.isUseCookies());
            this.download.setCookieBrowser(download.getCookieBrowser());
            this.download.setPlaylist(download.isPlaylist());
            this.download.setWriteSubs(download.isWriteSubs());
            this.download.setSubLangs(download.getSubLangs());
            this.download.setNoCertCheck(download.isNoCertCheck());
            this.download.setSpeedLimit(download.getSpeedLimit());
            this.download.setOutputFormat(download.getOutputFormat());
            this.download.setProxy(download.getProxy());
            this.download.setAddedAt(download.getAddedAt());
            this.download.setStartedAt(download.getStartedAt());
            this.download.setCompletedAt(download.getCompletedAt());
            this.download.setErrorMessage(download.getErrorMessage());
            
            refreshCardUI();
        }

        /**
         * Update UI components to reflect current download state
         */
        private void refreshCardUI() {
            // Update title
            String title = download.getTitle();
            if (title == null || title.isEmpty()) {
                title = "Processing...";
                if (download.getUrl() != null && !download.getUrl().isEmpty()) {
                    try {
                        // Extract filename from URL as fallback
                        title = java.net.URI.create(download.getUrl()).getPath();
                        if (title == null || title.isEmpty()) {
                            title = "Unknown Video";
                        }
                    } catch (Exception ignored) {
                        title = "Unknown Video";
                    }
                }
            }
            titleLabel.setText(title);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));

            // Update progress bar
            progressBar.setValue((int) download.getProgress());

            updatePreview();

            // Update status label with color
            String status = download.getStatus();
            statusLabel.setText(status != null ? status : "UNKNOWN");
            switch (status != null ? status : "") {
                case Constants.STATUS_DOWNLOADING:
                    statusLabel.setForeground(new Color(0, 150, 0)); // Green
                    break;
                case Constants.STATUS_COMPLETED:
                    statusLabel.setForeground(new Color(0, 100, 0)); // Dark green
                    break;
                case Constants.STATUS_FAILED:
                    statusLabel.setForeground(new Color(200, 0, 0)); // Red
                    break;
                case Constants.STATUS_PAUSED:
                    statusLabel.setForeground(new Color(180, 100, 0)); // Orange
                    break;
                case Constants.STATUS_QUEUED:
                    statusLabel.setForeground(new Color(100, 100, 100)); // Gray
                    break;
                case Constants.STATUS_CANCELLED:
                    statusLabel.setForeground(new Color(120, 120, 120)); // Light gray
                    break;
                default:
                    statusLabel.setForeground(Color.BLACK);
                    break;
            }
            
            // Update info labels
            speedLabel.setText(download.getSpeed() != null && !download.getSpeed().isBlank() ? "DL " + download.getSpeed() : "");
            sizeLabel.setText(download.getSize() != null ? download.getSize() : "");
            etaLabel.setText(download.getEta() != null && !download.getEta().isBlank() ? "ETA " + download.getEta() : "");

            // Update button states
            boolean isDownloading = Constants.STATUS_DOWNLOADING.equals(status);
            boolean isPaused = Constants.STATUS_PAUSED.equals(status);
            boolean isFinished = download.isFinished(); // Assuming we add this method
            boolean pauseSupported = downloadService.isPauseResumeSupported();
            
            pauseButton.setEnabled((isDownloading || isPaused) && !isFinished && pauseSupported);
            pauseButton.setText(isPaused ? "Resume" : "Pause");
            pauseButton.setToolTipText(
                pauseSupported ? null : "Pause/resume is not supported on this system configuration"
            );
            cancelButton.setEnabled(!isFinished);
            retryButton.setVisible(Constants.STATUS_FAILED.equals(status));
            retryButton.setEnabled(Constants.STATUS_FAILED.equals(status));
        }

        private void updatePreview() {
            String source = resolvePreviewSource(download);
            if (source == null || source.isBlank()) {
                previewLabel.setIcon(fallbackPreviewIcon);
                previewLabel.setText("");
                return;
            }

            ImageIcon cached = previewCache.get(source);
            if (cached != null) {
                previewLabel.setIcon(cached);
                previewLabel.setText("");
                return;
            }

            previewLabel.setIcon(fallbackPreviewIcon);
            previewLabel.setText("");
            loadPreviewAsync(source, previewLabel);
        }

        /**
         * Toggle pause/resume state
         */
        private void togglePause() {
            if (download.getStatus().equals(Constants.STATUS_DOWNLOADING)) {
                boolean paused = downloadService.pauseDownload(download.getId());
                if (!paused) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Unable to pause this download right now.",
                        "Pause Failed",
                        JOptionPane.WARNING_MESSAGE
                    );
                }
            } else if (download.getStatus().equals(Constants.STATUS_PAUSED)) {
                boolean resumed = downloadService.resumeDownload(download.getId());
                if (!resumed) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Unable to resume this download right now.",
                        "Resume Failed",
                        JOptionPane.WARNING_MESSAGE
                    );
                }
            }
            refreshCardUI();
        }

        /**
         * Cancel the download
         */
        private void cancelDownload() {
            int result = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "Are you sure you want to cancel this download?",
                "Confirm Cancel",
                JOptionPane.YES_NO_OPTION
            );
            
            if (result == JOptionPane.YES_OPTION) {
                downloadService.cancelDownload(download.getId());
                refreshCardUI();
            }
        }

        /**
         * Retry a failed download
         */
        private void retryDownload() {
            Download retry = new Download();
            retry.setUrl(download.getUrl());
            retry.setFormat(download.getFormat());
            retry.setSavePath(download.getSavePath());
            retry.setUseCookies(download.isUseCookies());
            retry.setCookieBrowser(download.getCookieBrowser());
            retry.setPlaylist(download.isPlaylist());
            retry.setWriteSubs(download.isWriteSubs());
            retry.setSubLangs(download.getSubLangs());
            retry.setNoCertCheck(download.isNoCertCheck());
            retry.setSpeedLimit(download.getSpeedLimit());
            retry.setOutputFormat(download.getOutputFormat());
            retry.setProxy(download.getProxy());

            boolean queued = downloadService.addDownload(retry);
            if (!queued) {
                JOptionPane.showMessageDialog(
                    this,
                    "A matching download is already queued or running.",
                    "Retry Skipped",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        }

    }

    private void loadPreviewAsync(String source, JLabel target) {
        if (!previewLoading.add(source)) {
            return;
        }

        SwingWorker<ImageIcon, Void> loader = new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() {
                try {
                    BufferedImage img = readPreviewImage(source);
                    if (img == null) {
                        return null;
                    }
                    Image scaled = img.getScaledInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, Image.SCALE_SMOOTH);
                    return new ImageIcon(scaled);
                } catch (Exception ignored) {
                    return null;
                }
            }

            @Override
            protected void done() {
                previewLoading.remove(source);
                try {
                    ImageIcon icon = get();
                    if (icon != null) {
                        previewCache.put(source, icon);
                        target.setIcon(icon);
                        target.setText("");
                    }
                } catch (Exception ignored) {
                    // Keep fallback icon.
                }
            }
        };
        loader.execute();
    }

    private BufferedImage readPreviewImage(String source) {
        try {
            if (source.startsWith("file:")) {
                File file = new File(URI.create(source));
                if (file.exists() && file.isFile()) {
                    return ImageIO.read(file);
                }
                return null;
            }
            return ImageIO.read(URI.create(source).toURL());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolvePreviewSource(Download download) {
        String local = resolveLocalImagePreview(download);
        if (local != null) {
            return local;
        }

        String url = download.getUrl();
        if (url == null || url.isBlank()) {
            return null;
        }

        String youtubeId = extractYoutubeId(url);
        if (youtubeId != null) {
            return "https://i.ytimg.com/vi/" + youtubeId + "/mqdefault.jpg";
        }

        String vimeoId = extractVimeoId(url);
        if (vimeoId != null) {
            return "https://vumbnail.com/" + vimeoId + ".jpg";
        }
        return null;
    }

    private String resolveLocalImagePreview(Download download) {
        String filename = download.getFilename();
        if (filename == null || filename.isBlank()) {
            return null;
        }

        try {
            File file = new File(filename);
            if (!file.isAbsolute() && download.getSavePath() != null && !download.getSavePath().isBlank()) {
                file = new File(download.getSavePath(), filename);
            }
            if (!file.exists() || !file.isFile()) {
                return null;
            }
            String lower = file.getName().toLowerCase();
            boolean isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp");
            return isImage ? file.toURI().toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractYoutubeId(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (host.contains("youtu.be")) {
                String path = uri.getPath();
                if (path != null && path.length() > 1) {
                    return path.substring(1).split("/")[0];
                }
            }
            if (host.contains("youtube.com")) {
                String query = uri.getRawQuery();
                if (query != null) {
                    for (String part : query.split("&")) {
                        int idx = part.indexOf('=');
                        if (idx > 0 && "v".equals(part.substring(0, idx))) {
                            return part.substring(idx + 1);
                        }
                    }
                }
                String path = uri.getPath();
                if (path != null && path.startsWith("/shorts/")) {
                    return path.substring("/shorts/".length()).split("/")[0];
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String extractVimeoId(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (!host.contains("vimeo.com")) {
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            Matcher m = VIMEO_ID_PATTERN.matcher(path);
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            return last;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ImageIcon createFallbackPreviewIcon() {
        BufferedImage image = new BufferedImage(PREVIEW_WIDTH, PREVIEW_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(226, 234, 243));
            g.fillRoundRect(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT, 12, 12);
            g.setColor(new Color(92, 117, 146));
            int cx = PREVIEW_WIDTH / 2;
            int cy = PREVIEW_HEIGHT / 2;
            g.fillPolygon(new int[]{cx - 7, cx - 7, cx + 8}, new int[]{cy - 9, cy + 9, cy}, 3);
        } finally {
            g.dispose();
        }
        return new ImageIcon(image);
    }
}
