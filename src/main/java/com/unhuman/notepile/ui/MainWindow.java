package com.unhuman.notepile.ui;

import com.unhuman.notepile.model.Settings;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Main application window for NotePile
 */
public class MainWindow extends JFrame {
    private Settings settings;
    private JLabel statusLabel;

    // Notebook/chapter UI
    private JComboBox<String> notebooksCombo;
    private DefaultListModel<String> chaptersModel;
    private JList<String> chaptersList;

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
        // Left pane: notebooks and chapters
        JPanel leftPanel = new JPanel(new BorderLayout(6, 6));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Top: notebook selector + new button
        JPanel topPanel = new JPanel(new BorderLayout(4, 4));
        notebooksCombo = new JComboBox<>();
        notebooksCombo.addActionListener(e -> {
            String selected = (String) notebooksCombo.getSelectedItem();
            refreshChapters(selected);
        });
        topPanel.add(notebooksCombo, BorderLayout.CENTER);

        JButton newNotebookBtn = new JButton("+");
        newNotebookBtn.setToolTipText("New Notebook");
        newNotebookBtn.addActionListener(e -> onNewNotebook());
        topPanel.add(newNotebookBtn, BorderLayout.EAST);

        leftPanel.add(topPanel, BorderLayout.NORTH);

        // Center: chapters list + new chapter button
        chaptersModel = new DefaultListModel<>();
        chaptersList = new JList<>(chaptersModel);
        chaptersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chaptersList.addListSelectionListener((ListSelectionEvent e) -> {
            if (!e.getValueIsAdjusting()) {
                String chapter = chaptersList.getSelectedValue();
                if (chapter != null) {
                    updateStatus("Selected chapter: " + chapter);
                }
            }
        });

        JScrollPane chaptersScroll = new JScrollPane(chaptersList);
        leftPanel.add(chaptersScroll, BorderLayout.CENTER);

        JButton newChapterBtn = new JButton("New Chapter");
        newChapterBtn.addActionListener(e -> onNewChapter());
        leftPanel.add(newChapterBtn, BorderLayout.SOUTH);

        // Right pane: note-taking UI with Add Note button
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addNoteBtn = new JButton("Add Note");
        addNoteBtn.addActionListener(e -> onAddNote());
        topRight.add(addNoteBtn);
        rightPanel.add(topRight, BorderLayout.NORTH);

        JLabel placeholderLabel = new JLabel("Note-taking interface will be implemented here", SwingConstants.CENTER);
        placeholderLabel.setFont(placeholderLabel.getFont().deriveFont(18f));
        placeholderLabel.setForeground(Color.GRAY);
        rightPanel.add(placeholderLabel, BorderLayout.CENTER);

        // Split pane
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setDividerLocation(300);

        // Status bar
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));

        // Main frame layout
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(split, BorderLayout.CENTER);
        contentPanel.add(statusLabel, BorderLayout.SOUTH);

        // --- Menu Bar (File / Help) ---
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem settingsItem = new JMenuItem("Settings...");
        settingsItem.addActionListener(e -> {
            // Open settings dialog (do not require storage or show storage field)
            showSettings(this.settings == null ? new com.unhuman.notepile.model.Settings() : this.settings, false, false);
        });
        fileMenu.add(settingsItem);

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> onExit());
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        setContentPane(contentPanel);
    }

    public void initialize() {
        // Step 1: Try to find existing settings via pointer in user home
        settings = Settings.findExisting();

        if (settings != null && settings.isValid()) {
            updateStatus("Storage location: " + settings.getStorageLocation());
            refreshNotebooks();
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
            refreshNotebooks();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Failed to save/load settings: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

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
                refreshNotebooks();
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

    private void refreshNotebooks() {
        notebooksCombo.removeAllItems();
        chaptersModel.clear();
        if (settings == null || settings.getStorageLocation() == null) return;

        Path storage = Paths.get(settings.getStorageLocation());
        if (!Files.exists(storage) || !Files.isDirectory(storage)) return;

        try {
            Files.list(storage)
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList())
                    .forEach(name -> notebooksCombo.addItem(name));
            // Auto-select first notebook if present
            if (notebooksCombo.getItemCount() > 0) {
                notebooksCombo.setSelectedIndex(0);
            }
        } catch (IOException e) {
            updateStatus("Failed to list notebooks: " + e.getMessage());
        }
    }

    private void refreshChapters(String notebook) {
        chaptersModel.clear();
        if (notebook == null || settings == null || settings.getStorageLocation() == null) return;

        Path nbPath = Paths.get(settings.getStorageLocation(), notebook);
        if (!Files.exists(nbPath) || !Files.isDirectory(nbPath)) return;

        try {
            // Chapters are now directories under the notebook folder
            Files.list(nbPath)
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList())
                    .forEach(chaptersModel::addElement);
        } catch (IOException e) {
            updateStatus("Failed to list chapters: " + e.getMessage());
        }
    }

    private void onNewNotebook() {
        if (settings == null || settings.getStorageLocation() == null) {
            JOptionPane.showMessageDialog(this, "Storage location is not configured.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Enter new notebook name:");
        if (name == null || name.trim().isEmpty()) return;
        String trimmed = name.trim();
        // basic validation: no path separators, no traversal
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            JOptionPane.showMessageDialog(this, "Invalid notebook name. Please avoid path separators or '..'.", "Invalid Name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Path nbPath = Paths.get(settings.getStorageLocation(), trimmed);
        try {
            if (Files.exists(nbPath)) {
                JOptionPane.showMessageDialog(this, "A notebook with that name already exists.", "Duplicate Notebook", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Files.createDirectories(nbPath);
            refreshNotebooks();
            notebooksCombo.setSelectedItem(trimmed);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to create notebook: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onNewChapter() {
        String notebook = (String) notebooksCombo.getSelectedItem();
        if (notebook == null) {
            JOptionPane.showMessageDialog(this, "Please select a notebook first.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Enter new chapter name (e.g. Chapter1):");
        if (name == null || name.trim().isEmpty()) return;
        String trimmed = name.trim();
        // validate: must start with alphanumeric, no separators or traversal
        if (!trimmed.matches("^[A-Za-z0-9].*")) {
            JOptionPane.showMessageDialog(this, "Chapter filename must start with an alphanumeric character.", "Invalid Chapter Name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            JOptionPane.showMessageDialog(this, "Invalid chapter filename. Please avoid path separators or '..'.", "Invalid Name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Path chapterPath = Paths.get(settings.getStorageLocation(), notebook, trimmed);
        try {
            if (Files.exists(chapterPath)) {
                JOptionPane.showMessageDialog(this, "A chapter with that name already exists in this notebook.", "Duplicate Chapter", JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Create chapter as a directory
            Files.createDirectories(chapterPath);
            refreshChapters(notebook);
            chaptersList.setSelectedValue(trimmed, true);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to create chapter: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onAddNote() {
        // Build list of notebooks and chapters for the dialog
        java.util.List<String> notebooks = new java.util.ArrayList<>();
        for (int i = 0; i < notebooksCombo.getItemCount(); i++) notebooks.add((String) notebooksCombo.getItemAt(i));
        String selNb = (String) notebooksCombo.getSelectedItem();
        String selCh = chaptersList.getSelectedValue();

        NoteDialog dlg = new NoteDialog(this, settings.getStorageLocation(), notebooks, selNb, selCh, settings.getDateFormat());
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return;

        java.nio.file.Path saved = dlg.getLastSavedPath();
        if (saved != null) {
            updateStatus("Note saved: " + saved.toString());
            // Refresh chapters for the current notebook so the chapter listing remains visible
            refreshChapters(selNb);
        } else {
            updateStatus("Note saved.");
            // Best-effort refresh
            refreshNotebooks();
        }
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
