package com.unhuman.notepile.ui;

import com.unhuman.notepile.model.Settings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Settings dialog for configuring NotePile application settings
 */
public class SettingsDialog extends JDialog {
    private final Settings settings;
    private final boolean showStorageField;
    private boolean confirmed = false;

    private JTextField storageLocationField;
    private JComboBox<String> dateFormatComboBox;

    // Common date format options
    private static final String[] DATE_FORMATS = {
        "yyyy-MM-dd",
        "MM-dd-yyyy",
        "dd-MM-yyyy",
        "yyyy/MM/dd",
        "MM/dd/yyyy",
        "dd/MM/yyyy"
    };

    public SettingsDialog(Frame parent, Settings settings, boolean requireStorageLocation, boolean showStorageField) {
        super(parent, "NotePile Settings", true);
        this.settings = settings;
        this.showStorageField = showStorageField;

        initComponents(requireStorageLocation);
        loadSettings();

        setSize(600, 270);
        setLocationRelativeTo(parent);
    }

    private void initComponents(boolean requireStorageLocation) {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // Settings panel
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        if (showStorageField) {
            // Storage Location
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            JLabel storageLabel = new JLabel("Storage Location:");
            if (requireStorageLocation) {
                storageLabel.setText("Storage Location: *");
                storageLabel.setForeground(Color.RED.darker());
            }
            settingsPanel.add(storageLabel, gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            storageLocationField = new JTextField();
            storageLocationField.setEditable(false);
            settingsPanel.add(storageLocationField, gbc);

            gbc.gridx = 2;
            gbc.weightx = 0;
            JButton browseButton = new JButton("...");
            browseButton.addActionListener(e -> browseForDirectory());
            settingsPanel.add(browseButton, gbc);

            row++;
        }

        // Date Format
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        settingsPanel.add(new JLabel("Date Format:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.gridwidth = showStorageField ? 2 : 3;
        dateFormatComboBox = new JComboBox<>(DATE_FORMATS);
        dateFormatComboBox.setEditable(true);
        settingsPanel.add(dateFormatComboBox, gbc);

        mainPanel.add(settingsPanel, BorderLayout.CENTER);

        // Required field notice
        if (requireStorageLocation && showStorageField) {
            JLabel requiredLabel = new JLabel("* Storage location must be specified before using NotePile");
            requiredLabel.setForeground(Color.RED.darker());
            requiredLabel.setFont(requiredLabel.getFont().deriveFont(Font.ITALIC));
            mainPanel.add(requiredLabel, BorderLayout.NORTH);
        }

        // Buttons panel
        JPanel buttonsPanel = new JPanel(new BorderLayout());

        // Left side: storage-change button (always visible to allow moving storage)
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton changeStorageBtn = new JButton("Change Storage...");
        changeStorageBtn.addActionListener(e -> onChangeStorage());
        leftButtons.add(changeStorageBtn);
        buttonsPanel.add(leftButtons, BorderLayout.WEST);

        // Right side: OK/Cancel
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> onOK(requireStorageLocation));
        rightButtons.add(okButton);

        if (!requireStorageLocation) {
            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> onCancel());
            rightButtons.add(cancelButton);
        }

        buttonsPanel.add(rightButtons, BorderLayout.EAST);

        mainPanel.add(buttonsPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        getRootPane().setDefaultButton(okButton);
    }

    private void loadSettings() {
        if (showStorageField && settings.getStorageLocation() != null) {
            storageLocationField.setText(settings.getStorageLocation());
        }

        if (settings.getDateFormat() != null) {
            dateFormatComboBox.setSelectedItem(settings.getDateFormat());
        }
    }

    private void browseForDirectory() {
        if (!showStorageField) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Storage Location");

        if (settings.getStorageLocation() != null) {
            fileChooser.setCurrentDirectory(new File(settings.getStorageLocation()));
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedDir = fileChooser.getSelectedFile();
            storageLocationField.setText(selectedDir.getAbsolutePath());
        }
    }

    private void onOK(boolean requireStorageLocation) {
        String storageLocation = showStorageField && storageLocationField != null ? storageLocationField.getText().trim() : null;

        if (requireStorageLocation && (storageLocation == null || storageLocation.isEmpty())) {
            JOptionPane.showMessageDialog(this,
                "Storage location is required.\nPlease select a directory to store your notes.",
                "Storage Location Required",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (showStorageField && storageLocation != null && !storageLocation.isEmpty()) {
            File storageDir = new File(storageLocation);
            if (!storageDir.exists()) {
                int choice = JOptionPane.showConfirmDialog(this,
                    "Directory does not exist. Create it?",
                    "Create Directory",
                    JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    if (!storageDir.mkdirs()) {
                        JOptionPane.showMessageDialog(this,
                            "Failed to create directory.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                } else {
                    return;
                }
            }

            if (!storageDir.canWrite()) {
                JOptionPane.showMessageDialog(this,
                    "Cannot write to selected directory.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
                return;
            }

            settings.setStorageLocation(storageLocation);
        }

        settings.setDateFormat((String) dateFormatComboBox.getSelectedItem());

        confirmed = true;
        dispose();
    }

    private void onCancel() {
        confirmed = false;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Show settings dialog and return whether user confirmed
     */
    public static boolean showDialog(Frame parent, Settings settings, boolean requireStorageLocation, boolean showStorageField) {
        SettingsDialog dialog = new SettingsDialog(parent, settings, requireStorageLocation, showStorageField);
        dialog.setVisible(true);
        return dialog.isConfirmed();
    }

    // ---- Change storage location logic ----
    private void onChangeStorage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select New Storage Location");
        // Default to current storage location if available, otherwise use user home
        if (settings != null && settings.getStorageLocation() != null) {
            chooser.setCurrentDirectory(new File(settings.getStorageLocation()));
        } else {
            chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        }

        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File newDir = chooser.getSelectedFile();
        String newPath = newDir.getAbsolutePath();
        String oldPath = settings.getStorageLocation();

        if (oldPath != null && oldPath.equals(newPath)) {
            JOptionPane.showMessageDialog(this, "Selected the current storage location; no changes made.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Ask whether to move existing data
        int choice = JOptionPane.showConfirmDialog(this,
            "Do you want to move all existing data from:\n" + (oldPath == null ? "(no previous storage)" : oldPath) + "\n to:\n" + newPath + "\n\nChoose Yes to move files, No to keep existing files in their current location (only update pointer).",
            "Relocate Data",
            JOptionPane.YES_NO_CANCEL_OPTION);

        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
            return;
        }

        try {
            if (choice == JOptionPane.YES_OPTION && oldPath != null) {
                // perform relocation (copy then delete) first
                relocateDirectory(Paths.get(oldPath), Paths.get(newPath));
            }

            // Persist the pointer to the new location (after successful relocation when moving)
            Settings.writeStorageLocationPointer(newPath);

            // Update settings object's storageLocation and save per-storage settings to new location
            settings.setStorageLocation(newPath);
            settings.save();

            // Update UI field if visible
            if (showStorageField && storageLocationField != null) {
                storageLocationField.setText(newPath);
            }

            JOptionPane.showMessageDialog(this, "Storage location updated successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to change storage location: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void relocateDirectory(Path source, Path target) throws IOException {
        if (source == null) return;
        if (!Files.exists(source)) return; // nothing to move

        // Ensure target directory exists
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        // Copy files and directories
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Path destDir = target.resolve(rel);
                if (!Files.exists(destDir)) {
                    Files.createDirectories(destDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Path destFile = target.resolve(rel);
                Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });

        // After copying, delete source tree
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
