package com.unhuman.notepile.ui;

import com.unhuman.notepile.model.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.nio.file.StandardCopyOption;

/**
 * Panel that renders notes (JSON files) from a chapter's notes directory.
 * Each note is rendered as markdown -> HTML inside an individual container.
 */
public class NoteViewerPanel extends JPanel {
    private Settings settings;
    private final JPanel listPanel;
    private final JScrollPane scrollPane;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Parser mdParser;
    private final HtmlRenderer mdRenderer;
    // current context for deletion
    private String currentStorage;
    private String currentNotebook;
    private String currentChapter;

    public NoteViewerPanel() {
        super(new BorderLayout());
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(new EmptyBorder(8,8,8,8));
        scrollPane = new JScrollPane(listPanel);
        add(scrollPane, BorderLayout.CENTER);
        MutableDataSet options = new MutableDataSet();
        mdParser = Parser.builder(options).build();
        mdRenderer = HtmlRenderer.builder(options).build();
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    public void loadNotes(String storageLocation, String notebook, String chapter) {
        this.currentStorage = storageLocation;
        this.currentNotebook = notebook;
        this.currentChapter = chapter;
        listPanel.removeAll();
        if (storageLocation == null || notebook == null || chapter == null) {
            listPanel.add(createMessagePanel("No notebook/chapter selected."));
            revalidate(); repaint();
            return;
        }
        Path notesDir = Paths.get(storageLocation, notebook, chapter, "notes");
        if (!Files.exists(notesDir) || !Files.isDirectory(notesDir)) {
            listPanel.add(createMessagePanel("No notes directory found for this chapter."));
            revalidate(); repaint();
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(notesDir)) {
            List<Path> files = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .collect(Collectors.toList());
            if (files.isEmpty()) {
                listPanel.add(createMessagePanel("No notes found in this chapter."));
                revalidate(); repaint();
                return;
            }
            // Sort according to settings
            boolean descending = true;
            if (settings != null) {
                descending = settings.getDefaultSortOrder() == Settings.SortOrder.Descending;
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            if (descending) Collections.reverse(files);

            for (Path f : files) {
                try {
                    Map<?,?> obj = mapper.readValue(f.toFile(), Map.class);
                    String title = safeString(obj.get("title"));
                    String date = safeString(obj.get("date"));
                    String people = safeString(obj.get("people"));
                    String labels = safeString(obj.get("labels"));
                    String content = safeString(obj.get("content"));

                    String header = (title == null || title.isEmpty()) ? f.getFileName().toString() : title;
                    StringBuilder metaSb = new StringBuilder();
                    if (date != null && !date.isEmpty()) metaSb.append(date);
                    if (people != null && !people.isEmpty()) {
                        if (!metaSb.isEmpty()) metaSb.append(" — ");
                        metaSb.append(people);
                    }
                    if (labels != null && !labels.isEmpty()) {
                        if (!metaSb.isEmpty()) metaSb.append(" — ");
                        metaSb.append(labels);
                    }
                    String meta = metaSb.toString();
                    JPanel notePanel = createNotePanel(f, header, meta, content);
                    listPanel.add(notePanel);
                    listPanel.add(Box.createVerticalStrut(8));
                } catch (Exception ex) {
                    listPanel.add(createMessagePanel("Failed to read note: " + f.getFileName().toString()));
                }
            }

        } catch (IOException e) {
            listPanel.add(createMessagePanel("Failed to list notes: " + e.getMessage()));
        }
        revalidate(); repaint();
    }

    private JPanel createMessagePanel(String msg) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        JLabel l = new JLabel(msg);
        p.add(l, BorderLayout.WEST);
        return p;
    }

    private JPanel createNotePanel(Path noteFile, String title, String meta, String markdown) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY), new EmptyBorder(8,8,8,8)));

        JPanel top = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        top.add(titleLabel, BorderLayout.WEST);
        if (meta != null && !meta.isEmpty()) {
            JLabel metaLabel = new JLabel(meta);
            metaLabel.setForeground(Color.DARK_GRAY);
            top.add(metaLabel, BorderLayout.EAST);
        }
        p.add(top, BorderLayout.NORTH);

        JEditorPane editor = new JEditorPane();
        editor.setEditable(false);
        editor.setContentType("text/html");
        HTMLEditorKit kit = new HTMLEditorKit();
        editor.setEditorKit(kit);
        // Use Flexmark to convert markdown to HTML
        String html;
        try {
            com.vladsch.flexmark.util.ast.Node doc = mdParser.parse(markdown == null ? "" : markdown);
            html = mdRenderer.render(doc);
        } catch (Exception ex) {
            html = "<pre>Failed to render markdown</pre>";
        }
        // Wrap rendered HTML in a body with a fixed content width so text wraps predictably
        String wrappedHtml = "<html><body style=\"font-family: sans-serif; font-size: 12px; width:580px;\">" + html + "</body></html>";
        editor.setText(wrappedHtml);
        editor.setCaretPosition(0);

        // Compute preferred height for the editor content and set preferred size so there are no per-note scrollbars
        int height = computeHtmlPreferredHeight(editor, wrappedHtml);
        int maxHeight = 1200; // clamp to avoid excessively tall individual boxes
        Dimension pref = new Dimension(600, Math.min(height, maxHeight));
        editor.setPreferredSize(pref);
        editor.setMinimumSize(new Dimension(200, 50));
        // Add editor directly (no internal scroll pane) so the note panel expands to fit content
        p.add(editor, BorderLayout.CENTER);

        // Context menu for edit and delete
        p.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openEditor(noteFile);
                }
            }
            private void maybeShowPopup(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = new JPopupMenu();
                    JMenuItem edit = new JMenuItem("Edit");
                    edit.addActionListener(ae -> openEditor(noteFile));
                    menu.add(edit);
                    JMenuItem del = new JMenuItem("Delete");
                    del.addActionListener(new java.awt.event.ActionListener() {
                        @Override
                        public void actionPerformed(java.awt.event.ActionEvent ae) {
                            onDeleteNoteConfirmed(noteFile);
                        }
                    });
                    menu.add(del);
                    menu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        // Also add double-click on the editor itself to edit the note
        editor.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openEditor(noteFile);
                }
            }
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) editor.getParent().dispatchEvent(e); }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) editor.getParent().dispatchEvent(e); }
        });

        return p;
    }

    private static String safeString(Object o) {
        if (o == null) return null;
        return String.valueOf(o);
    }

    private int computeHtmlPreferredHeight(JEditorPane editor, String html) {
        // Try to let the editor compute its preferred size for the given content.
        // We set a width and request preferred size; this gives a better height estimate.
        try {
            editor.setSize(new Dimension(600, Integer.MAX_VALUE));
            Dimension pref = editor.getPreferredSize();
            return pref == null ? 200 : pref.height;
        } catch (Exception ex) {
            // fallback
            int lines = html.split("\n").length;
            int lineHeight = 16;
            return lines * lineHeight;
        }
    }

    private void onDeleteNoteConfirmed(Path noteFile) {
        if (noteFile == null) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete this note?\nIt will be moved to the application's .garbage folder.",
                "Delete Note",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        if (currentStorage == null || currentNotebook == null || currentChapter == null) {
            JOptionPane.showMessageDialog(this, "Cannot determine storage location to move the deleted note.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Path garbageRoot = Paths.get(currentStorage, ".garbage", currentNotebook, currentChapter, "notes");
            if (!Files.exists(garbageRoot)) {
                Files.createDirectories(garbageRoot);
            }
            Path target = garbageRoot.resolve(noteFile.getFileName());
            // avoid overwrite: if exists, append timestamp
            if (Files.exists(target)) {
                String base = noteFile.getFileName().toString();
                String name = base;
                int idx = base.lastIndexOf('.');
                String ext = "";
                if (idx > 0) { ext = base.substring(idx); name = base.substring(0, idx); }
                String ts = "-" + System.currentTimeMillis();
                target = garbageRoot.resolve(name + ts + ext);
            }
            Files.move(noteFile, target, StandardCopyOption.REPLACE_EXISTING);
            // Reload notes after deletion
            loadNotes(currentStorage, currentNotebook, currentChapter);
            JOptionPane.showMessageDialog(this, "Note moved to .garbage.", "Deleted", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to move note to .garbage: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openEditor(Path noteFile) {
        if (noteFile == null) return;
        Frame parent = (Frame) SwingUtilities.getWindowAncestor(this);
        java.util.List<String> notebooks = listNotebooks();
        String dateFmt = settings == null ? "yyyy-MM-dd" : settings.getDateFormat();
        NoteDialog editorDialog = new NoteDialog(parent, currentStorage, notebooks, currentNotebook, currentChapter, dateFmt);
        try {
            editorDialog.populateForEdit(noteFile);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to open note for edit: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        editorDialog.setModal(true);
        editorDialog.setVisible(true);
        // Reload notes after editor closed
        loadNotes(currentStorage, currentNotebook, currentChapter);
    }

    // Helper method to list notebooks in the current storage
    public List<String> listNotebooks() {
        if (currentStorage == null) return Collections.emptyList();
        try (java.util.stream.Stream<Path> stream = Files.list(Paths.get(currentStorage))) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
