package com.videograb.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Status bar at the bottom of the main window
 */
public class StatusBar extends JPanel {
    private final JLabel statusLabel;
    private final JLabel concurrentLabel;
    private Timer messageTimer;

    public StatusBar() {
        // Setup panel
        setLayout(new BorderLayout(10, 0));
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 225, 230)));
        setPreferredSize(new Dimension(getWidth(), 24));
        
        // Initialize components
        this.statusLabel = new JLabel("Ready");
        this.statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        
        this.concurrentLabel = new JLabel("0/3 concurrent");
        this.concurrentLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        this.concurrentLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        
        // Add components
        add(statusLabel, BorderLayout.WEST);
        add(concurrentLabel, BorderLayout.EAST);
        
        // Set background
        setBackground(new Color(248, 250, 252));
    }

    /**
     * Show a temporary status message
     */
    public void showMessage(String message, int timeoutMillis) {
        String originalText = statusLabel.getText();
        statusLabel.setText(message);
        
        if (messageTimer != null && messageTimer.isRunning()) {
            messageTimer.stop();
        }
        
        messageTimer = new Timer(timeoutMillis, e -> {
            statusLabel.setText(originalText);
            ((Timer) e.getSource()).stop();
        });
        messageTimer.setRepeats(false);
        messageTimer.start();
    }

    /**
     * Show an error message
     */
    public void showError(String message, int timeoutMillis) {
        Color originalColor = statusLabel.getForeground();
        statusLabel.setForeground(Color.RED);

        if (messageTimer != null && messageTimer.isRunning()) {
            messageTimer.stop();
        }

        String originalText = statusLabel.getText();
        statusLabel.setText(message);
        messageTimer = new Timer(timeoutMillis, e -> {
            statusLabel.setText(originalText);
            statusLabel.setForeground(originalColor);
            ((Timer) e.getSource()).stop();
        });
        messageTimer.setRepeats(false);
        messageTimer.start();
    }

    /**
     * Update concurrent downloads display
     */
    public void updateConcurrentDisplay(int active, int max) {
        concurrentLabel.setText(active + "/" + max + " concurrent");
    }

    /**
     * Set ready state
     */
    public void setReady() {
        statusLabel.setText("Ready");
        statusLabel.setForeground(Color.BLACK);
    }
}