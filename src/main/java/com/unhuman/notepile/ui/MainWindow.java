package com.unhuman.notepile.ui;

import com.unhuman.notepile.model.Settings;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main application window for NotePile
 */
public class MainWindow extends JFrame {
    private Settings settings;
    private JLabel statusLabel;

    public MainWindow() {
        super("NotePile - Hierarchical Note Taking");

        initComponents();

        setSize(1024, 768);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onExit();
            }
        });
    }

    private void initComponents() {
        // Menu bar
        JMenuBar menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");

        JMenuItem settingsMenuItem = new JMenuItem("Settings...");
        // Show settings dialog WITHOUT storage field (only per-storage settings)
        settingsMenuItem.addActionListener(e -> showSettings(this.settings, false, false));
        fileMenu.add(settingsMenuItem);

        fileMenu.addSeparator();

        JMenuItem exitMenuItem = new JMenuItem("Exit");
        exitMenuItem.addActionListener(e -> onExit());
        fileMenu.add(exitMenuItem);

        menuBar.add(fileMenu);

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutMenuItem = new JMenuItem("About");
        aboutMenuItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutMenuItem);

        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        // Main content panel
        JPanel contentPanel = new JPanel(new BorderLayout());

        // Placeholder for note-taking UI
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel placeholderLabel = new JLabel("Note-taking interface will be implemented here", SwingConstants.CENTER);
        placeholderLabel.setFont(placeholderLabel.getFont().deriveFont(18f));
        placeholderLabel.setForeground(Color.GRAY);
        centerPanel.add(placeholderLabel, BorderLayout.CENTER);

        contentPanel.add(centerPanel, BorderLayout.CENTER);

        // Status bar
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
            BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        contentPanel.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(contentPanel);
    }

    public void initialize() {
        // Step 1: Try to find existing settings via pointer in user home
        settings = Settings.findExisting();

        if (settings != null && settings.isValid()) {
            updateStatus("Storage location: " + settings.getStorageLocation());
            return;
        }

        // No existing settings found - ask the user to choose storage directory (required)
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Storage Location for NotePile");
        // Start in user home
        chooser.setCurrentDirectory(new File(System.getProperty("user.home")));

        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            JOptionPane.showMessageDialog(this,
                "Storage location is required to use NotePile.\nApplication will now exit.",
                "Configuration Required",
                JOptionPane.WARNING_MESSAGE);
            System.exit(0);
            return;
        }

        File selectedDir = chooser.getSelectedFile();
        String chosen = selectedDir.getAbsolutePath();

        // Check whether a settings file already exists in the chosen directory
        Path configPath = Paths.get(chosen, com.unhuman.notepile.model.Settings.CONFIG_FILE_NAME);
        boolean existed = Files.exists(configPath);

        try {
            Settings loaded = Settings.setStorageLocationAndLoadIfExists(chosen);
            // If a settings file did not exist, surface the settings dialog to let user configure date format
            if (!existed) {
                // Show settings dialog to collect date format (storage location is already set)
                boolean confirmed = SettingsDialog.showDialog(this, loaded, false, false);
                // If user did not confirm, we'll proceed with defaults, otherwise the dialog will have set values
                if (confirmed) {
                    // Save the updated settings
                    loaded.save();
                } else {
                    // Save defaults anyway so app has persistent settings
                    loaded.save();
                }
            }
            this.settings = loaded;
            updateStatus("Storage location: " + settings.getStorageLocation());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Failed to save/load settings: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /**
     * Show settings dialog. If 'dialogSettings' represents a required storage location selection, the returned
     * settings will be persisted via Settings.setStorageLocationAndLoadIfExists.
     */
    private boolean showSettings(Settings dialogSettings, boolean requireStorageLocation, boolean showStorageField) {
        boolean confirmed = SettingsDialog.showDialog(this, dialogSettings, requireStorageLocation, showStorageField);

        if (!confirmed) {
            return false;
        }

        // If the dialog provided a storage location, persist pointer and load or create settings there
        String chosen = dialogSettings.getStorageLocation();
        if (chosen != null && !chosen.trim().isEmpty()) {
            try {
                Settings loaded = Settings.setStorageLocationAndLoadIfExists(chosen);
                // If user edited other values in the dialog (dateFormat), prefer those
                if (dialogSettings.getDateFormat() != null && !dialogSettings.getDateFormat().trim().isEmpty()) {
                    loaded.setDateFormat(dialogSettings.getDateFormat());
                }
                loaded.save();
                this.settings = loaded;
                updateStatus("Settings saved - Storage: " + settings.getStorageLocation());
                return true;
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to save settings: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }

        // No storage change - just update other settings on the current settings object
        if (this.settings != null) {
            if (dialogSettings.getDateFormat() != null) {
                this.settings.setDateFormat(dialogSettings.getDateFormat());
            }
            try {
                this.settings.save();
                updateStatus("Settings saved - Storage: " + settings.getStorageLocation());
                return true;
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to save settings: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }

        return confirmed;
    }

    private void updateStatus(String message) {
        statusLabel.setText(message);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "NotePile v1.0.0\n" +
            "Hierarchical Note Taking Application\n\n" +
            "© 2025 Unhuman",
            "About NotePile",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void onExit() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to exit?",
            "Exit NotePile",
            JOptionPane.YES_NO_OPTION);

        if (choice == JOptionPane.YES_OPTION) {
            dispose();
            System.exit(0);
        }
    }
}
