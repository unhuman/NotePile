package com.unhuman.notepile.ui;

import com.unhuman.notepile.model.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.concurrent.Worker;
import javafx.util.Duration;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.event.*;
import java.awt.event.*;

/**
 * Panel that renders notes (JSON files) from a chapter's notes directory.
 * Each note is rendered as markdown -> HTML inside an individual container.
 */
public class NoteViewerPanel extends JPanel {
    private static final boolean JAVA_FX_AVAILABLE;
    static {
        boolean available;
        try {
            Class.forName("javafx.application.Platform");
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        JAVA_FX_AVAILABLE = available;
        if (available) {
            try {
                new JFXPanel(); // boots the JavaFX toolkit for Swing applications
            } catch (Throwable t) {
                System.err.println("NoteViewerPanel: Failed to initialize JavaFX toolkit: " + t.getMessage());
            }
        }
    }
     private Settings settings;
    private final JPanel listPanel;
    private final JScrollPane scrollPane;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Parser mdParser;
    private final HtmlRenderer mdRenderer;
    private String currentStorage;
    private String currentNotebook;
    private String currentChapter;
    // track rendering entries so we can remeasure on resize
    private final Map<Path, RenderEntry> renderEntries = new ConcurrentHashMap<>();
    // Use fully-qualified javax.swing.Timer to avoid ambiguity with java.util.Timer
    private javax.swing.Timer resizeTimer;

    public NoteViewerPanel() {
        super(new BorderLayout());
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(new EmptyBorder(8,8,8,8));
        scrollPane = new JScrollPane(listPanel);
        // Prefer no horizontal scrollbar so the UI truncates titles instead of forcing a horizontal scroll
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
         add(scrollPane, BorderLayout.CENTER);
        MutableDataSet options = new MutableDataSet();
        // Enable hard line breaks: treat all newlines as <br> tags (note-taking behavior)
        // Setting SOFT_BREAK to <br> makes the renderer output <br> for soft line breaks
        options.set(HtmlRenderer.SOFT_BREAK, "<br />\n");
        mdParser = Parser.builder(options).build();
        mdRenderer = HtmlRenderer.builder(options).build();
        // listen for viewport resize to remeasure rendered notes (debounced)
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                scheduleResizeRemeasure();
            }
        });
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    public void loadNotes(String storageLocation, String notebook, String chapter) {
        this.currentStorage = storageLocation;
        this.currentNotebook = notebook;
        this.currentChapter = chapter;
        // clear previous render registry and views
        clearRenderEntries();
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
                    // Only show the date on the header/meta line. Keep people and labels in the panel's client properties
                    String meta = (date == null) ? "" : date;
                    JPanel notePanel = createNotePanel(f, header, meta, content);
                    // Preserve metadata for future use (search/indexing, etc.)
                    notePanel.putClientProperty("people", people == null ? "" : people);
                    notePanel.putClientProperty("labels", labels == null ? "" : labels);
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
        p.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Color.LIGHT_GRAY), new EmptyBorder(4,8,4,8)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        // title on the left (expands), meta (date) on the right (fixed width)
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
         // meta label may be null; use a single-element array so it is effectively final for lambdas
         final JLabel[] metaLabelRef = new JLabel[1];
         // title label will be ellipsized to fit available space; full text stored as client property
         JLabel titleLabel = new JLabel(title);
         titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
         titleLabel.putClientProperty("fullText", title == null ? "" : title);
         titleLabel.setToolTipText(title);
         titleLabel.setHorizontalAlignment(SwingConstants.LEFT);
         // allow title to occupy the center area (it will expand/shrink with top's center)
         // give the title a very small minimum width so it can shrink and allow the meta label to remain visible
         Dimension titlePref = titleLabel.getPreferredSize();
         titleLabel.setMinimumSize(new Dimension(10, titlePref.height));
         JPanel titleContainer = new JPanel(new BorderLayout());
         titleContainer.setOpaque(false);
         titleContainer.add(titleLabel, BorderLayout.CENTER);
         top.add(titleContainer, BorderLayout.CENTER);
         if (meta != null && !meta.isEmpty()) {
             JLabel ml = new JLabel(meta);
             ml.setForeground(Color.DARK_GRAY);
             ml.setHorizontalAlignment(SwingConstants.RIGHT);
             ml.setOpaque(false);
             // prefer the meta label's preferred width, but allow it to shrink when necessary
             Dimension metaPref = ml.getPreferredSize();
             ml.setPreferredSize(metaPref);
             // do NOT force a minimum width; allow the meta label to be truncated if no room
             top.add(ml, BorderLayout.EAST);
             metaLabelRef[0] = ml;
             // when meta label's size changes (font/locale), update ellipsis for the title/meta
             ml.addComponentListener(new ComponentAdapter() {
                 @Override
                 public void componentResized(ComponentEvent e) {
                     SwingUtilities.invokeLater(() -> updateTitleEllipsis(titleLabel, metaLabelRef[0], top));
                 }
             });
         }
         // ensure the top panel can shrink horizontally with the container (height fixed to its preferred)
         Dimension topPref = top.getPreferredSize();
         top.setMaximumSize(new Dimension(Integer.MAX_VALUE, topPref.height));
         p.add(top, BorderLayout.NORTH);

         // Ensure title ellipsizes to fit the space; recalc on top resize
         top.addComponentListener(new ComponentAdapter() {
             @Override
             public void componentResized(ComponentEvent e) {
                 SwingUtilities.invokeLater(() -> updateTitleEllipsis(titleLabel, metaLabelRef[0], top));
             }
         });
         // initial async truncation after layout
         SwingUtilities.invokeLater(() -> updateTitleEllipsis(titleLabel, metaLabelRef[0], top));

        // Build HTML from markdown using Flexmark
        String html;
        try {
            String processedMarkdown = markdown == null ? "" : markdown;
            // Pre-process: Replace single newlines with hard breaks (two trailing spaces + newline)
            // but preserve blank lines (double newlines) for paragraph breaks.
            // First normalize line endings: convert Windows CRLF (\r\n) to Unix LF (\n)
            processedMarkdown = processedMarkdown.replace("\r\n", "\n");
            processedMarkdown = processedMarkdown.replace("\r", "\n"); // handle old Mac CR

            // Strategy: Temporarily replace \n\n with a placeholder, add spaces to remaining \n, then restore
            String PARA_MARKER = "\u0000PARA\u0000";
            processedMarkdown = processedMarkdown.replace("\n\n", PARA_MARKER);
            processedMarkdown = processedMarkdown.replace("\n", "  \n");
            processedMarkdown = processedMarkdown.replace(PARA_MARKER, "\n\n");

            com.vladsch.flexmark.util.ast.Node doc = mdParser.parse(processedMarkdown);
            html = mdRenderer.render(doc);
        } catch (Exception ex) {
            html = "<pre>Failed to render markdown</pre>";
        }
        // Create HTML wrapper with base href pointing to notes directory so attachments resolve relative to notesDir
        Path notesDir = noteFile.getParent();
        String baseHref = notesDir == null ? "" : notesDir.toUri().toString();
        // Build enhanced HTML with proper CSS for images and tight margins.
        // Wrap content in a dedicated root so JS can measure its intrinsic height (avoids measuring viewport height).
        String css = "html,body{height:auto !important;min-height:0 !important;margin:0;padding:0;overflow-x:hidden;}" +
                "#notepile-root{box-sizing:border-box;padding:8px 12px 8px 12px;display:block;width:100%;}" +
                "body{font-family:sans-serif;font-size:12px;color:#111;}" +
                "img{max-width:100%;height:auto;display:block;margin:0;} p{margin:12px 0;}" +
                "pre, code { white-space: pre-wrap; word-wrap: break-word; overflow-wrap: break-word; }" +
                "table{ max-width:100%; table-layout: fixed; } ul,ol{margin:4px 0;padding-left:24px;}";
        String enhancedHtml = "<html><head><base href='" + baseHref + "'/><style>" + css + "</style></head><body><div id='notepile-root'>" + html + "</div></body></html>";

        // Ensure temp HTML is written to disk so WebView has a file URL (reliable base for relative attachments)
        Path tempHtml;
        try {
            Path viewerDir = notesDir == null ? null : notesDir.resolve(".viewer");
            if (viewerDir != null) Files.createDirectories(viewerDir);
            String safeName = noteFile.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_") + ".html";
            tempHtml = viewerDir == null ? null : viewerDir.resolve(safeName);
            if (tempHtml != null) Files.writeString(tempHtml, enhancedHtml);
        } catch (Exception ex) {
            // ignore write errors; fallback will try loadContent
            tempHtml = null;
        }

        JPanel contentHolder = new JPanel(new BorderLayout());
        contentHolder.setOpaque(false);
        JPanel placeholder = createLoadingPlaceholder();
        contentHolder.add(placeholder, BorderLayout.CENTER);
        p.add(contentHolder, BorderLayout.CENTER);

        if (!JAVA_FX_AVAILABLE) {
            JLabel missing = new JLabel("JavaFX unavailable — enable OpenJFX to render notes.", SwingConstants.CENTER);
            contentHolder.removeAll();
            contentHolder.add(missing, BorderLayout.CENTER);
        } else {
            JFXPanel jfxPanel = new JFXPanel();
            jfxPanel.setOpaque(false);
            // measure header height now to include when sizing the whole note container
            int headerHeight = top.getPreferredSize().height;
            renderNoteInWebView(noteFile, tempHtml, enhancedHtml, contentHolder, placeholder, jfxPanel, p, headerHeight);

            // Double-click editing and context menu support on the rendered content itself
            jfxPanel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        openEditor(noteFile);
                    }
                }
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) p.dispatchEvent(e); }
                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) p.dispatchEvent(e); }
            });

            // Forward mouse wheel events to the parent scroll pane so scrolling anywhere scrolls the entire window
            jfxPanel.addMouseWheelListener(e -> {
                scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(e.getComponent(), e, scrollPane));
            });
        }

        // Support drag-and-drop onto the panel for attachments
        p.setTransferHandler(new TransferHandler() {
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
                    if (files.isEmpty()) return false;

                    // attachments dir for this note's chapter
                    Path notesDir = noteFile.getParent();
                    if (notesDir == null) return false;
                    Path attachmentsDir = notesDir.resolve("attachments");
                    if (!Files.exists(attachmentsDir)) Files.createDirectories(attachmentsDir);

                    StringBuilder sb = new StringBuilder();
                    for (java.io.File f : files) {
                        java.nio.file.Path dest = copyAttachmentTo(attachmentsDir, f.toPath());
                        if (dest != null) {
                            String fname = dest.getFileName().toString();
                            String lower = fname.toLowerCase();
                            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".svg")) {
                                sb.append("![").append(fname).append("](attachments/").append(fname).append(")\n");
                            } else {
                                sb.append("[").append(fname).append("](attachments/").append(fname).append(")\n");
                            }
                        }
                    }

                    if (!sb.isEmpty()) {
                        // Open editor populated for this note and append links to content
                        SwingUtilities.invokeLater(() -> {
                            Frame parent = (Frame) SwingUtilities.getWindowAncestor(NoteViewerPanel.this);
                            java.util.List<String> notebooks = listNotebooks();
                            String dateFmt = settings == null ? "yyyy-MM-dd" : settings.getDateFormat();
                            NoteDialog dlg = new NoteDialog(parent, currentStorage, notebooks, currentNotebook, currentChapter, dateFmt);
                            try {
                                dlg.populateForEdit(noteFile);
                                // append links to content area using public API
                                dlg.insertIntoContent(sb.toString());
                            } catch (Exception ex) {
                                // fallback: just notify user
                                JOptionPane.showMessageDialog(NoteViewerPanel.this, "Attachments copied to: " + attachmentsDir, "Attachments", JOptionPane.INFORMATION_MESSAGE);
                            }
                            dlg.setModal(true);
                            dlg.setVisible(true);
                            // reload notes after editor closes
                            loadNotes(currentStorage, currentNotebook, currentChapter);
                        });
                    }
                    return true;
                } catch (Exception ex) {
                    ex.printStackTrace(System.err);
                    return false;
                }
            }
        });

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
                    edit.addActionListener(ae -> { java.util.Objects.requireNonNull(ae); openEditor(noteFile); });
                      menu.add(edit);
                      JMenuItem del = new JMenuItem("Delete");
                     del.addActionListener(ae -> { java.util.Objects.requireNonNull(ae); onDeleteNoteConfirmed(noteFile); });
                      menu.add(del);
                      menu.show(e.getComponent(), e.getX(), e.getY());
                 }
             }
         });

         // Forward mouse wheel events to the parent scroll pane so scrolling anywhere scrolls the entire window
         p.addMouseWheelListener(e -> {
             scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(e.getComponent(), e, scrollPane));
         });

        return p;
    }

    private JPanel createLoadingPlaceholder() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JLabel label = new JLabel("Rendering…", SwingConstants.CENTER);
        label.setForeground(Color.DARK_GRAY);
        panel.add(label, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(600, 60));
        return panel;
    }

    private void renderNoteInWebView(Path noteFile, Path htmlFile, String htmlContent,
                                     JPanel contentHolder, JComponent placeholder, JFXPanel targetPanel,
                                     JPanel noteContainer, int headerHeight) {
        Platform.runLater(() -> {
            try {
                WebView webView = new WebView();
                // Disable the WebView's default context menu so our custom edit/delete menu works
                webView.setContextMenuEnabled(false);
                // lower minimum width to avoid forcing horizontal scrollbars when viewport is narrow
                final double widthFinal = Math.max(200, scrollPane.getViewport().getWidth() - 32);
                webView.setPrefWidth(widthFinal);
                Scene scene = new Scene(webView);
                scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                targetPanel.setScene(scene);

                final WebEngine engine = webView.getEngine();
                // register this render entry so we can remeasure on resize
                engine.getLoadWorker().stateProperty().addListener((ignored, oldState, newState) -> {
                    if (newState == Worker.State.SUCCEEDED && oldState != Worker.State.SUCCEEDED) {
                        // remove placeholder and attach the JFXPanel immediately so Swing layout includes the JFXPanel
                        SwingUtilities.invokeLater(() -> {
                            contentHolder.remove(placeholder);
                            // Add the JFXPanel to the NORTH so it uses its preferred height and does not stretch vertically
                            contentHolder.add(targetPanel, BorderLayout.NORTH);
                            // do not lock the JFXPanel width; allow width to expand but keep a sensible initial max height
                            Dimension initPref = targetPanel.getPreferredSize();
                            targetPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, initPref.height));
                            contentHolder.revalidate();
                            contentHolder.repaint();
                        });

                        // Per-note debounce: collect measured heights from JS and apply them only after they stabilize.
                        final int[] pendingHeight = new int[] { -1 };
                        final javax.swing.Timer applyTimer = new javax.swing.Timer(30, evt -> {
                            java.util.Objects.requireNonNull(evt);
                            int ph = pendingHeight[0];
                            if (ph <= 0) return;
                            // compute the current available viewport width on EDT so applied widths match window
                            int currentAvailableWidth = Math.max(200, scrollPane.getViewport().getWidth() - 32);
                            // apply on EDT (Timer runs on EDT)
                            try {
                                final int prefHeight = ph;
                                Dimension pref = new Dimension(currentAvailableWidth, prefHeight);
                                targetPanel.setPreferredSize(pref);
                                targetPanel.setMinimumSize(new Dimension(64, prefHeight));
                                targetPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, prefHeight));
                                int totalH = prefHeight + headerHeight + 16;
                                Dimension notePref = new Dimension(currentAvailableWidth, totalH);
                                noteContainer.setPreferredSize(notePref);
                                noteContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, totalH));
                                contentHolder.revalidate();
                                contentHolder.repaint();
                                listPanel.revalidate();
                                listPanel.repaint();
                                System.out.println("NoteViewerPanel: applied-measure note=" + noteFile.getFileName() + " height=" + prefHeight + " total=" + totalH + " width=" + currentAvailableWidth);
                                // nudge the WebView layout on FX thread
                                Platform.runLater(() -> webView.setPrefHeight(prefHeight));
                            } catch (Throwable t) {
                                t.printStackTrace(System.err);
                            }
                        });
                        applyTimer.setRepeats(false);

                        // Set an onAlert handler so JS can notify us of height changes by calling alert("NOTEPILE_HEIGHT:<value>")
                        engine.setOnAlert(webEvent -> {
                            String data = webEvent.getData();
                            if (data != null && data.startsWith("NOTEPILE_HEIGHT:")) {
                                try {
                                    double h = Double.parseDouble(data.substring("NOTEPILE_HEIGHT:".length()));
                                    final int prefHeight = (int) Math.max(80, Math.min(4000, Math.round(h + 16)));
                                    // store and debounce apply
                                    pendingHeight[0] = prefHeight;
                                    // restart timer
                                    SwingUtilities.invokeLater(applyTimer::restart);
                                } catch (NumberFormatException ignore) {
                                }
                            }
                        });

                        // Improved JS observer: uses RAF for smooth, debounced measurements
                        // This is more efficient than firing on every mutation
                        String observerScript =
                                "(function(){" +
                                "var scheduled = false;" +
                                "function measure(){" +
                                "var el = document.getElementById('notepile-root');" +
                                "if(!el){ var h = document.body.scrollHeight || document.documentElement.scrollHeight || 0; try{ if(window.alert) window.alert('NOTEPILE_HEIGHT:' + h); }catch(e){} return; }" +
                                "try{ var prevOverflow = document.body.style.overflow || ''; document.body.style.overflow = 'hidden'; }catch(e){}" +
                                "var h = el.scrollHeight || (el.getBoundingClientRect && el.getBoundingClientRect().height) || document.body.scrollHeight || 0;" +
                                "try{ if(window.alert) window.alert('NOTEPILE_HEIGHT:' + h); }catch(e){}" +
                                "try{ if(typeof prevOverflow !== 'undefined') document.body.style.overflow = prevOverflow; }catch(e){}" +
                                "scheduled = false;" +
                                "}" +
                                "function scheduleMeasure(){" +
                                "if(scheduled) return;" +
                                "scheduled = true;" +
                                "requestAnimationFrame(measure);" +
                                "}" +
                                "window.notepileMeasure = scheduleMeasure;" +
                                "var obs = new MutationObserver(scheduleMeasure);" +
                                "try{ obs.observe(document.getElementById('notepile-root') || document.body, {subtree:true, childList:true, attributes:true}); }catch(e){}" +
                                "var imgs = document.images || [];" +
                                "Array.prototype.forEach.call(imgs, function(i){ if(!i.complete){ i.addEventListener('load', scheduleMeasure); } });" +
                                "scheduleMeasure();" +
                                "})();";
                        try {
                            engine.executeScript(observerScript);
                            // record the render entry (engine and webView must be used on FX thread for some ops)
                            RenderEntry entry = new RenderEntry(noteFile, targetPanel, webView, engine, noteContainer, headerHeight, widthFinal);
                            // attach the apply timer for potential future control / cancellation
                            entry.measureTimer = applyTimer;
                            renderEntries.put(noteFile, entry);
                        } catch (Throwable t) {
                            // fall back to polling adjustments if script injection fails
                            adjustHeight(engine, webView, targetPanel, contentHolder, widthFinal, noteFile, noteContainer, headerHeight);
                        }
                     } else if (newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                         SwingUtilities.invokeLater(() -> showRenderError(contentHolder, "Failed to render note."));
                     }
                  });

                if (htmlFile != null && Files.exists(htmlFile)) {
                    engine.load(htmlFile.toUri().toString());
                } else {
                    engine.loadContent(htmlContent, "text/html");
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showRenderError(contentHolder, "Render error: " + ex.getMessage()));
            }
        });
    }

    private double computeDocumentHeight(WebEngine engine, WebView webView) {
        double height = Math.max(
                evalDouble(engine, "document.documentElement.scrollHeight"),
                evalDouble(engine, "document.body.scrollHeight"));
        height = Math.max(height, evalDouble(engine, "document.body.getBoundingClientRect().height"));

        if (height <= 0) {
            height = Math.max(webView.getBoundsInLocal().getHeight(), webView.prefHeight(-1));
        }

        if (height <= 0) {
            height = 80;
        }

        return height;
    }

    private double evalDouble(WebEngine engine, String script) {
        try {
            Object result = engine.executeScript(script);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
            if (result != null) {
                return Double.parseDouble(result.toString());
            }
        } catch (Exception ignore) {
            // swallow and fall through to fallback
        }
        return -1;
    }

    /**
     * Fallback: Measure document height when JS callback system fails.
     * Only does 2 quick measurements since callback system handles 99% of cases.
     */
    private void adjustHeight(WebEngine engine, WebView webView, JFXPanel jfxPanel, JPanel contentHolder,
                              double widthFinal, Path noteFile, JPanel noteContainer, int headerHeight) {
        final double padding = 16.0;
        // run measurements on FX thread, but update Swing on EDT
        Platform.runLater(() -> {
            final double[] lastMeasured = { -1 };

            // Helper to measure and apply size
            Runnable measureAndApply = () -> {
                 double h = computeDocumentHeight(engine, webView);
                 if (h <= 0) return;
                 final int prefHeight = (int) Math.max(80, Math.min(4000, Math.round(h + padding)));
                 // if height changed meaningfully update Swing sizes
                 if (lastMeasured[0] <= 0 || Math.abs(lastMeasured[0] - prefHeight) > 2) {
                     lastMeasured[0] = prefHeight;
                     SwingUtilities.invokeLater(() -> {
                        Dimension pref = new Dimension((int) Math.ceil(widthFinal), prefHeight);
                        jfxPanel.setPreferredSize(pref);
                        jfxPanel.setMinimumSize(new Dimension(64, prefHeight));
                        jfxPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, prefHeight));
                        int totalH = prefHeight + headerHeight + 16;
                        Dimension notePref = new Dimension((int) Math.ceil(widthFinal), totalH);
                        noteContainer.setPreferredSize(notePref);
                        noteContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, totalH));
                        contentHolder.revalidate();
                        contentHolder.repaint();
                        listPanel.revalidate();
                        listPanel.repaint();
                        System.out.println("NoteViewerPanel: fallback-adjusted note=" + noteFile.getFileName() + " height=" + prefHeight + " total=" + totalH);
                    });
                    webView.setPrefHeight(prefHeight);
                 }
             };

            // Immediate measurement
            measureAndApply.run();

            // One delayed measurement for images/late content (100ms is enough)
            PauseTransition pt = new PauseTransition(Duration.millis(100));
            pt.setOnFinished(ev -> { ev.consume(); measureAndApply.run(); });
            pt.play();
         });
     }

    private void showRenderError(JPanel contentHolder, String message) {
        contentHolder.removeAll();
        JLabel label = new JLabel(message, SwingConstants.CENTER);
        contentHolder.add(label, BorderLayout.CENTER);
        contentHolder.revalidate();
        contentHolder.repaint();
    }

    private static String safeString(Object o) {
        if (o == null) return null;
        return String.valueOf(o);
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
                    // must be a directory
                    .filter(Files::isDirectory)
                    // exclude any hidden/dot-prefixed directories (e.g. .garbage, .viewer)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.startsWith("."))
                    .collect(Collectors.toList());
         } catch (IOException e) {
             return Collections.emptyList();
         }
     }

    // Copy an attachment into the attachmentsDir, avoiding name collisions by appending a counter.
    private java.nio.file.Path copyAttachmentTo(java.nio.file.Path attachmentsDir, java.nio.file.Path src) {
        try {
            String fileName = src.getFileName().toString();
            java.nio.file.Path dest = attachmentsDir.resolve(fileName);
            if (java.nio.file.Files.exists(dest)) {
                String base = fileName;
                String ext = "";
                int idx = fileName.lastIndexOf('.');
                if (idx > 0) { base = fileName.substring(0, idx); ext = fileName.substring(idx); }
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

    private void clearRenderEntries() {
        // Remove any bookkeeping for rendered entries. The Swing components will be removed
        // when the panels are cleared; we just clear the registry here.
        renderEntries.clear();
    }

    // RenderEntry and resize remeasure helpers
    private static class RenderEntry {
        final Path notePath;
        final JFXPanel panel;
        final WebView webView;
        final WebEngine engine;
        final JPanel noteContainer;
        final int headerHeight;
        double lastKnownWidth;
        javax.swing.Timer measureTimer; // for debounced height applying

        RenderEntry(Path notePath, JFXPanel panel, WebView webView, WebEngine engine, JPanel noteContainer, int headerHeight, double width) {
            this.notePath = notePath;
            this.panel = panel;
            this.webView = webView;
            this.engine = engine;
            this.noteContainer = noteContainer;
            this.headerHeight = headerHeight;
            this.lastKnownWidth = width;
        }
    }

    private void scheduleResizeRemeasure() {
        if (resizeTimer != null && resizeTimer.isRunning()) {
            resizeTimer.restart();
            return;
        }
        resizeTimer = new javax.swing.Timer(180, evt -> {
            java.util.Objects.requireNonNull(evt);
            resizeTimer.stop();
            remeasureAllVisible();
        });
        resizeTimer.setRepeats(false);
        resizeTimer.start();
    }

    private void remeasureAllVisible() {
        // Use a lower minimum so narrow windows don't force a large minimum width
        final int availableWidth = Math.max(200, scrollPane.getViewport().getWidth() - 32);
        Platform.runLater(() -> {
            for (RenderEntry entry : renderEntries.values()) {
                try {
                    entry.lastKnownWidth = availableWidth;
                    try {
                        // update the WebView preferred width so DOM layout uses the current viewport width
                        entry.webView.setPrefWidth(availableWidth);
                        entry.engine.executeScript("if(window.notepileMeasure) window.notepileMeasure();");
                    } catch (Throwable t) {
                        // ignore per-entry failures
                    }
                } catch (Throwable ignore) {
                }
            }
        });
    }

    private void updateTitleEllipsis(JLabel titleLabel, JLabel metaLabel, JPanel topPanel) {
        String fullTitle = (String) titleLabel.getClientProperty("fullText");
        if (fullTitle == null) fullTitle = "";
        String fullMeta = metaLabel == null ? "" : metaLabel.getText();

        // Provide tooltips with full values
        titleLabel.setToolTipText(fullTitle);
        if (metaLabel != null) metaLabel.setToolTipText(fullMeta);

        int totalWidth = topPanel.getWidth();
        Insets insets = topPanel.getInsets();
        // subtract left/right insets so calculations match the actual client area
        totalWidth = Math.max(0, totalWidth - insets.left - insets.right);
        if (totalWidth <= 0) return;

        FontMetrics titleFm = titleLabel.getFontMetrics(titleLabel.getFont());
        FontMetrics metaFm = (metaLabel == null) ? titleFm : metaLabel.getFontMetrics(metaLabel.getFont());

        int padding = 16; // margin between title and meta
        int fullTitleW = titleFm.stringWidth(fullTitle);
        int fullMetaW = metaFm.stringWidth(fullMeta);

        // If both fit, set both to full text
        if (fullTitleW + fullMetaW + padding <= totalWidth) {
            titleLabel.setText(fullTitle);
            if (metaLabel != null) metaLabel.setText(fullMeta);
            // set preferred sizes so layout gives them their full width
            int height = Math.max(titleLabel.getPreferredSize().height, metaLabel == null ? 0 : metaLabel.getPreferredSize().height);
            if (metaLabel != null) metaLabel.setPreferredSize(new Dimension(fullMetaW, height));
            titleLabel.setPreferredSize(new Dimension(totalWidth - fullMetaW - padding, height));
            topPanel.revalidate(); topPanel.repaint();
            return;
        }

        // Prefer to keep meta untruncated if possible. Compute available space for title when meta kept full.
        if (fullMetaW + padding < totalWidth) {
            int availForTitle = totalWidth - fullMetaW - padding;
            String t = ellipsizeText(fullTitle, titleFm, Math.max(10, availForTitle));
            titleLabel.setText(t);
            if (metaLabel != null) metaLabel.setText(fullMeta);
            int height = Math.max(titleLabel.getPreferredSize().height, metaLabel == null ? 0 : metaLabel.getPreferredSize().height);
            if (metaLabel != null) metaLabel.setPreferredSize(new Dimension(fullMetaW, height));
            titleLabel.setPreferredSize(new Dimension(availForTitle, height));
            topPanel.revalidate(); topPanel.repaint();
            return;
        }

        // Not enough room to keep meta full. Allocate a small share to meta and the rest to title.
        // Give meta up to 40% of total, but no more than its full width.
        int metaTarget = Math.min(fullMetaW, (int) (totalWidth * 0.35));
        int titleTarget = totalWidth - metaTarget - padding;
        if (titleTarget < 10) {
            // extreme narrow: split roughly
            metaTarget = Math.max(10, totalWidth / 3);
            titleTarget = Math.max(10, totalWidth - metaTarget - padding);
        }
        String t2 = ellipsizeText(fullTitle, titleFm, titleTarget);
        String m2 = metaLabel == null ? null : ellipsizeText(fullMeta, metaFm, metaTarget);
        titleLabel.setText(t2);
        if (metaLabel != null) metaLabel.setText(m2);
        int height = Math.max(titleLabel.getPreferredSize().height, metaLabel == null ? 0 : metaLabel.getPreferredSize().height);
        if (metaLabel != null) metaLabel.setPreferredSize(new Dimension(metaTarget, height));
        titleLabel.setPreferredSize(new Dimension(titleTarget, height));
        topPanel.revalidate(); topPanel.repaint();
    }

    private String ellipsizeText(String text, FontMetrics fm, int maxWidth) {
        if (text == null) return null;
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ell = "…";
        int avail = Math.max(0, maxWidth - fm.stringWidth(ell));
        if (avail <= 0) return ell;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String sub = text.substring(0, mid);
            if (fm.stringWidth(sub) <= avail) lo = mid; else hi = mid - 1;
        }
        if (lo <= 0) return ell;
        return text.substring(0, lo) + ell;
    }

}
