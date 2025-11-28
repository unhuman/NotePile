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
        if (notebooks != null) notebooks.forEach(n -> notebooksCombo.addItem(n));
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

            // Attempt to atomically create a new file with a counter suffix to avoid race conditions.
            java.nio.file.Path notePath = null;
            int attempts = 0;
            while (true) {
                String filename = base + "-" + next + ".json";
                notePath = notesDir.resolve(filename);
                try {
                    // This will fail if the file already exists, ensuring atomicity
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

            // Write JSON into the newly created file (overwrite the empty file)
            try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(notePath, java.nio.file.StandardOpenOption.WRITE)) {
                mapper.writeValue(os, noteObj);
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
}
