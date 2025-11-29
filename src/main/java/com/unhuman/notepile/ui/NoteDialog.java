package com.unhuman.notepile.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import com.github.lgooddatepicker.components.DatePicker;
import com.github.lgooddatepicker.components.DatePickerSettings;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialog for creating a new note. Collects notebook, chapter, title, people, labels and content.
 */
public class NoteDialog extends JDialog {
    private boolean confirmed = false;

    private JComboBox<String> notebooksCombo;
    private JComboBox<String> chaptersCombo;
    private JTextField titleField;
    private JTextField peopleField;
    private JTextField labelsField;
    private DatePicker datePicker;
    private JTextPane contentArea;
    private javax.swing.text.StyledDocument contentDoc;
    private javax.swing.undo.UndoManager undoManager;

    private final String storageRoot;
    private final String dateFormatPattern;
    private java.nio.file.Path lastSavedPath = null;

    // Track images attached during this editing session for cleanup
    private final java.util.Set<String> attachedImages = new java.util.HashSet<>();

    public static class NoteData {
        public String notebook;
        public String chapter;
        public String title;
        public String people; // comma-separated
        public String labels; // comma-separated
        public String content;
        public String date; // new field for date
    }

    // When editing an existing note, this is the path to the note being edited.
    private java.nio.file.Path editingPath = null;

    // Constructor now receives storageRoot and list of notebooks; chapters will be discovered dynamically
    public NoteDialog(Frame parent, String storageRoot, List<String> notebooks, String selectedNotebook, String selectedChapter, String dateFormatPattern) {
        super(parent, "Add Note", true);
        this.storageRoot = storageRoot;
        this.dateFormatPattern = dateFormatPattern == null ? "yyyy-MM-dd" : dateFormatPattern;

        initComponents(notebooks, selectedNotebook, selectedChapter);
        setSize(700, 600);
        setLocationRelativeTo(parent);
    }

    private void initComponents(List<String> notebooks, String selectedNotebook, String selectedChapter) {
        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Header panel: notebook & chapter dropdowns and title
        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        header.add(new JLabel("Notebook:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        notebooksCombo = new JComboBox<>();
        if (notebooks != null) {
            notebooks.stream()
                    .filter(n -> n != null && !n.startsWith("."))
                    .forEach(n -> notebooksCombo.addItem(n));
        }
        if (selectedNotebook != null) notebooksCombo.setSelectedItem(selectedNotebook);
        header.add(notebooksCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        header.add(new JLabel("Chapter:"), gbc);
        gbc.gridx = 3; gbc.weightx = 1.0;
        chaptersCombo = new JComboBox<>();
        header.add(chaptersCombo, gbc);

        // When notebook changes, refresh chapters from storage (do not reuse the constructor's selectedChapter)
        notebooksCombo.addActionListener(ae -> refreshChaptersFromStorage((String) notebooksCombo.getSelectedItem(), null));

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        header.add(new JLabel("Title:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0;
        titleField = new JTextField();
        header.add(titleField, gbc);
        gbc.gridwidth = 1;

        // Date picker (single source of truth for the date)
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        header.add(new JLabel("Date:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 3;
        DatePickerSettings dps = new DatePickerSettings();
        try {
            dps.setFormatForDatesCommonEra(java.time.format.DateTimeFormatter.ofPattern(this.dateFormatPattern));
        } catch (Exception ex) {
            // ignore - will use default format
        }
        datePicker = new DatePicker(dps);
        header.add(datePicker, gbc);
        gbc.gridwidth = 1;
        // Populate datePicker with today's date
        datePicker.setDate(java.time.LocalDate.now());

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        header.add(new JLabel("People (comma-separated):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 3;
        peopleField = new JTextField();
        header.add(peopleField, gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0;
        header.add(new JLabel("Labels (comma-separated):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 3;
        labelsField = new JTextField();
        header.add(labelsField, gbc);
        gbc.gridwidth = 1;

        main.add(header, BorderLayout.NORTH);

        // Content area - using JTextPane to support inline images
        contentArea = new JTextPane();
        contentDoc = contentArea.getStyledDocument();
        contentArea.setContentType("text/plain"); // Start with plain text mode

        // Initialize UndoManager for undo/redo support
        undoManager = new javax.swing.undo.UndoManager();
        contentDoc.addUndoableEditListener(undoManager);

        JScrollPane contentScroll = new JScrollPane(contentArea);

        // Enable standard cut/copy/paste keyboard shortcuts (Ctrl+X, Ctrl+C, Ctrl+V)
        setupClipboardSupport(contentArea);

        // Enable undo/redo keyboard shortcuts (Ctrl+Z, Ctrl+Y)
        setupUndoRedoSupport(contentArea);

        // Support drag-and-drop attachments: files dropped into the content area are copied into the
        // current chapter's notes/attachments directory and a markdown link is inserted at the caret.
        // Also supports pasting images from clipboard with inline display.
        contentArea.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferHandler.TransferSupport support) {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                    || support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)
                    || support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(TransferHandler.TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    java.awt.datatransfer.Transferable t = support.getTransferable();

                    // Handle pasted images from clipboard - highest priority
                    if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                        java.awt.Image img = (java.awt.Image) t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
                        return handleImagePaste(img);
                    }

                    // Handle dropped/pasted files
                    if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        java.util.List<java.io.File> files = (java.util.List<java.io.File>) t.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                        if (files == null || files.isEmpty()) return false;

                        // Determine target attachments dir for currently selected notebook/chapter in dialog
                        String nb = (String) notebooksCombo.getSelectedItem();
                        String ch = (String) chaptersCombo.getSelectedItem();
                        if (nb == null || ch == null) {
                            JOptionPane.showMessageDialog(NoteDialog.this, "Please select a notebook and chapter before dropping attachments.", "No Target", JOptionPane.WARNING_MESSAGE);
                            return false;
                        }
                        Path attachmentsDir = Paths.get(storageRoot, nb, ch, "notes", "attachments");
                        if (!Files.exists(attachmentsDir)) Files.createDirectories(attachmentsDir);

                        for (java.io.File f : files) {
                            java.nio.file.Path dest = copyAttachment(f.toPath(), attachmentsDir);
                            if (dest != null) {
                                String fname = dest.getFileName().toString();

                                // Track this image for cleanup if not used
                                attachedImages.add(fname);

                                String lower = fname.toLowerCase();
                                // If image, insert inline with markdown, otherwise insert as clickable link button
                                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) {
                                    insertImageInline(dest, fname);
                                } else {
                                    // Insert as clickable link button immediately
                                    insertFileLinkButton(fname, dest);
                                }
                            }
                        }
                        return true;
                    }

                    // Handle text paste
                    if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                        String text = (String) t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                        contentArea.replaceSelection(text);
                        return true;
                    }

                    return false;
                } catch (Exception ex) {
                    ex.printStackTrace(System.err);
                    return false;
                }
            }

            @Override
            public void exportToClipboard(JComponent comp, java.awt.datatransfer.Clipboard clipboard, int action) {
                // Support copy/cut of selected text
                JTextPane ta = (JTextPane) comp;
                String selectedText = ta.getSelectedText();
                if (selectedText != null && !selectedText.isEmpty()) {
                    java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(selectedText);
                    clipboard.setContents(sel, null);
                    if (action == MOVE) {
                        ta.replaceSelection("");
                    }
                }
            }

            @Override
            public int getSourceActions(JComponent c) {
                return COPY_OR_MOVE;
            }
        });
        main.add(contentScroll, BorderLayout.CENTER);

        // Buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("Save");
        ok.addActionListener(e -> onSave());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> onCancel());
        buttons.add(ok);
        buttons.add(cancel);

        main.add(buttons, BorderLayout.SOUTH);

        setContentPane(main);

        // Initial population of chapters for selected notebook (chapters are directories)
        refreshChaptersFromStorage(selectedNotebook, selectedChapter);
    }

    private void refreshChaptersFromStorage(String notebook, String selectChapter) {
        chaptersCombo.removeAllItems();
        if (notebook == null || storageRoot == null) return;
        Path nbPath = Paths.get(storageRoot, notebook);
        if (!Files.exists(nbPath) || !Files.isDirectory(nbPath)) return;
        try (Stream<Path> stream = Files.list(nbPath)) {
            // Chapters are directories
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(ch -> chaptersCombo.addItem(ch));
            if (selectChapter != null) chaptersCombo.setSelectedItem(selectChapter);
            else if (chaptersCombo.getItemCount() > 0) chaptersCombo.setSelectedIndex(0);
        } catch (IOException e) {
            // ignore on dialog load
        }
    }

    private void onSave() {
        // basic validation
        if (notebooksCombo.getItemCount() == 0 || chaptersCombo.getItemCount() == 0) {
            JOptionPane.showMessageDialog(this, "Please ensure a notebook and chapter are available.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // validate date is selected in the picker
        if (datePicker.getDate() == null) {
            JOptionPane.showMessageDialog(this, "Please select a date.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Build NoteData from fields
        NoteData d = new NoteData();
        d.notebook = (String) notebooksCombo.getSelectedItem();
        d.chapter = (String) chaptersCombo.getSelectedItem();
        d.title = titleField.getText();
        try {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(this.dateFormatPattern);
            if (datePicker.getDate() != null) {
                d.date = datePicker.getDate().format(fmt);
            } else {
                d.date = null;
            }
        } catch (Exception ex) {
            d.date = (datePicker.getDate() == null) ? null : datePicker.getDate().toString();
        }
        d.people = peopleField.getText();
        d.labels = labelsField.getText();
        d.content = extractContentWithImages();

        // Attempt to save the note. On failure, keep the dialog open so user doesn't lose work.
        try {
            if (storageRoot == null || storageRoot.trim().isEmpty()) {
                throw new IllegalStateException("Storage root is not configured");
            }
            // Store notes under the chapter directory: <storage>/<notebook>/<chapter>/notes/
            java.nio.file.Path notesDir = java.nio.file.Paths.get(storageRoot, d.notebook, d.chapter, "notes");
            if (!java.nio.file.Files.exists(notesDir)) {
                java.nio.file.Files.createDirectories(notesDir);
            }
            // Create attachments subdir placeholder
            java.nio.file.Path attachmentsDir = notesDir.resolve("attachments");
            if (!java.nio.file.Files.exists(attachmentsDir)) {
                java.nio.file.Files.createDirectories(attachmentsDir);
            }
            // Quick writable check: try to create and delete a temporary file in the notes directory.
            try {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile(notesDir, "write-test-", ".tmp");
                java.nio.file.Files.deleteIfExists(tmp);
            } catch (Exception writeEx) {
                throw new IOException("Unable to write to notes directory: " + notesDir.toString() + " - " + writeEx.getMessage(), writeEx);
            }

            // Build a safe base filename from Date only (always use ISO yyyy-MM-dd for filenames)
            String rawDate;
            if (datePicker.getDate() != null) {
                rawDate = datePicker.getDate().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                rawDate = d.date == null ? "" : d.date.trim();
            }
             // Sanitize: allow letters, digits, dash, underscore and dot; replace others with underscore
             String base = rawDate.replaceAll("[^A-Za-z0-9._-]", "_");
             // Trim repeated underscores
             base = base.replaceAll("_+", "_");
             // Trim leading/trailing separators
             base = base.replaceAll("^[._-]+|[._-]+$", "");
             if (base.isEmpty()) base = "date";

            // Always include a counter suffix. Find existing files that match base-<number>.json and compute next counter
            java.util.concurrent.atomic.AtomicInteger maxCounter = new java.util.concurrent.atomic.AtomicInteger(0);
            Pattern p = Pattern.compile(Pattern.quote(base) + "-(\\d+)\\.json$");
            try (Stream<Path> s = java.nio.file.Files.list(notesDir)) {
                s.filter(java.nio.file.Files::isRegularFile).forEach(pth -> {
                    String nm = pth.getFileName().toString();
                    Matcher m = p.matcher(nm);
                    if (m.matches()) {
                        try {
                            int v = Integer.parseInt(m.group(1));
                            maxCounter.updateAndGet(curr -> Math.max(curr, v));
                        } catch (NumberFormatException ignore) {}
                    }
                });
            }
            int next = maxCounter.get() + 1;
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

            java.util.Map<String, Object> noteObj = new java.util.LinkedHashMap<>();
            noteObj.put("notebook", d.notebook);
            noteObj.put("chapter", d.chapter);
            noteObj.put("title", d.title);
            noteObj.put("people", d.people);
            noteObj.put("labels", d.labels);
            noteObj.put("date", d.date);
            noteObj.put("content", d.content);
            noteObj.put("createdAt", System.currentTimeMillis());

            // ----- Editing vs Creating logic -----
            java.nio.file.Path notePath = null;
            if (this.editingPath == null) {
                // Create new note file as before
                int attempts = 0;
                while (true) {
                    String filename = base + "-" + next + ".json";
                    notePath = notesDir.resolve(filename);
                    try {
                        java.nio.file.Files.createFile(notePath);
                        break; // success
                    } catch (java.nio.file.FileAlreadyExistsException fae) {
                        next++;
                        attempts++;
                        if (attempts > 10000) {
                            throw new IOException("Too many filename collisions for base: " + base);
                        }
                        // try next
                    }
                }
            } else {
                // Editing existing note: decide whether we can overwrite same file or need to create a new one
                java.nio.file.Path original = this.editingPath;
                // Try to preserve createdAt from original if present
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String,Object> orig = mapper.readValue(original.toFile(), java.util.Map.class);
                    if (orig.containsKey("createdAt")) {
                        noteObj.put("createdAt", orig.get("createdAt"));
                    }
                } catch (Exception ignore) { }

                // If original parent equals target notesDir and filename matches base-<n>.json pattern starting with base, overwrite original.
                if (original.getParent() != null && original.getParent().equals(notesDir)) {
                    String origName = original.getFileName().toString();
                    if (origName.startsWith(base + "-") && origName.endsWith(".json")) {
                        notePath = original; // overwrite
                    }
                }

                if (notePath == null) {
                    // create a new file in target dir with next counter
                    int attempts = 0;
                    while (true) {
                        String filename = base + "-" + next + ".json";
                        notePath = notesDir.resolve(filename);
                        try {
                            java.nio.file.Files.createFile(notePath);
                            break;
                        } catch (java.nio.file.FileAlreadyExistsException fae) {
                            next++;
                            attempts++;
                            if (attempts > 10000) throw new IOException("Too many filename collisions for base: " + base);
                        }
                    }
                }
            }

            // Write JSON into the selected file (overwrite if existing)
            try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(notePath, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writeValue(os, noteObj);
            }

            // If we were editing and created a new file (moved/renamed), delete the old one
            if (this.editingPath != null && !this.editingPath.equals(notePath)) {
                try { java.nio.file.Files.deleteIfExists(this.editingPath); } catch (Exception ignored) {}
            }

            // Clean up any attached images that weren't included in the final content
            cleanupUnusedImages(d.content, d.notebook, d.chapter);

            // success: set lastSavedPath, mark confirmed, and close
            this.lastSavedPath = notePath;
            this.confirmed = true;
            dispose();
        } catch (Exception ex) {
            // Log stack trace to stderr
            ex.printStackTrace(System.err);
            // Show full stack trace in a scrollable dialog so user can see detailed failure
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            ex.printStackTrace(pw);
            pw.flush();
            String stack = sw.toString();
            JTextArea ta = new JTextArea(stack);
            ta.setEditable(false);
            ta.setRows(20);
            ta.setColumns(80);
            JScrollPane sp = new JScrollPane(ta);
            JOptionPane.showMessageDialog(this, sp, "Failed to save note: " + ex.getMessage(), JOptionPane.ERROR_MESSAGE);
            // do not close; let user retry or cancel
        }
    }


    private void onCancel() {
        confirmed = false;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public NoteData getNoteData() {
        if (!confirmed) return null;
        NoteData d = new NoteData();
        d.notebook = (String) notebooksCombo.getSelectedItem();
        d.chapter = (String) chaptersCombo.getSelectedItem();
        d.title = titleField.getText();
        // Format date from datePicker using configured pattern
        try {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(this.dateFormatPattern);
            if (datePicker.getDate() != null) {
                d.date = datePicker.getDate().format(fmt);
            } else {
                d.date = null;
            }
        } catch (Exception ex) {
            d.date = (datePicker.getDate() == null) ? null : datePicker.getDate().toString();
        }
        d.people = peopleField.getText();
        d.labels = labelsField.getText();
        d.content = extractContentWithImages();
        return d;
    }

    public java.nio.file.Path getLastSavedPath() {
        return lastSavedPath;
    }

    /**
     * Append text to the content area at current caret position. Public so other UI components
     * (like the viewer's drag-and-drop handler) can insert attachment links.
     */
    public void insertIntoContent(String text) {
        if (text == null || text.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            int pos = contentArea.getCaretPosition();
            contentArea.setCaretPosition(pos);
            contentArea.replaceSelection(text);
        });
    }

    /**
     * Populate the dialog with an existing note file for editing. The file must be a JSON note
     * previously written by the application. This will set editingPath so save() will update
     * the existing file (or move/renamed it if notebook/chapter/date changed).
     */
    public void populateForEdit(java.nio.file.Path notePath) throws IOException {
        if (notePath == null || !java.nio.file.Files.exists(notePath)) {
            throw new IOException("Note file does not exist: " + notePath);
        }
        // Read JSON into a map
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        java.util.Map<String,Object> m = mapper.readValue(notePath.toFile(), java.util.Map.class);
        // Fill fields where present
        String nb = (String) m.get("notebook");
        String ch = (String) m.get("chapter");
        if (nb != null) notebooksCombo.setSelectedItem(nb);
        refreshChaptersFromStorage(nb, ch);
        if (ch != null) chaptersCombo.setSelectedItem(ch);
        titleField.setText((String) m.getOrDefault("title", ""));
        peopleField.setText((String) m.getOrDefault("people", ""));
        labelsField.setText((String) m.getOrDefault("labels", ""));

        // Parse content and convert image tags to components
        String content = (String) m.getOrDefault("content", "");
        parseAndInsertContent(content, nb, ch);

        String d = (String) m.get("date");
        if (d != null) {
            try {
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(this.dateFormatPattern);
                datePicker.setDate(java.time.LocalDate.parse(d, fmt));
            } catch (Exception ex) {
                try { datePicker.setDate(java.time.LocalDate.parse(d)); } catch (Exception ignore) {}
            }
        }

        // Discard all undo history so that undo only goes back to this loaded state, not to empty
        if (undoManager != null) {
            undoManager.discardAllEdits();
        }

        this.editingPath = notePath;
        setTitle("Edit Note");
    }

    // Copy an attachment into the attachmentsDir, avoiding name collisions by appending a counter.
    private java.nio.file.Path copyAttachment(java.nio.file.Path src, java.nio.file.Path attachmentsDir) {
        try {
            String fileName = src.getFileName().toString();
            java.nio.file.Path dest = attachmentsDir.resolve(fileName);
            if (java.nio.file.Files.exists(dest)) {
                String base = fileName;
                String ext = "";
                int idx = fileName.lastIndexOf('.');
                if (idx > 0) {
                    base = fileName.substring(0, idx);
                    ext = fileName.substring(idx);
                }
                int counter = 1;
                while (java.nio.file.Files.exists(dest)) {
                    String candidate = base + "-" + counter + ext;
                    dest = attachmentsDir.resolve(candidate);
                    counter++;
                    if (counter > 10000) break;
                }
            }
            java.nio.file.Files.copy(src, dest);
            return dest;
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            return null;
        }
    }

    /**
     * Clean up any images that were attached during editing but not included in the final saved content.
     */
    private void cleanupUnusedImages(String content, String notebook, String chapter) {
        if (attachedImages.isEmpty()) return;

        try {
            Path attachmentsDir = Paths.get(storageRoot, notebook, chapter, "notes", "attachments");
            if (!Files.exists(attachmentsDir)) return;

            // Check each attached image to see if it's referenced in the content
            for (String fileName : attachedImages) {
                // Check if this image is referenced in the content
                // Look for both HTML img tags and markdown image syntax
                boolean referenced = content.contains("attachments/" + fileName) ||
                                    content.contains("(attachments/" + fileName + ")");

                if (!referenced) {
                    // Image was attached but not used - delete it
                    Path imageFile = attachmentsDir.resolve(fileName);
                    try {
                        if (Files.exists(imageFile)) {
                            Files.delete(imageFile);
                            System.out.println("Cleaned up unused image: " + fileName);
                        }
                    } catch (Exception ex) {
                        System.err.println("Failed to delete unused image " + fileName + ": " + ex.getMessage());
                    }
                }
            }

            // Clear the tracking set after cleanup
            attachedImages.clear();

        } catch (Exception ex) {
            System.err.println("Error during image cleanup: " + ex.getMessage());
        }
    }

    /**
     * Setup keyboard shortcuts for clipboard operations in the text pane.
     */
    private void setupClipboardSupport(JTextPane textPane) {
        // Standard Swing JTextPane already supports Ctrl+X, Ctrl+C, Ctrl+V for text
        // We just need to ensure the TransferHandler supports it, which we've done above
        // Add explicit keybindings to make sure they work
        textPane.getInputMap().put(KeyStroke.getKeyStroke("control X"), "cut-to-clipboard");
        textPane.getInputMap().put(KeyStroke.getKeyStroke("control C"), "copy-to-clipboard");
        textPane.getInputMap().put(KeyStroke.getKeyStroke("control V"), "paste-from-clipboard");

        // Setup right-click context menu
        setupContextMenu(textPane);
    }

    /**
     * Setup right-click context menu for the editor with Cut/Copy/Paste/Delete/Attach File options.
     */
    private void setupContextMenu(JTextPane textPane) {
        JPopupMenu contextMenu = new JPopupMenu();

        // Cut menu item
        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setAccelerator(KeyStroke.getKeyStroke("control X"));
        cutItem.addActionListener(e -> textPane.cut());
        contextMenu.add(cutItem);

        // Copy menu item
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke("control C"));
        copyItem.addActionListener(e -> textPane.copy());
        contextMenu.add(copyItem);

        // Paste menu item
        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke("control V"));
        pasteItem.addActionListener(e -> textPane.paste());
        contextMenu.add(pasteItem);

        // Delete menu item
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.setAccelerator(KeyStroke.getKeyStroke("DELETE"));
        deleteItem.addActionListener(e -> textPane.replaceSelection(""));
        contextMenu.add(deleteItem);

        contextMenu.addSeparator();

        // Attach File menu item
        JMenuItem attachFileItem = new JMenuItem("Attach File...");
        attachFileItem.addActionListener(e -> attachFile());
        contextMenu.add(attachFileItem);

        // Add popup listener to enable/disable menu items based on state
        textPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu, cutItem, copyItem, pasteItem, deleteItem);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu, cutItem, copyItem, pasteItem, deleteItem);
                }
            }
        });
    }

    /**
     * Show context menu and enable/disable items based on current state.
     */
    private void showContextMenu(MouseEvent e, JPopupMenu menu, JMenuItem cutItem,
                                   JMenuItem copyItem, JMenuItem pasteItem, JMenuItem deleteItem) {
        // Enable/disable based on selection
        boolean hasSelection = contentArea.getSelectionStart() != contentArea.getSelectionEnd();
        cutItem.setEnabled(hasSelection);
        copyItem.setEnabled(hasSelection);
        deleteItem.setEnabled(hasSelection);

        // Enable paste only if clipboard has content
        boolean canPaste = false;
        try {
            java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            java.awt.datatransfer.Transferable contents = clipboard.getContents(null);
            canPaste = contents != null && (
                contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor) ||
                contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor) ||
                contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
            );
        } catch (Exception ex) {
            // If we can't check clipboard, assume we can paste
            canPaste = true;
        }
        pasteItem.setEnabled(canPaste);

        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /**
     * Open a file chooser dialog to attach a file to the note.
     */
    private void attachFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select File to Attach");
        fileChooser.setMultiSelectionEnabled(true);

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            java.io.File[] selectedFiles = fileChooser.getSelectedFiles();
            if (selectedFiles.length == 0) {
                selectedFiles = new java.io.File[] { fileChooser.getSelectedFile() };
            }

            // Get current notebook and chapter
            String nb = (String) notebooksCombo.getSelectedItem();
            String ch = (String) chaptersCombo.getSelectedItem();

            if (nb == null || ch == null) {
                JOptionPane.showMessageDialog(this,
                    "Please select a notebook and chapter before attaching files.",
                    "No Target", JOptionPane.WARNING_MESSAGE);
                return;
            }

            try {
                Path attachmentsDir = Paths.get(storageRoot, nb, ch, "notes", "attachments");
                if (!Files.exists(attachmentsDir)) {
                    Files.createDirectories(attachmentsDir);
                }

                for (java.io.File file : selectedFiles) {
                    Path dest = copyAttachment(file.toPath(), attachmentsDir);
                    if (dest != null) {
                        String fileName = dest.getFileName().toString();

                        // Track this file for cleanup if not used
                        attachedImages.add(fileName);

                        String lower = fileName.toLowerCase();
                        // If image, insert inline with markdown, otherwise insert as clickable link button
                        if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                            lower.endsWith(".jpeg") || lower.endsWith(".gif")) {
                            insertImageInline(dest, fileName);
                        } else {
                            // Insert as clickable link button immediately
                            insertFileLinkButton(fileName, dest);
                        }
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "Failed to attach file: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Setup undo/redo keyboard shortcuts (Ctrl+Z and Ctrl+Y).
     */
    private void setupUndoRedoSupport(JTextPane textPane) {
        // Ctrl+Z for Undo
        textPane.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
            java.awt.event.InputEvent.CTRL_DOWN_MASK), "undo");
        textPane.getActionMap().put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                }
            }
        });

        // Ctrl+Y for Redo
        textPane.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y,
            java.awt.event.InputEvent.CTRL_DOWN_MASK), "redo");
        textPane.getActionMap().put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                }
            }
        });
    }

    /**
     * Handle pasted image from clipboard by saving it to attachments and inserting inline with resize capability.
     */
    private boolean handleImagePaste(java.awt.Image img) {
        try {
            // Determine target attachments dir
            String nb = (String) notebooksCombo.getSelectedItem();
            String ch = (String) chaptersCombo.getSelectedItem();
            if (nb == null || ch == null) {
                JOptionPane.showMessageDialog(this, "Please select a notebook and chapter before pasting images.", "No Target", JOptionPane.WARNING_MESSAGE);
                return false;
            }
            Path attachmentsDir = Paths.get(storageRoot, nb, ch, "notes", "attachments");
            if (!Files.exists(attachmentsDir)) Files.createDirectories(attachmentsDir);

            // Generate unique filename with timestamp
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new java.util.Date());
            String fileName = "pasted-" + timestamp + ".png";
            java.nio.file.Path dest = attachmentsDir.resolve(fileName);

            // Ensure filename is unique
            int counter = 1;
            while (Files.exists(dest)) {
                fileName = "pasted-" + timestamp + "-" + counter + ".png";
                dest = attachmentsDir.resolve(fileName);
                counter++;
            }

            // Convert Image to BufferedImage and save as PNG
            java.awt.image.BufferedImage buffered;
            if (img instanceof java.awt.image.BufferedImage) {
                buffered = (java.awt.image.BufferedImage) img;
            } else {
                buffered = new java.awt.image.BufferedImage(img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = buffered.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
            }

            javax.imageio.ImageIO.write(buffered, "png", dest.toFile());

            // Track this image for cleanup if not used
            attachedImages.add(fileName);

            // Insert image inline in the editor
            insertImageInline(dest, fileName);

            return true;
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            JOptionPane.showMessageDialog(this, "Failed to paste image: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * Parse content and insert text/image components into the editor.
     * Converts <img> tags back to ResizableImageComponent for editing.
     * Converts markdown links to clickable links for non-image files.
     */
    private void parseAndInsertContent(String content, String notebook, String chapter) {
        if (content == null || content.isEmpty()) {
            contentArea.setText("");
            return;
        }

        // Clear existing content
        contentArea.setText("");

        // Pattern to match our saved img tags: <img src="attachments/file.png" alt="file.png" width="123" />
        java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile(
            "<img\\s+src=\"attachments/([^\"]+)\"[^>]*width=\"(\\d+)\"[^>]*/>",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );

        // Pattern to match markdown links: [filename](attachments/filename)
        java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile(
            "\\[([^\\]]+)\\]\\(attachments/([^)]+)\\)"
        );

        // First pass: process images
        java.util.regex.Matcher imgMatcher = imgPattern.matcher(content);
        StringBuilder tempContent = new StringBuilder(content);
        java.util.List<ImageReplacement> imageReplacements = new java.util.ArrayList<>();

        while (imgMatcher.find()) {
            String fileName = imgMatcher.group(1);
            int width = Integer.parseInt(imgMatcher.group(2));
            imageReplacements.add(new ImageReplacement(imgMatcher.start(), imgMatcher.end(), fileName, width));
        }

        // Process from end to start to maintain indices
        for (int i = imageReplacements.size() - 1; i >= 0; i--) {
            ImageReplacement ir = imageReplacements.get(i);
            tempContent.replace(ir.start, ir.end, "\u0000IMG" + i + "\u0000");
        }

        // Second pass: process markdown links for non-images
        String processedContent = tempContent.toString();
        java.util.regex.Matcher linkMatcher = linkPattern.matcher(processedContent);
        java.util.List<LinkReplacement> linkReplacements = new java.util.ArrayList<>();

        while (linkMatcher.find()) {
            String displayText = linkMatcher.group(1);
            String fileName = linkMatcher.group(2);
            linkReplacements.add(new LinkReplacement(linkMatcher.start(), linkMatcher.end(), displayText, fileName));
        }

        // Process links from end to start
        for (int i = linkReplacements.size() - 1; i >= 0; i--) {
            LinkReplacement lr = linkReplacements.get(i);
            processedContent = processedContent.substring(0, lr.start) + "\u0000LINK" + i + "\u0000" + processedContent.substring(lr.end);
        }

        // Now insert everything in order
        int pos = 0;
        while (pos < processedContent.length()) {
            char ch = processedContent.charAt(pos);

            if (ch == '\u0000') {
                // Found a placeholder
                int endPos = processedContent.indexOf('\u0000', pos + 1);
                if (endPos > pos) {
                    String placeholder = processedContent.substring(pos + 1, endPos);

                    if (placeholder.startsWith("IMG")) {
                        int imgIndex = Integer.parseInt(placeholder.substring(3));
                        insertImageComponent(imageReplacements.get(imgIndex), notebook, chapter);
                    } else if (placeholder.startsWith("LINK")) {
                        int linkIndex = Integer.parseInt(placeholder.substring(4));
                        insertClickableLink(linkReplacements.get(linkIndex), notebook, chapter);
                    }

                    pos = endPos + 1;
                    continue;
                }
            }

            // Regular character - find next placeholder or end
            int nextPlaceholder = processedContent.indexOf('\u0000', pos);
            if (nextPlaceholder == -1) nextPlaceholder = processedContent.length();

            String text = processedContent.substring(pos, nextPlaceholder);
            if (!text.isEmpty()) {
                contentArea.replaceSelection(text);
            }
            pos = nextPlaceholder;
        }
    }

    // Helper classes for tracking replacements
    private static class ImageReplacement {
        int start, end, width;
        String fileName;
        ImageReplacement(int start, int end, String fileName, int width) {
            this.start = start; this.end = end; this.fileName = fileName; this.width = width;
        }
    }

    private static class LinkReplacement {
        int start, end;
        String displayText, fileName;
        LinkReplacement(int start, int end, String displayText, String fileName) {
            this.start = start; this.end = end; this.displayText = displayText; this.fileName = fileName;
        }
    }

    /**
     * Insert an image component at the current position.
     */
    private void insertImageComponent(ImageReplacement ir, String notebook, String chapter) {
        try {
            Path attachmentsDir = Paths.get(storageRoot, notebook, chapter, "notes", "attachments");
            Path imagePath = attachmentsDir.resolve(ir.fileName);

            if (Files.exists(imagePath)) {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
                if (img != null) {
                    // Track this image for cleanup if removed during editing
                    attachedImages.add(ir.fileName);

                    // Create component with the saved width
                    ResizableImageComponent imageComp = new ResizableImageComponent(img, ir.fileName, imagePath, ir.width);
                    contentArea.insertComponent(imageComp);
                } else {
                    // Fallback: insert as text
                    contentArea.replaceSelection("[Image: " + ir.fileName + "]");
                }
            } else {
                // File doesn't exist, insert placeholder text
                contentArea.replaceSelection("![" + ir.fileName + " - FILE NOT FOUND]");
            }
        } catch (Exception ex) {
            contentArea.replaceSelection("[Image: " + ir.fileName + " - ERROR]");
        }
    }

    /**
     * Insert a clickable link component for non-image files.
     */
    private void insertClickableLink(LinkReplacement lr, String notebook, String chapter) {
        try {
            Path attachmentsDir = Paths.get(storageRoot, notebook, chapter, "notes", "attachments");
            Path filePath = attachmentsDir.resolve(lr.fileName);

            // Track this file for cleanup if removed during editing
            attachedImages.add(lr.fileName);

            // Create a clickable button/link
            JButton linkButton = new JButton(lr.displayText);
            linkButton.setBorderPainted(false);
            linkButton.setContentAreaFilled(false);
            linkButton.setForeground(Color.BLUE);
            linkButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            linkButton.setFocusable(false);

            // Store original font for hover effect
            Font originalFont = linkButton.getFont();
            Font underlinedFont = originalFont.deriveFont(originalFont.getAttributes());
            java.util.Map<java.awt.font.TextAttribute, Object> attributes = new java.util.HashMap<>(underlinedFont.getAttributes());
            attributes.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
            Font underlineFont = originalFont.deriveFont(attributes);

            // Store fileName for extraction when saving
            linkButton.putClientProperty("fileName", lr.fileName);

            // Add underline on hover using font attributes (no size change)
            linkButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    linkButton.setFont(underlineFont);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    linkButton.setFont(originalFont);
                }
            });

            // Open file when clicked
            linkButton.addActionListener(e -> openFileExternally(filePath));

            contentArea.insertComponent(linkButton);
        } catch (Exception ex) {
            contentArea.replaceSelection("[" + lr.displayText + "]");
        }
    }

    /**
     * Insert a clickable link button for a file (used when dropping/attaching files).
     * This creates the same type of button that appears when loading notes.
     */
    private void insertFileLinkButton(String displayText, Path filePath) {
        try {
            // Create a clickable button/link
            JButton linkButton = new JButton(displayText);
            linkButton.setBorderPainted(false);
            linkButton.setContentAreaFilled(false);
            linkButton.setForeground(Color.BLUE);
            linkButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            linkButton.setFocusable(false);

            // Store original font for hover effect
            Font originalFont = linkButton.getFont();
            Font underlinedFont = originalFont.deriveFont(originalFont.getAttributes());
            java.util.Map<java.awt.font.TextAttribute, Object> attributes = new java.util.HashMap<>(underlinedFont.getAttributes());
            attributes.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
            Font underlineFont = originalFont.deriveFont(attributes);

            // Store fileName for extraction when saving
            String fileName = filePath.getFileName().toString();
            linkButton.putClientProperty("fileName", fileName);

            // Add underline on hover using font attributes (no size change)
            linkButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    linkButton.setFont(underlineFont);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    linkButton.setFont(originalFont);
                }
            });

            // Open file when clicked
            linkButton.addActionListener(e -> openFileExternally(filePath));

            contentArea.insertComponent(linkButton);
        } catch (Exception ex) {
            // Fallback to plain text if button creation fails
            contentArea.replaceSelection("[" + displayText + "]");
        }
    }

    /**
     * Open a file with the system's default application.
     */
    private void openFileExternally(Path filePath) {
        try {
            if (!Files.exists(filePath)) {
                JOptionPane.showMessageDialog(this,
                    "File not found: " + filePath.getFileName(),
                    "File Not Found", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Use Desktop API to open with default application
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(filePath.toFile());
                } else {
                    JOptionPane.showMessageDialog(this,
                        "Cannot open files on this system.",
                        "Not Supported", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(this,
                    "Desktop operations not supported on this system.",
                    "Not Supported", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Failed to open file: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Extract content from the text pane, converting embedded image components to markdown.
     */
    private String extractContentWithImages() {
        try {
            StringBuilder result = new StringBuilder();
            javax.swing.text.Element root = contentDoc.getDefaultRootElement();

            for (int i = 0; i < root.getElementCount(); i++) {
                javax.swing.text.Element paragraph = root.getElement(i);
                for (int j = 0; j < paragraph.getElementCount(); j++) {
                    javax.swing.text.Element elem = paragraph.getElement(j);

                    // Check if this element contains a component
                    javax.swing.text.AttributeSet attrs = elem.getAttributes();
                    Object component = attrs.getAttribute(javax.swing.text.StyleConstants.ComponentAttribute);

                    if (component instanceof ResizableImageComponent) {
                        // Convert image component to markdown
                        ResizableImageComponent imgComp = (ResizableImageComponent) component;
                        result.append(imgComp.getMarkdown());
                    } else if (component instanceof JButton) {
                        // Convert file link button to markdown
                        JButton btn = (JButton) component;
                        String displayText = btn.getText();
                        // Remove HTML tags if present (from hover effect)
                        displayText = displayText.replaceAll("<[^>]*>", "");

                        // Extract filename from button's action listeners
                        // We need to store this in the button's client property
                        String fileName = (String) btn.getClientProperty("fileName");
                        if (fileName != null) {
                            result.append("[").append(displayText).append("](attachments/").append(fileName).append(")");
                        } else {
                            // Fallback - just use the display text
                            result.append(displayText);
                        }
                    } else {
                        // Regular text
                        int start = elem.getStartOffset();
                        int end = elem.getEndOffset();
                        String text = contentDoc.getText(start, end - start);
                        result.append(text);
                    }
                }
            }

            return result.toString();
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            // Fallback to plain getText
            return contentArea.getText();
        }
    }

    /**
     * Insert an image inline in the text pane - displays the actual image with resize capability.
     * The markdown reference is stored as an attribute so it can be saved.
     */
    private void insertImageInline(java.nio.file.Path imagePath, String fileName) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Load the image
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
                if (img == null) {
                    // Fallback to markdown text if image can't be loaded
                    contentArea.replaceSelection("![" + fileName + "](attachments/" + fileName + ")\n");
                    return;
                }

                // Create a resizable image component
                ResizableImageComponent imageComp = new ResizableImageComponent(img, fileName, imagePath);

                // Insert the component into the text pane
                contentArea.setCaretPosition(contentArea.getCaretPosition());
                contentArea.insertComponent(imageComp);
                contentArea.replaceSelection("\n");

            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                // Fallback to markdown text
                contentArea.replaceSelection("![" + fileName + "](attachments/" + fileName + ")\n");
            }
        });
    }

    /**
     * Component that displays an image inline with resize capability.
     */
    private static class ResizableImageComponent extends JPanel {
        private final java.awt.image.BufferedImage originalImage;
        private final String fileName;
        private final java.nio.file.Path imagePath;
        private JLabel imageLabel;
        private JLabel infoLabel;
        private int currentWidth;
        private int currentHeight;
        private final int originalWidth;
        private final int originalHeight;

        public ResizableImageComponent(java.awt.image.BufferedImage img, String fileName, java.nio.file.Path imagePath) {
            this(img, fileName, imagePath, -1);
        }

        public ResizableImageComponent(java.awt.image.BufferedImage img, String fileName, java.nio.file.Path imagePath, int initialWidth) {
            super(new BorderLayout());
            this.originalImage = img;
            this.fileName = fileName;
            this.imagePath = imagePath;
            this.originalWidth = img.getWidth();
            this.originalHeight = img.getHeight();

            // Determine initial display size
            if (initialWidth > 0) {
                // Use the provided width (from saved state)
                currentWidth = initialWidth;
                currentHeight = (int) ((double) originalHeight * initialWidth / originalWidth);
            } else {
                // Default: max 600px wide
                int maxWidth = 600;
                if (originalWidth > maxWidth) {
                    currentWidth = maxWidth;
                    currentHeight = (int) ((double) originalHeight * maxWidth / originalWidth);
                } else {
                    currentWidth = originalWidth;
                    currentHeight = originalHeight;
                }
            }

            initUI();
        }

        private void initUI() {
            setOpaque(true);
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            ));

            // Image label with resize handle overlay
            updateImageDisplay();

            // Create a layered panel to show the image with resize handles
            JLayeredPane layeredPane = new JLayeredPane();
            layeredPane.setOpaque(false);

            // Add image label to the layered pane
            imageLabel.setBounds(0, 0, currentWidth, currentHeight);
            layeredPane.add(imageLabel, Integer.valueOf(0));

            // Create resize handle (bottom-right corner)
            JPanel resizeHandle = new JPanel();
            resizeHandle.setOpaque(true);
            resizeHandle.setBackground(new Color(100, 150, 255, 200));
            resizeHandle.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
            int handleSize = 12;
            resizeHandle.setBounds(currentWidth - handleSize, currentHeight - handleSize, handleSize, handleSize);
            layeredPane.add(resizeHandle, Integer.valueOf(1));

            // Set preferred size for the layered pane
            layeredPane.setPreferredSize(new Dimension(currentWidth, currentHeight));

            // Add mouse drag listener for resize handle
            MouseAdapter resizeDragger = new MouseAdapter() {
                private int startX, startY;
                private int startWidth, startHeight;

                @Override
                public void mousePressed(MouseEvent e) {
                    startX = e.getXOnScreen();
                    startY = e.getYOnScreen();
                    startWidth = currentWidth;
                    startHeight = currentHeight;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int deltaX = e.getXOnScreen() - startX;

                    // Calculate new width (use deltaX as primary)
                    int newWidth = startWidth + deltaX;

                    // Enforce min/max bounds
                    if (newWidth < 50) newWidth = 50;
                    if (newWidth > originalWidth * 3) newWidth = originalWidth * 3;

                    // Calculate height maintaining aspect ratio
                    int newHeight = (int) ((double) originalHeight * newWidth / originalWidth);

                    // Update current size
                    currentWidth = newWidth;
                    currentHeight = newHeight;

                    // Update display
                    updateImageDisplay();
                    imageLabel.setBounds(0, 0, currentWidth, currentHeight);
                    resizeHandle.setBounds(currentWidth - handleSize, currentHeight - handleSize, handleSize, handleSize);
                    layeredPane.setPreferredSize(new Dimension(currentWidth, currentHeight));
                    infoLabel.setText(currentWidth + "×" + currentHeight + " px");

                    // Revalidate hierarchy
                    revalidate();
                    repaint();

                    // Force parent to revalidate
                    Container parent = getParent();
                    while (parent != null) {
                        parent.revalidate();
                        parent.repaint();
                        if (parent instanceof JTextPane) break;
                        parent = parent.getParent();
                    }
                }
            };

            resizeHandle.addMouseListener(resizeDragger);
            resizeHandle.addMouseMotionListener(resizeDragger);

            add(layeredPane, BorderLayout.CENTER);

            // Info panel at bottom with filename, size, and resize button
            JPanel bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.setOpaque(false);

            infoLabel = new JLabel(currentWidth + "×" + currentHeight + " px");
            infoLabel.setFont(infoLabel.getFont().deriveFont(10f));
            infoLabel.setForeground(Color.GRAY);
            bottomPanel.add(infoLabel, BorderLayout.WEST);

            JButton resizeBtn = new JButton("Resize");
            resizeBtn.setFont(resizeBtn.getFont().deriveFont(10f));
            resizeBtn.setMargin(new Insets(2, 6, 2, 6));
            resizeBtn.addActionListener(e -> showResizeDialog());
            bottomPanel.add(resizeBtn, BorderLayout.EAST);

            add(bottomPanel, BorderLayout.SOUTH);

            // Double-click also opens resize dialog
            imageLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        showResizeDialog();
                    }
                }
            });

            // Tooltip with filename and original size
            String tooltip = fileName + " (" + originalWidth + "×" + originalHeight + " original)";
            setToolTipText(tooltip);
            imageLabel.setToolTipText(tooltip);
        }

        private void updateImageDisplay() {
            java.awt.Image scaled = originalImage.getScaledInstance(
                currentWidth, currentHeight, java.awt.Image.SCALE_SMOOTH);

            if (imageLabel == null) {
                imageLabel = new JLabel(new ImageIcon(scaled));
                imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            } else {
                imageLabel.setIcon(new ImageIcon(scaled));
            }
        }

        private void showResizeDialog() {
            String[] options = {"25%", "50%", "75%", "100%", "150%", "200%", "Custom..."};
            int currentPercent = (int) Math.round(100.0 * currentWidth / originalWidth);
            String defaultOption = currentPercent + "%";

            // Try to select the closest preset
            String selected = "100%";
            for (String opt : options) {
                if (opt.equals(currentPercent + "%")) {
                    selected = opt;
                    break;
                }
            }

            int choice = JOptionPane.showOptionDialog(
                this,
                "Resize image (maintains aspect ratio)\nOriginal: " + originalWidth + "×" + originalHeight +
                "\nCurrent: " + currentWidth + "×" + currentHeight,
                "Resize Image",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                selected
            );

            if (choice < 0) return;

            int newWidth;
            if (choice == 0) {
                newWidth = originalWidth / 4;
            } else if (choice == 1) {
                newWidth = originalWidth / 2;
            } else if (choice == 2) {
                newWidth = (int) (originalWidth * 0.75);
            } else if (choice == 3) {
                newWidth = originalWidth;
            } else if (choice == 4) {
                newWidth = (int) (originalWidth * 1.5);
            } else if (choice == 5) {
                newWidth = originalWidth * 2;
            } else {
                // Custom
                String input = JOptionPane.showInputDialog(
                    this,
                    "Enter width in pixels (10-" + (originalWidth * 3) + "):",
                    currentWidth
                );
                if (input == null || input.trim().isEmpty()) return;
                try {
                    newWidth = Integer.parseInt(input.trim());
                    if (newWidth < 10) newWidth = 10;
                    if (newWidth > originalWidth * 3) newWidth = originalWidth * 3;
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Invalid number", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            // Calculate new height maintaining aspect ratio
            int newHeight = (int) ((double) originalHeight * newWidth / originalWidth);

            // Update the display
            currentWidth = newWidth;
            currentHeight = newHeight;
            updateImageDisplay();
            infoLabel.setText(currentWidth + "×" + currentHeight + " px");

            // Update layered pane and components
            Component[] components = getComponents();
            for (Component comp : components) {
                if (comp instanceof JLayeredPane) {
                    JLayeredPane layeredPane = (JLayeredPane) comp;
                    // Update image label bounds
                    imageLabel.setBounds(0, 0, currentWidth, currentHeight);
                    // Update resize handle bounds
                    Component[] layerComps = layeredPane.getComponents();
                    for (Component layerComp : layerComps) {
                        if (layerComp instanceof JPanel && layerComp != imageLabel) {
                            int handleSize = 12;
                            layerComp.setBounds(currentWidth - handleSize, currentHeight - handleSize, handleSize, handleSize);
                        }
                    }
                    layeredPane.setPreferredSize(new Dimension(currentWidth, currentHeight));
                    break;
                }
            }

            revalidate();
            repaint();

            // Force parent to revalidate so text wraps around new size
            Container parent = getParent();
            while (parent != null) {
                parent.revalidate();
                parent.repaint();
                if (parent instanceof JTextPane) break;
                parent = parent.getParent();
            }
        }

        /**
         * Get the markdown/HTML representation of this image for saving.
         * Uses HTML img tag with width to preserve size (markdown parsers support this).
         */
        public String getMarkdown() {
            // Use HTML img tag with width attribute - most markdown parsers support inline HTML
            // This preserves the resize choice when viewing
            return "<img src=\"attachments/" + fileName + "\" alt=\"" + fileName + "\" width=\"" + currentWidth + "\" />";
        }
    }
}
