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
    private NoteViewerPanel noteViewer;

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
        // Force non-editable combo to avoid showing typed or stale values like ".garbage"
        notebooksCombo.setEditable(false);
        notebooksCombo.addActionListener(e -> {
            String selected = (String) notebooksCombo.getSelectedItem();
            refreshChapters(selected);
        });
        // Defensive listener: before showing the popup, remove any hidden/dot-prefixed items (e.g. .garbage)
        notebooksCombo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                for (int i = notebooksCombo.getItemCount() - 1; i >= 0; i--) {
                    String it = (String) notebooksCombo.getItemAt(i);
                    if (it == null || it.trim().startsWith(".")) {
                        notebooksCombo.removeItemAt(i);
                    }
                }
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
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
                String notebook = (String) notebooksCombo.getSelectedItem();
                if (chapter != null) {
                    updateStatus("Selected chapter: " + chapter);
                    if (noteViewer != null) {
                        noteViewer.setSettings(settings);
                        noteViewer.loadNotes(settings == null ? null : settings.getStorageLocation(), notebook, chapter);
                    }
                }
            }
        });

        JScrollPane chaptersScroll = new JScrollPane(chaptersList);
        leftPanel.add(chaptersScroll, BorderLayout.CENTER);

        JButton newChapterBtn = new JButton("New Chapter");
        newChapterBtn.addActionListener(e -> onNewChapter());
        leftPanel.add(newChapterBtn, BorderLayout.SOUTH);

        // Right pane: note viewer and Add Note button
        JPanel rightPanel = new JPanel(new BorderLayout(6, 6));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Top: Add Note button - align with notebook dropdown on left
        JPanel topRight = new JPanel(new BorderLayout(4, 4));
        JButton addNoteBtn = new JButton("Add Note");
        addNoteBtn.addActionListener(e -> onAddNote());
        topRight.add(addNoteBtn, BorderLayout.WEST);
        rightPanel.add(topRight, BorderLayout.NORTH);

        // Note viewer panel
        noteViewer = new NoteViewerPanel();
        rightPanel.add(noteViewer, BorderLayout.CENTER);

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
            // Ensure .garbage never appears
            if (notebooksCombo != null) notebooksCombo.removeItem(".garbage");
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
            if (notebooksCombo != null) notebooksCombo.removeItem(".garbage");
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
                // If user edited other values in the dialog (dateFormat, sortOrder), prefer those
                if (dialogSettings.getDateFormat() != null && !dialogSettings.getDateFormat().trim().isEmpty()) {
                    loaded.setDateFormat(dialogSettings.getDateFormat());
                }
                if (dialogSettings.getContentDateSortOrder() != null) {
                    loaded.setContentDateSortOrder(dialogSettings.getContentDateSortOrder());
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
            if (dialogSettings.getContentDateSortOrder() != null) {
                this.settings.setContentDateSortOrder(dialogSettings.getContentDateSortOrder());
            }
            try {
                this.settings.save();
                updateStatus("Settings saved - Storage: " + settings.getStorageLocation());

                // Refresh the current note view to apply new sort order
                String currentNotebook = (String) notebooksCombo.getSelectedItem();
                String currentChapter = chaptersList.getSelectedValue();
                if (currentNotebook != null && currentChapter != null && noteViewer != null) {
                    noteViewer.setSettings(this.settings);
                    noteViewer.loadNotes(this.settings.getStorageLocation(), currentNotebook, currentChapter);
                }

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
            java.util.List<String> nbNames = Files.list(storage)
                    .filter(Files::isDirectory)
                    // exclude hidden dot-directories (e.g. .garbage, .viewer)
                    .filter(p -> {
                        String nm = normalizeName(p.getFileName().toString());
                        return nm != null && !nm.startsWith(".");
                    })
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(p -> normalizeName(p.getFileName().toString()))
                    .collect(Collectors.toList());

            // Build a fresh model and apply it atomically to the combo box so no stray items remain
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (String name : nbNames) model.addElement(name);
            // Defensive: remove any literal ".garbage" if present
            model.removeElement(".garbage");
            notebooksCombo.setModel(model);

            // Auto-select first non-dot notebook if present; if none, clear selection
            boolean selected = false;
            for (int i = 0; i < notebooksCombo.getItemCount(); i++) {
                String it = (String) notebooksCombo.getItemAt(i);
                if (it != null && !it.trim().startsWith(".")) {
                    notebooksCombo.setSelectedIndex(i);
                    selected = true;
                    break;
                }
            }
            if (!selected) notebooksCombo.setSelectedItem(null);
        } catch (IOException e) {
            updateStatus("Failed to list notebooks: " + e.getMessage());
        }
    }

    private void refreshChapters(String notebook) {
        if (notebook == null || settings == null || settings.getStorageLocation() == null) return;

        Path nbPath = Paths.get(settings.getStorageLocation(), notebook);
        if (!Files.exists(nbPath) || !Files.isDirectory(nbPath)) return;

        // Preserve current selection if possible
        String previous = chaptersList.getSelectedValue();
        chaptersModel.clear();
        try {
            // Chapters are directories under the notebook folder
            java.util.List<String> names = Files.list(nbPath)
                    .filter(Files::isDirectory)
                    // exclude hidden dot-directories
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
            for (String n : names) chaptersModel.addElement(n);

            // Restore previous selection if present; otherwise select first
            if (previous != null && names.contains(previous)) {
                chaptersList.setSelectedValue(previous, true);
            } else if (chaptersModel.getSize() > 0) {
                chaptersList.setSelectedIndex(0);
            }

            // Load notes for the selected chapter
            String chapter = chaptersList.getSelectedValue();
            if (chapter != null && noteViewer != null) {
                noteViewer.setSettings(settings);
                noteViewer.loadNotes(settings.getStorageLocation(), notebook, chapter);
            }
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
        for (int i = 0; i < notebooksCombo.getItemCount(); i++) {
            String it = (String) notebooksCombo.getItemAt(i);
            if (it == null) continue;
            if (it.trim().startsWith(".")) continue; // skip hidden/internal
            notebooks.add(it);
        }
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

    // Normalize a filesystem name: trim and remove control characters so hidden names are recognized reliably
    private static String normalizeName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        // remove non-printable characters
        trimmed = trimmed.replaceAll("\\p{C}", "");
        return trimmed;
    }
}
