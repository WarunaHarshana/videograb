package com.videograb;

import com.videograb.ui.MainFrame;

import javax.swing.*;

/**
 * Main application entry point
 */
public class Main {
    public static void main(String[] args) {
        // Avoid native-access restrictions on newer JDKs (24+) for FlatLaf optional native helpers.
        System.setProperty("flatlaf.useNativeLibrary", "false");

        // Set system look and feel for modern appearance
        try {
            // Try to use the system look and feel
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fallback to cross-platform look and feel
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ex) {
                // Keep default look and feel
            }
        }
        
        // Enable window decorations for JDialogs and JFrames
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
        
        // Start the application on the Event Dispatch Thread
        SwingUtilities.invokeLater(() -> {
            new MainFrame().setVisible(true);
        });
    }
}