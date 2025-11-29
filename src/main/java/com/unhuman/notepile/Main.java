package com.unhuman.notepile;

import com.unhuman.notepile.ui.MainWindow;

import javax.swing.*;

/**
 * Main entry point for NotePile application
 */
public class Main {
    public static void main(String[] args) {
        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Create and show main window on EDT
        SwingUtilities.invokeLater(() -> {
            // Initialize JavaFX toolkit by creating a JFXPanel (safe no-op if JavaFX not present)
            try {
                // Create a single JFXPanel to initialize JavaFX runtime
                new javafx.embed.swing.JFXPanel();
                try {
                    javafx.application.Platform.setImplicitExit(false);
                } catch (Throwable t) {
                    // ignore
                }
            } catch (Throwable t) {
                // JavaFX not available; we'll rely on the fallback (JEditorPane) for rendering
                System.err.println("NotePile: JavaFX not initialized - WebView will be unavailable: " + t.getMessage());
            }
            MainWindow mainWindow = new MainWindow();
            mainWindow.setVisible(true);
            mainWindow.initialize();
        });
    }
}
