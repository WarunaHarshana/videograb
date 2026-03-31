package com.videograb.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Lightweight toast notifications anchored to the owner frame.
 */
public class ToastManager {
    private final JFrame owner;

    public ToastManager(JFrame owner) {
        this.owner = owner;
    }

    public void showInfo(String message) {
        show(message, new Color(40, 120, 220), 2600);
    }

    public void showSuccess(String message) {
        show(message, new Color(20, 140, 70), 2600);
    }

    public void showError(String message) {
        show(message, new Color(180, 50, 50), 4200);
    }

    private void show(String message, Color background, int timeoutMillis) {
        if (!owner.isShowing()) {
            return;
        }

        JWindow toast = new JWindow(owner);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0, 0, 0, 35), 1),
            BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));
        panel.setBackground(background);

        JLabel label = new JLabel(message);
        label.setForeground(Color.WHITE);
        panel.add(label, BorderLayout.CENTER);

        toast.add(panel);
        toast.pack();
        toast.setAlwaysOnTop(true);

        Point ownerLocation = owner.getLocationOnScreen();
        int x = ownerLocation.x + owner.getWidth() - toast.getWidth() - 18;
        int y = ownerLocation.y + owner.getHeight() - toast.getHeight() - 42;
        toast.setLocation(Math.max(8, x), Math.max(8, y));
        toast.setVisible(true);

        Timer timer = new Timer(timeoutMillis, e -> {
            toast.setVisible(false);
            toast.dispose();
            ((Timer) e.getSource()).stop();
        });
        timer.setRepeats(false);
        timer.start();
    }
}
