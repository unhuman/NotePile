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
            MainWindow mainWindow = new MainWindow();
            mainWindow.setVisible(true);
            mainWindow.initialize();
        });
    }
}

