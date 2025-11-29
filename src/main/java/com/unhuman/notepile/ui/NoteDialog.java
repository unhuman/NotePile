package com.unhuman.notepile.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
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
    private JTextArea contentArea;

    private final String storageRoot;
    private final String dateFormatPattern;
    private java.nio.file.Path lastSavedPath = null;

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

        // Content area
        contentArea = new JTextArea();
        JScrollPane contentScroll = new JScrollPane(contentArea);
        // Support drag-and-drop attachments: files dropped into the content area are copied into the
        // current chapter's notes/attachments directory and a markdown link is inserted at the caret.
        contentArea.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferHandler.TransferSupport support) {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferHandler.TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    java.awt.datatransfer.Transferable t = support.getTransferable();
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

                    StringBuilder insertBuilder = new StringBuilder();
                    for (java.io.File f : files) {
                        java.nio.file.Path dest = copyAttachment(f.toPath(), attachmentsDir);
                        if (dest != null) {
                            String fname = dest.getFileName().toString();
                            // If image, insert markdown image syntax, otherwise a link
                            String lower = fname.toLowerCase();
                            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".svg")) {
                                insertBuilder.append("![").append(fname).append("](attachments/").append(fname).append(")\n");
                            } else {
                                insertBuilder.append("[").append(fname).append("](attachments/").append(fname).append(")\n");
                            }
                        }
                    }
                    if (insertBuilder.length() > 0) {
                        final String toInsert = insertBuilder.toString();
                        SwingUtilities.invokeLater(() -> contentArea.insert(toInsert, contentArea.getCaretPosition()));
                    }
                    return true;
                } catch (Exception ex) {
                    ex.printStackTrace(System.err);
                    return false;
                }
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
        d.content = contentArea.getText();

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
        d.content = contentArea.getText();
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
        SwingUtilities.invokeLater(() -> contentArea.insert(text, contentArea.getCaretPosition()));
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
        contentArea.setText((String) m.getOrDefault("content", ""));
        String d = (String) m.get("date");
        if (d != null) {
            try {
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(this.dateFormatPattern);
                datePicker.setDate(java.time.LocalDate.parse(d, fmt));
            } catch (Exception ex) {
                try { datePicker.setDate(java.time.LocalDate.parse(d)); } catch (Exception ignore) {}
            }
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
}
