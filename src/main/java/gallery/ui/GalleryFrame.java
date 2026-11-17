package gallery.ui;

import gallery.catalog.Catalog;
import gallery.catalog.ClipartItem;
import gallery.catalog.DrawingLoader;
import gallery.drawing.AnimatedImage;
import gallery.drawing.Drawing;
import gallery.export.Exporter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GalleryFrame extends JFrame {
    private static final int THUMB_MIN = 64, THUMB_MAX = 256, THUMB_DEFAULT = 128;
    /** Pages of thumbnails prepared (decoded) ahead of the visible page, in the direction of scrolling. */
    private static final int PRELOAD_PAGES = 20;
    /** Pages just scrolled past whose decoded art stays cached (never more than the pages ahead). */
    private static final int KEEP_PAST_PAGES = 20;
    /** Of those, the pages nearest the visible one (ahead, and behind) that are also kept drawn, ready to show. */
    private static final int RASTER_AHEAD_PAGES = 2, RASTER_BEHIND_PAGES = 2;
    /** Remembers the folder last chosen in "Add discs…" so the chooser opens there next time. */
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(GalleryFrame.class);

    private final Catalog catalog;
    private final ThumbnailCache thumbs;
    private final JTextField searchField = new JTextField(30);
    private final JButton clearBtn = new JButton("Clear");
    private final JComboBox<Catalog.Filter> filterBox = new JComboBox<>(Catalog.Filter.values());
    private final JList<ClipartItem> grid = new JList<>();
    private final JLabel status = new JLabel(" ");
    private final PreviewPanel preview = new PreviewPanel();
    private final JTextArea info = new JTextArea();
    private final JComboBox<Exporter.Format> formatBox = new JComboBox<>(Exporter.Format.values());
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("No discs added")));
    private final JSlider sizeSlider = new JSlider(THUMB_MIN, THUMB_MAX, THUMB_DEFAULT);
    private final QuickLook quickLook = new QuickLook();
    private volatile int thumb = THUMB_DEFAULT;
    /** Device pixels per logical pixel where the grid was last painted (2 on Retina screens). */
    private double screenScale = 1;
    private String category = "";
    private boolean updatingTree;
    private final JCheckBox dupBox = new JCheckBox("Hide duplicates", true);
    private final JProgressBar dupBar = new JProgressBar(0, 100);
    private int hiddenDupes;
    private final JCheckBox scopeBox = new JCheckBox("Only in selected category", true);
    private int pngSize = 1024;
    private final JButton exportBtn = new JButton("Export…");
    private final Timer searchTimer;
    private volatile int loadSerial;
    private List<ClipartItem> results = List.of();
    /** Which way the grid was last scrolled (down until the user scrolls up), and where it was. */
    private boolean scrollingDown = true;
    private int lastFirst = -1;
    /** Reused by updateWanted, which runs on every scroll step. */
    private final List<ClipartItem> todo = new ArrayList<>(), past = new ArrayList<>();

    public GalleryFrame(Catalog catalog) {
        super("Clipart Gallery");
        this.catalog = catalog;
        this.thumbs = new ThumbnailCache(catalog, THUMB_DEFAULT, SwingUtilities::invokeLater, this::repaintCell);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        filterBox.setSelectedItem(Catalog.Filter.VECTOR);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.add(new JLabel("Search:"));
        top.add(searchField);
        top.add(clearBtn);
        top.add(filterBox);
        scopeBox.setToolTipText("Checked: search only inside the folder selected in the tree. Unchecked: search everything.");
        top.add(scopeBox);
        dupBox.setEnabled(false);
        dupBox.setToolTipText("Hide files whose preview image is identical to another file's, keeping the copy with the smoothest geometry. "
                + "Becomes available once the previews have been compared (first run only).");
        top.add(dupBox);
        JButton scan = new JButton("Add discs…");
        scan.addActionListener(e -> addSources());
        top.add(scan);
        JButton report = new JButton("Report…");
        report.addActionListener(e -> showReport());
        top.add(report);
        add(top, BorderLayout.NORTH);

        JMenuBar bar = new JMenuBar();
        JMenu catMenu = new JMenu("Catalog");
        JMenuItem addItem = new JMenuItem("Add Discs…");
        addItem.addActionListener(e -> addSources());
        catMenu.add(addItem);
        JMenuItem rebuildItem = new JMenuItem("Rebuild Indexes…");
        rebuildItem.addActionListener(e -> rebuildIndexes());
        catMenu.add(rebuildItem);
        catMenu.addSeparator();
        JMenuItem reportItem = new JMenuItem("Disc Report…");
        reportItem.addActionListener(e -> showReport());
        catMenu.add(reportItem);
        bar.add(catMenu);
        setJMenuBar(bar);

        grid.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        grid.setVisibleRowCount(-1);
        applyCellSize();
        grid.setCellRenderer(new Cell());
        grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        grid.setTransferHandler(new DragOut());
        grid.setDragEnabled(true);
        grid.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelected(); });

        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setBorder(new EmptyBorder(6, 6, 6, 6));
        JPanel exp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        exp.add(formatBox);
        exp.add(exportBtn);
        JPanel right = new JPanel(new BorderLayout());
        // the picture and its details share one framed container, styled like the category tree's frame
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(UIManager.getBorder("ScrollPane.border"));
        card.add(preview, BorderLayout.CENTER);
        // the details grow to fit their text, up to half of the preview pane; only beyond that do they scroll
        JScrollPane infoScroll = new JScrollPane(info, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override public Dimension getPreferredSize() {
                Insets si = getInsets(), ti = info.getInsets();
                Insets ci = card.getInsets();
                int w = card.getWidth() - ci.left - ci.right - si.left - si.right - ti.left - ti.right;
                if (w <= 0) return super.getPreferredSize();
                javax.swing.text.View v = info.getUI().getRootView(info);
                v.setSize(w, Integer.MAX_VALUE);
                int h = (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + ti.top + ti.bottom + si.top + si.bottom;
                return new Dimension(super.getPreferredSize().width, Math.min(h, (card.getHeight() - ci.top - ci.bottom) / 2));
            }
        };
        info.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void changed() { card.revalidate(); card.repaint(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        });
        infoScroll.setBorder(null);
        card.add(infoScroll, BorderLayout.SOUTH);
        right.add(card, BorderLayout.CENTER);
        right.add(exp, BorderLayout.SOUTH);
        right.setPreferredSize(new Dimension(300, 340));

        DefaultTreeCellRenderer rend = new DefaultTreeCellRenderer();
        rend.setLeafIcon(rend.getClosedIcon());   // leaf folders look like folders too
        tree.setCellRenderer(rend);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(this::categoryChosen);
        // clicking the node that is already highlighted (e.g. after it followed the grid selection) sends no
        // selection event, so apply it here
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                TreePath tp = tree.getPathForLocation(e.getX(), e.getY());
                if (tp != null && tp.getLastPathComponent() instanceof DefaultMutableTreeNode n
                        && n.getUserObject() instanceof CatNode cn) chooseCategory(cn.cat.prefix);
            }
        });
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(300, 400));
        treeScroll.setMinimumSize(new Dimension(150, 100));
        right.setMinimumSize(new Dimension(150, 150));
        // left column: category tree on top; preview, details and export underneath
        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, right);
        left.setResizeWeight(0.55);
        left.setDividerLocation(400);
        left.setBorder(null);   // the outer split pane already frames it
        JScrollPane gridScroll = new JScrollPane(grid);
        gridScroll.getViewport().addChangeListener(e -> updateWanted());   // scrolled or resized
        JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, gridScroll);
        outer.setResizeWeight(0.0);
        outer.setDividerLocation(300);
        outer.setBorder(null);
        add(outer, BorderLayout.CENTER);

        sizeSlider.setPreferredSize(new Dimension(140, sizeSlider.getPreferredSize().height));
        sizeSlider.setFocusable(false);
        sizeSlider.setToolTipText("Thumbnail size");
        sizeSlider.addChangeListener(e -> {
            thumb = sizeSlider.getValue();
            thumbs.setSize(thumb, screenScale);
            lastFirst = -1;   // the first visible index moves with the cell size; that isn't a scroll
            applyCellSize();
            grid.repaint();
            SwingUtilities.invokeLater(this::updateWanted);
        });
        JPanel sizeBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        sizeBox.add(new JLabel("▫"));
        sizeBox.add(sizeSlider);
        sizeBox.add(new JLabel("▣"));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        dupBar.setStringPainted(true);
        dupBar.setPreferredSize(new Dimension(300, 18));
        dupBar.setVisible(false);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        east.add(dupBar);
        east.add(sizeBox);
        bottom.add(east, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        status.setBorder(new EmptyBorder(3, 8, 3, 8));

        setGlassPane(quickLook);
        quickLook.setVisible(false);
        grid.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "quicklook");
        grid.getActionMap().put("quicklook", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { quickLook.open(); }
        });
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        grid.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), "copyImage");
        grid.getActionMap().put("copyImage", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { copySelected(); }
        });
        JPopupMenu popup = buildGridMenu();
        grid.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybePopup(e, popup); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e, popup); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && grid.getSelectedValue() != null) quickLook.open();
            }
        });

        searchTimer = new Timer(250, e -> runSearch());
        searchTimer.setRepeats(false);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            void changed() { searchTimer.restart(); clearBtn.setEnabled(!searchField.getText().isEmpty()); }
        });
        clearBtn.setEnabled(false);
        clearBtn.setToolTipText("Clear the search (Esc)");
        clearBtn.setFocusable(false);
        clearBtn.addActionListener(e -> clearSearch());
        searchField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSearch");
        searchField.getActionMap().put("clearSearch", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { clearSearch(); }
        });
        searchField.addActionListener(e -> runSearch());
        filterBox.addActionListener(e -> { rebuildTree(); runSearch(); });
        scopeBox.addActionListener(e -> runSearch());
        dupBox.addActionListener(e -> { rebuildTreeForCurrentState(); runSearch(); });
        formatBox.setSelectedItem(Exporter.Format.SVG);
        exportBtn.addActionListener(e -> doExport());
        exportBtn.setEnabled(false);

        if (catalog.isEmpty()) {
            status.setText("No discs added yet. Click “Add discs…” and choose your CorelGallery folder (or a single ISO).");
        } else {
            rebuildTree();
            runSearch();
            startDuplicateScan();
        }
    }

    private boolean hidingDupes() { return dupBox.isSelected() && catalog.duplicatesReady(); }

    private void clearSearch() {
        if (searchField.getText().isEmpty()) return;
        searchField.setText("");
        searchTimer.stop();
        runSearch();
        searchField.requestFocusInWindow();
    }

    private void rebuildTreeForCurrentState() {
        if (searchField.getText().isBlank()) rebuildTree();   // a search rebuilds the tree itself
    }

    /** Compares the previews of all CCX files in the background (cached after the first run), with a progress bar. */
    private void startDuplicateScan() {
        if (catalog.isEmpty()) return;
        dupBox.setEnabled(false);
        dupBar.setIndeterminate(true);
        dupBar.setString("Checking for duplicates…");
        dupBar.setVisible(true);
        java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong();
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() {
                catalog.computeDuplicates((phase, done, total) -> {
                    long now = System.currentTimeMillis();
                    if (done != total && now - last.get() < 120) return;
                    last.set(now);
                    SwingUtilities.invokeLater(() -> {
                        dupBar.setIndeterminate(false);
                        dupBar.setMaximum(Math.max(1, total));
                        dupBar.setValue(done);
                        dupBar.setString(phase + "  " + String.format("%,d / %,d", done, total));
                    });
                });
                return null;
            }
            protected void done() {
                dupBar.setVisible(false);
                try { get(); } catch (Exception e) { status.setText("Duplicate check failed: " + e); return; }
                dupBox.setEnabled(true);
                dupBox.setText("Hide duplicates (" + String.format("%,d", catalog.duplicateCount()) + ")");
                if (dupBox.isSelected()) { rebuildTreeForCurrentState(); runSearch(); }
            }
        }.execute();
    }

    /**
     * Moves the thumbnail window: prepare the visible page, then {@link #PRELOAD_PAGES} pages ahead in the direction
     * of scrolling; keep up to {@link #KEEP_PAST_PAGES} pages just scrolled past, never more than there are ahead.
     * The nearest {@link #RASTER_AHEAD_PAGES} / {@link #RASTER_BEHIND_PAGES} pages are kept drawn; the rest as art.
     */
    private void updateWanted() {
        ListModel<ClipartItem> m = grid.getModel();
        int n = m.getSize();
        int first = grid.getFirstVisibleIndex(), last = Math.min(grid.getLastVisibleIndex(), n - 1);
        if (first < 0 || last < first) { thumbs.clearQueue(); return; }
        if (lastFirst >= 0 && first != lastFirst) scrollingDown = first > lastFirst;
        lastFirst = first;

        int page = last - first + 1;
        todo.clear();
        past.clear();
        for (int i = first; i <= last; i++) todo.add(m.getElementAt(i));
        int ahead = 0;
        for (int d = 1; d <= page * PRELOAD_PAGES; d++, ahead++) {   // nearest first
            int i = scrollingDown ? last + d : first - d;
            if (i < 0 || i >= n) break;
            todo.add(m.getElementAt(i));
        }
        int pastCount = Math.min(page * KEEP_PAST_PAGES, ahead);
        for (int d = 1; d <= pastCount; d++) {
            int i = scrollingDown ? first - d : last + d;
            if (i < 0 || i >= n) break;
            past.add(m.getElementAt(i));
        }
        thumbs.setWindow(todo, page + Math.min(ahead, page * RASTER_AHEAD_PAGES), past, Math.min(past.size(), page * RASTER_BEHIND_PAGES));
    }

    /** Repaints the cell showing {@code it}, if it is on screen (finished prefetches cause no repaint). */
    private void repaintCell(ClipartItem it) {
        ListModel<ClipartItem> m = grid.getModel();
        int first = grid.getFirstVisibleIndex(), last = Math.min(grid.getLastVisibleIndex(), m.getSize() - 1);
        for (int i = Math.max(first, 0); i <= last; i++) {
            if (m.getElementAt(i) == it) {
                Rectangle r = grid.getCellBounds(i, i);
                if (r != null) grid.repaint(r);
            }
        }
    }

    private void applyCellSize() {
        grid.setFixedCellWidth(thumb + 24);
        grid.setFixedCellHeight(thumb + 40);
    }

    // ------------------------------------------------------------ category tree

    private static final class CatNode {
        final Catalog.Category cat;
        CatNode(Catalog.Category c) { cat = c; }
        @Override public String toString() { return cat.name + "  (" + cat.count + ")"; }
    }

    private static DefaultMutableTreeNode toNode(Catalog.Category c) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(new CatNode(c));
        for (Catalog.Category k : c.children) n.add(toNode(k));
        return n;
    }

    /** Rebuilds the tree for the current type filter, keeping the selected folder if it still exists. */
    private void rebuildTree() {
        Catalog.Filter f = (Catalog.Filter) filterBox.getSelectedItem();
        rebuildTree(catalog.categories(f, hidingDupes()), false);
    }

    private boolean treeFiltered;
    private String treeSig = "";

    private static StringBuilder treeSig(Catalog.Category c, StringBuilder sb) {
        sb.append(c.prefix).append(':').append(c.count).append(';');
        for (Catalog.Category k : c.children) treeSig(k, sb);
        return sb;
    }

    private void rebuildTree(Catalog.Category cats, boolean expandAll) {
        treeFiltered = expandAll;
        DefaultMutableTreeNode root = toNode(cats);
        updatingTree = true;
        try {
            tree.setModel(new DefaultTreeModel(root));
            DefaultMutableTreeNode sel = root;
            if (!category.isEmpty()) {
                // deepest surviving node on the way to the chosen folder (the folder itself may have no matches)
                DefaultMutableTreeNode cur = root;
                outer:
                while (true) {
                    for (int i = 0; i < cur.getChildCount(); i++) {
                        DefaultMutableTreeNode ch = (DefaultMutableTreeNode) cur.getChildAt(i);
                        String p = ((CatNode) ch.getUserObject()).cat.prefix;
                        if (category.regionMatches(true, 0, p, 0, p.length())) {
                            cur = ch;
                            sel = ch;
                            if (p.length() == category.length()) break outer;
                            continue outer;
                        }
                    }
                    break;
                }
                category = sel == root ? "" : ((CatNode) sel.getUserObject()).cat.prefix;
            }
            if (expandAll && root.getChildCount() > 0 && root.getDepth() >= 0 && countNodes(root) <= 400)
                for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            TreePath path = new TreePath(sel.getPath());
            tree.setSelectionPath(path);
            tree.expandPath(new TreePath(root.getPath()));
            if (sel != root) tree.scrollPathToVisible(path);
        } finally {
            updatingTree = false;
        }
    }

    private static int countNodes(DefaultMutableTreeNode n) {
        int c = 1;
        for (int i = 0; i < n.getChildCount(); i++) c += countNodes((DefaultMutableTreeNode) n.getChildAt(i));
        return c;
    }

    private void categoryChosen(TreeSelectionEvent e) {
        if (updatingTree) return;
        Object o = tree.getLastSelectedPathComponent();
        if (!(o instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof CatNode cn)) return;
        chooseCategory(cn.cat.prefix);
    }

    /** The user picked a folder: it becomes the search scope (and turns the scope checkbox on). */
    private void chooseCategory(String prefix) {
        if (prefix.equals(category) && scopeBox.isSelected()) return;
        category = prefix;
        scopeBox.setSelected(true);
        runSearch();
    }

    /** Moves the tree highlight to the folder holding {@code it}, expanding parents. Does not change the search scope. */
    private void revealInTree(ClipartItem it) {
        if (!(tree.getModel().getRoot() instanceof DefaultMutableTreeNode root)) return;
        String folder = it.folder() == null ? "" : it.folder() + "/";
        DefaultMutableTreeNode cur = root;
        outer:
        while (cur.getChildCount() > 0) {
            for (int i = 0; i < cur.getChildCount(); i++) {
                DefaultMutableTreeNode ch = (DefaultMutableTreeNode) cur.getChildAt(i);
                if (!(ch.getUserObject() instanceof CatNode cn)) continue;
                String p = cn.cat.prefix;
                if (folder.length() >= p.length() && folder.regionMatches(true, 0, p, 0, p.length())) {
                    cur = ch;
                    if (p.length() == folder.length()) break outer;
                    continue outer;
                }
            }
            break;
        }
        TreePath path = new TreePath(cur.getPath());
        boolean was = updatingTree;
        updatingTree = true;
        try {
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        } finally {
            updatingTree = was;
        }
    }

    private void runSearch() {
        String q = searchField.getText();
        Catalog.Filter f = (Catalog.Filter) filterBox.getSelectedItem();
        thumbs.clearQueue();
        boolean hide = hidingDupes() && f != Catalog.Filter.WEBART;
        if (q.isBlank()) {
            if (treeFiltered) rebuildTree();
            List<ClipartItem> raw = catalog.search(q, f, scopeBox.isSelected() ? category : "");
            results = hide ? catalog.withoutDuplicates(raw) : raw;
            hiddenDupes = raw.size() - results.size();
        } else {
            // search everywhere first so the tree can show only the folders that contain matches
            List<ClipartItem> rawAll = catalog.search(q, f, "");
            List<ClipartItem> all = hide ? catalog.withoutDuplicates(rawAll) : rawAll;
            Catalog.Category cats = catalog.categories(all);
            String sig = treeSig(cats, new StringBuilder()).toString();
            if (!treeFiltered || !sig.equals(treeSig)) rebuildTree(cats, true);   // same folders: leave the tree alone (keeps clicks/expansion)
            treeSig = sig;
            if (!scopeBox.isSelected() || category.isEmpty()) { results = all; hiddenDupes = rawAll.size() - all.size(); }
            else {
                String cat = category;
                List<ClipartItem> sub = new ArrayList<>(), rawSub = new ArrayList<>();
                for (ClipartItem it : rawAll)
                    if ((it.folder() + "/").regionMatches(true, 0, cat, 0, cat.length())) rawSub.add(it);
                results = hide ? catalog.withoutDuplicates(rawSub) : rawSub;
                hiddenDupes = rawSub.size() - results.size();
            }
        }
        scrollingDown = true;   // new results start at the top
        lastFirst = -1;
        grid.setModel(new AbstractListModel<>() {
            final List<ClipartItem> r = results;
            public int getSize() { return r.size(); }
            public ClipartItem getElementAt(int i) { return r.get(i); }
        });
        SwingUtilities.invokeLater(this::updateWanted);   // after the list has laid itself out
        status.setText(results.size() + " match" + (results.size() == 1 ? "" : "es")
                + (hiddenDupes > 0 ? " (" + String.format("%,d", hiddenDupes) + " duplicate" + (hiddenDupes == 1 ? "" : "s") + " hidden)" : "") + "  ·  " + catalog.itemCount()
                + " files indexed" + (catalog.webArtCount() > 0 ? "  ·  " + catalog.webArtCount() + " web-art GIFs" : "") + (catalog.hasCorelIndex() ? "  ·  Corel keywords on (" + catalog.corelKeywordCount() + ")" : ""));
        showSelected();
    }

    private void showSelected() {
        ClipartItem it = grid.getSelectedValue();
        exportBtn.setEnabled(it != null);
        if (it == null) { preview.set(null, null); info.setText(""); return; }
        revealInTree(it);
        int serial = ++loadSerial;
        Exporter.Format cur = (Exporter.Format) formatBox.getSelectedItem();
        if (!it.isVector() && (cur == Exporter.Format.SVG || cur == Exporter.Format.PDF || cur == Exporter.Format.EPS))
            formatBox.setSelectedItem(Exporter.Format.PNG);   // bitmaps can't be exported as vectors
        StringBuilder sb = new StringBuilder(catalog.displayName(it)).append("  (").append(it.fileName()).append(")\n").append(it.sourceName()).append(" : ").append(it.path())
                .append("\n").append(it.size()).append(" bytes");
        List<ClipartItem> twins = catalog.duplicatesOf(it);
        if (!twins.isEmpty()) {
            sb.append("\nIdentical preview, hidden").append(hidingDupes() ? "" : " when the filter is on").append(": ");
            for (int i = 0; i < Math.min(4, twins.size()); i++)
                sb.append(i > 0 ? ", " : "").append(twins.get(i).sourceName().replaceAll("(?i)\\.iso$", "")).append(":").append(twins.get(i).path());
            if (twins.size() > 4) sb.append(" … +").append(twins.size() - 4);
        }
        List<String> kw = catalog.keywordsOf(it);
        if (!kw.isEmpty()) sb.append("\nKeywords: ").append(String.join(", ", kw));
        info.setText(sb.toString());
        info.setCaretPosition(0);
        preview.set(null, "Loading…");
        new SwingWorker<Drawing, Void>() {
            protected Drawing doInBackground() throws Exception { return DrawingLoader.load(catalog, it); }
            protected void done() {
                if (serial != loadSerial) return;
                try {
                    Drawing d = get();
                    preview.set(d, null);
                    if (d instanceof AnimatedImage a && a.frameCount() > 1) {
                        formatBox.setSelectedItem(Exporter.Format.ORIGINAL);   // keeps the animation
                        info.append("\nAnimated GIF, " + a.frameCount() + " frames");
                    }
                }
                catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    preview.set(null, "Cannot display: " + c.getMessage());
                }
            }
        }.execute();
    }

    private void addSources() {
        File last = new File(PREFS.get("lastDiscDir", System.getProperty("user.home")));
        JFileChooser fc = new JFileChooser(last.isDirectory() ? last : new File(System.getProperty("user.home")));
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fc.setDialogTitle("Choose a folder of disc images, a single ISO, or a folder of clipart");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        File dir = fc.getSelectedFile().isDirectory() ? fc.getSelectedFile() : fc.getSelectedFile().getParentFile();
        if (dir != null) PREFS.put("lastDiscDir", dir.getAbsolutePath());
        runScan("Scanning…", log -> catalog.addPath(p, log));
    }

    /** Corel Gallery's own icon from disc 1 for the Dock and window, read in the background (cached after the first time). */
    public void loadAppIcon() {
        new SwingWorker<BufferedImage, Void>() {
            protected BufferedImage doInBackground() { return AppIcon.load(catalog); }
            protected void done() {
                try { AppIcon.apply(get(), GalleryFrame.this); } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void rebuildIndexes() {
        if (catalog.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No discs have been added yet.", "Rebuild Indexes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "Re-read every disc and rebuild the file index, long names, Corel keywords and duplicate groups?\n"
                        + "This reads all discs again and the duplicate check runs from scratch.",
                "Rebuild Indexes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        runScan("Rebuilding indexes…", catalog::rebuildAll);
    }

    private interface ScanTask { void run(java.util.function.Consumer<String> log) throws Exception; }

    /** Runs a catalog scan in the background behind a modal log window, then refreshes the tree, grid and duplicates. */
    private void runScan(String title, ScanTask task) {
        JDialog dlg = new JDialog(this, title, true);
        JTextArea log = new JTextArea(14, 60);
        log.setEditable(false);
        dlg.add(new JScrollPane(log));
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        new SwingWorker<Void, String>() {
            protected Void doInBackground() throws Exception {
                task.run(this::publish);
                return null;
            }
            protected void process(List<String> l) { for (String s : l) log.append(s + "\n"); }
            protected void done() {
                dlg.dispose();
                try { get(); } catch (Exception e) { JOptionPane.showMessageDialog(GalleryFrame.this, e.getCause() != null ? e.getCause().getMessage() : e.getMessage()); }
                rebuildTree();
                runSearch();
                startDuplicateScan();
                loadAppIcon();   // disc 1 may just have been added
            }
        }.execute();
        dlg.setVisible(true);
    }

    private void showReport() {
        JTextArea t = new JTextArea(catalog.report(), 30, 100);
        t.setEditable(false);
        t.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(t), "Disc report", JOptionPane.PLAIN_MESSAGE);
    }

    private void maybePopup(MouseEvent e, JPopupMenu popup) {
        if (!e.isPopupTrigger()) return;
        int i = grid.locationToIndex(e.getPoint());
        if (i < 0 || !grid.getCellBounds(i, i).contains(e.getPoint())) return;
        if (!grid.isSelectedIndex(i)) grid.setSelectedIndex(i);
        grid.requestFocusInWindow();
        popup.show(grid, e.getX(), e.getY());
    }

    private JPopupMenu buildGridMenu() {
        JPopupMenu m = new JPopupMenu();
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuItem copy = new JMenuItem("Copy Image");
        copy.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, mask));
        copy.addActionListener(e -> copySelected());
        m.add(copy);
        JMenuItem copyFile = new JMenuItem("Copy as File");
        copyFile.addActionListener(e -> copySelectedFile());
        m.add(copyFile);
        m.addSeparator();
        JMenuItem ql = new JMenuItem("Quick Look");
        ql.addActionListener(e -> { if (grid.getSelectedValue() != null) quickLook.open(); });
        m.add(ql);
        m.addSeparator();
        JMenu exp = new JMenu("Export As");
        for (Exporter.Format f : Exporter.Format.values()) {
            JMenuItem mi = new JMenuItem(f.toString());
            mi.addActionListener(e -> doExport(f));
            exp.add(mi);
        }
        m.add(exp);
        JMenuItem expSel = new JMenuItem("Export…");
        expSel.addActionListener(e -> doExport());
        m.add(expSel);
        m.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                ClipartItem it = grid.getSelectedValue();
                boolean vec = it != null && it.isVector();
                for (Component c : exp.getMenuComponents()) {
                    if (c instanceof JMenuItem mi) {
                        String t = mi.getText();
                        boolean needsVec = t.startsWith("SVG") || t.startsWith("PDF") || t.startsWith("EPS");
                        mi.setEnabled(it != null && (vec || !needsVec));
                    }
                }
            }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        return m;
    }

    /** Puts the selected item on the clipboard as an image (vectors are rendered at the PNG size; bitmaps at native size). */
    private void copySelected() {
        ClipartItem it = grid.getSelectedValue();
        if (it == null) return;
        status.setText("Copying…");
        new SwingWorker<BufferedImage, Void>() {
            protected BufferedImage doInBackground() throws Exception {
                Drawing d = DrawingLoader.load(catalog, it);
                Rectangle2D b = d.bounds();
                int size = d.asVector() != null ? pngSize : (int) Math.max(1, Math.ceil(Math.max(b.getWidth(), b.getHeight())));
                return Exporter.render(d, size);
            }
            protected void done() {
                try {
                    BufferedImage img = get();
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(img), null);
                    status.setText("Copied " + catalog.displayName(it) + " to the clipboard (" + img.getWidth() + " × " + img.getHeight() + ")");
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(GalleryFrame.this, c.getMessage(), "Copy failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * An image for the clipboard.  On macOS, Java writes its TIFF encoding of an image under every image type it
     * offers, including public.png, so apps that paste the PNG got undecodable data (a blank white box).  There we
     * offer real PNG bytes for public.png ourselves and leave Java's conversion to TIFF only.
     */
    private static final class ImageSelection implements Transferable {
        private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        private static final DataFlavor PNG = pngFlavor();

        private static DataFlavor pngFlavor() {
            try {
                DataFlavor f = new DataFlavor("image/png;class=java.io.InputStream");
                if (MAC && SystemFlavorMap.getDefaultFlavorMap() instanceof SystemFlavorMap fm) {
                    fm.setNativesForFlavor(DataFlavor.imageFlavor, new String[]{"TIFF"});
                    fm.setNativesForFlavor(f, new String[]{"PNG"});
                    fm.setFlavorsForNative("PNG", new DataFlavor[]{f});
                }
                return f;
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        private final BufferedImage img;
        private byte[] png;

        ImageSelection(BufferedImage img) { this.img = img; }

        public DataFlavor[] getTransferDataFlavors() {
            return MAC ? new DataFlavor[]{PNG, DataFlavor.imageFlavor} : new DataFlavor[]{DataFlavor.imageFlavor};
        }
        public boolean isDataFlavorSupported(DataFlavor f) {
            for (DataFlavor x : getTransferDataFlavors()) if (x.equals(f)) return true;
            return false;
        }
        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException, IOException {
            if (DataFlavor.imageFlavor.equals(f)) return img;
            if (MAC && PNG.equals(f)) {
                synchronized (this) {
                    if (png == null) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        javax.imageio.ImageIO.write(img, "png", bo);
                        png = bo.toByteArray();
                    }
                }
                return new java.io.ByteArrayInputStream(png);
            }
            throw new UnsupportedFlavorException(f);
        }
    }

    /** Puts the selected item on the clipboard as a file (SVG for vectors, PNG for bitmaps, the original for GIFs) for pasting into Finder. */
    private void copySelectedFile() {
        TransferHandler th = grid.getTransferHandler();
        if (grid.getSelectedValue() == null) return;
        th.exportToClipboard(grid, Toolkit.getDefaultToolkit().getSystemClipboard(), TransferHandler.COPY);
        status.setText("Copied file to the clipboard");
    }

    private void doExport() { doExport((Exporter.Format) formatBox.getSelectedItem()); }

    private void doExport(Exporter.Format f) {
        ClipartItem it = grid.getSelectedValue();
        if (it == null) return;
        int size = f == Exporter.Format.PDF || f == Exporter.Format.EPS ? 576 : 1024;
        if (f == Exporter.Format.PNG) {
            JSpinner sp = new JSpinner(new SpinnerNumberModel(pngSize, 16, 16384, 64));
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            p.add(new JLabel("Longest side (pixels):"));
            p.add(sp);
            if (JOptionPane.showConfirmDialog(this, p, "PNG size", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) return;
            try { sp.commitEdit(); } catch (java.text.ParseException ignored) {}
            size = pngSize = (Integer) sp.getValue();
        }
        final int exportSize = size;
        String ext = f == Exporter.Format.ORIGINAL ? it.ext() : f.ext;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(catalog.exportName(it) + "." + ext));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception { Exporter.exportItem(catalog, it, f, out, exportSize); return null; }
            protected void done() {
                try { get(); status.setText("Saved " + out); }
                catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(GalleryFrame.this, c.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------ pieces

    private final class Cell extends JPanel implements ListCellRenderer<ClipartItem> {
        private BufferedImage img;
        private String name;
        private boolean sel;
        Cell() { setOpaque(true); }
        public Component getListCellRendererComponent(JList<? extends ClipartItem> l, ClipartItem v, int i, boolean s, boolean f) {
            name = catalog.displayName(v);
            sel = s;
            img = thumbs.get(v);
            setBackground(s ? l.getSelectionBackground() : Color.WHITE);
            return this;
        }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            int w = getWidth();
            int t = thumb;
            double sc = screenScale(g);
            if (sc != screenScale) {      // first paint, or moved to a screen of another density: redraw at its resolution
                screenScale = sc;
                thumbs.setSize(t, sc);
                SwingUtilities.invokeLater(() -> { updateWanted(); grid.repaint(); });
            }
            if (img != null) drawImageCentred(g, img, (w - t) / 2.0, 6, t, sc);
            else { g.setColor(Color.LIGHT_GRAY); g.drawString("…", w / 2 - 4, 6 + t / 2); }
            g.setColor(sel ? Color.WHITE : Color.DARK_GRAY);
            g.setFont(getFont().deriveFont(11f));
            String n = name;
            FontMetrics fm = g.getFontMetrics();
            while (n.length() > 3 && fm.stringWidth(n) > w - 6) n = n.substring(0, n.length() - 2) + "…";
            g.drawString(n, (w - fm.stringWidth(n)) / 2, t + 24);
        }
    }

    /** macOS Quick Look style overlay: space (or double-click) on a selected item shows it large; space/esc closes. */
    private final class QuickLook extends JPanel {
        private Drawing drawing;
        private String message;
        private ClipartItem shown;
        private BufferedImage cached;
        private int cachedW, cachedH;
        private int serial;
        private final Animator animator = new Animator();

        QuickLook() {
            setOpaque(false);
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { close(); }
            });
            bind(KeyEvent.VK_SPACE, "close", this::close);
            bind(KeyEvent.VK_ESCAPE, "close", this::close);
            bind(KeyEvent.VK_ENTER, "close", this::close);
            bind(KeyEvent.VK_LEFT, "prev", () -> move(-1));
            bind(KeyEvent.VK_RIGHT, "next", () -> move(1));
            bind(KeyEvent.VK_UP, "up", () -> move(-columns()));
            bind(KeyEvent.VK_DOWN, "down", () -> move(columns()));
        }

        private void bind(int key, String name, Runnable r) {
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
            getActionMap().put(name, new AbstractAction() {
                public void actionPerformed(ActionEvent e) { r.run(); }
            });
        }

        private int columns() { return Math.max(1, grid.getWidth() / Math.max(1, grid.getFixedCellWidth())); }

        void open() {
            if (grid.getSelectedValue() == null) return;
            setVisible(true);
            requestFocusInWindow();
            load();
        }

        void close() {
            serial++;
            animator.stop();
            setVisible(false);
            drawing = null;
            cached = null;
            grid.requestFocusInWindow();
        }

        private void move(int delta) {
            int n = grid.getModel().getSize();
            if (n == 0) return;
            int i = Math.max(0, Math.min(n - 1, grid.getSelectedIndex() + delta));
            grid.setSelectedIndex(i);
            grid.ensureIndexIsVisible(i);
            load();
        }

        private void load() {
            ClipartItem it = grid.getSelectedValue();
            if (it == null) { close(); return; }
            shown = it;
            animator.stop();
            drawing = null;
            cached = null;
            message = "Loading…";
            int mine = ++serial;
            repaint();
            new SwingWorker<Drawing, Void>() {
                protected Drawing doInBackground() throws Exception { return DrawingLoader.load(catalog, it); }
                protected void done() {
                    if (mine != serial) return;
                    try {
                        drawing = get();
                        message = null;
                        animator.start(drawing, () -> { cached = null; repaint(); });
                    }
                    catch (Exception e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        message = "Cannot display: " + c.getMessage();
                    }
                    repaint();
                }
            }.execute();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setColor(new Color(0, 0, 0, 205));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.setFont(getFont().deriveFont(Font.BOLD, 15f));
                FontMetrics fm = g.getFontMetrics();
                String title = shown == null ? "" : catalog.displayName(shown);
                g.drawString(title, (getWidth() - fm.stringWidth(title)) / 2, 34);
                g.setFont(getFont().deriveFont(12f));
                g.setColor(new Color(255, 255, 255, 150));
                fm = g.getFontMetrics();
                String hint = "space or esc to close    ← → ↑ ↓ to browse";
                g.drawString(hint, (getWidth() - fm.stringWidth(hint)) / 2, getHeight() - 18);
                if (drawing != null) {
                    int maxW = Math.max(50, getWidth() - 120), maxH = Math.max(50, getHeight() - 120);
                    Rectangle2D b = drawing.bounds();
                    double aspect = Math.max(1e-6, b.getWidth()) / Math.max(1e-6, b.getHeight());
                    int w = (int) Math.min(maxW, maxH * aspect), h = (int) Math.min(maxH, maxW / aspect);
                    w = Math.max(1, w); h = Math.max(1, h);
                    double sc = screenScale(g);
                    int pw = (int) Math.round(w * sc), ph = (int) Math.round(h * sc);   // device pixels
                    if (cached == null || cachedW != pw || cachedH != ph) {
                        cached = renderFit(drawing, pw, ph);
                        cachedW = pw;
                        cachedH = ph;
                    }
                    int x = (getWidth() - w) / 2, y = 52 + (maxH - h) / 2 + 8;
                    g.setColor(new Color(0, 0, 0, 120));
                    g.fillRect(x + 3, y + 5, w, h);
                    drawDeviceImage(g, cached, x, y, sc);
                } else if (message != null) {
                    g.setFont(getFont().deriveFont(14f));
                    g.setColor(Color.WHITE);
                    fm = g.getFontMetrics();
                    g.drawString(message, (getWidth() - fm.stringWidth(message)) / 2, getHeight() / 2);
                }
            } finally { g.dispose(); }
        }
    }

    /** Device pixels per logical pixel for this graphics (2 on Retina screens). */
    private static double screenScale(Graphics2D g) {
        double s = g.getTransform().getScaleX();
        return s > 0 ? s : 1;
    }

    /**
     * Draws a device-resolution image centred in a {@code box}-sized square at (x, y), shrinking it if it is larger,
     * but never stretching it: an image with fewer pixels than the box is shown at its own size.  Placed on whole
     * device pixels, so an image of exactly the right size is copied 1:1.
     */
    private static void drawImageCentred(Graphics2D g, BufferedImage img, double x, double y, int box, double sc) {
        int iw = img.getWidth(), ih = img.getHeight();
        int boxPx = (int) Math.round(box * sc);
        double k = Math.min(1, (double) boxPx / Math.max(iw, ih));          // device pixels per image pixel, <= 1
        double w = iw * k / sc, h = ih * k / sc;                             // logical size shown
        double dx = Math.round((x + (box - w) / 2) * sc) / sc, dy = Math.round((y + (box - h) / 2) * sc) / sc;
        Graphics2D c = (Graphics2D) g.create();
        try {
            if (k < 1) c.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            c.translate(dx, dy);
            c.scale(k / sc, k / sc);
            c.drawImage(img, 0, 0, null);
        } finally { c.dispose(); }
    }

    /** Draws an image made at device resolution into w x h logical pixels at (x, y), 1:1 with the screen's pixels. */
    private static void drawDeviceImage(Graphics2D g, BufferedImage img, int x, int y, double sc) {
        Graphics2D c = (Graphics2D) g.create();
        try {
            c.translate(x, y);
            c.scale(1 / sc, 1 / sc);
            c.drawImage(img, 0, 0, null);
        } finally { c.dispose(); }
    }

    /** Renders onto a white w x h card, scaling the drawing to fill it. */
    private static BufferedImage renderFit(Drawing d, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Rectangle2D b = d.bounds();
            double pad = d.asVector() != null ? Math.min(w, h) * 0.03 : 0;
            double sc = Math.min((w - 2 * pad) / Math.max(b.getWidth(), 1e-9), (h - 2 * pad) / Math.max(b.getHeight(), 1e-9));
            g.translate(w / 2.0, h / 2.0);
            g.scale(sc, sc);
            g.translate(-b.getCenterX(), -b.getCenterY());
            d.draw(g);
        } finally { g.dispose(); }
        return img;
    }

    /** Plays an animated GIF by stepping its current frame on a Swing timer. */
    private static final class Animator {
        private Timer timer;
        void start(Drawing d, Runnable onFrame) {
            stop();
            if (!(d instanceof AnimatedImage a) || a.frameCount() < 2) return;
            timer = new Timer(a.delayMs(0), null);
            timer.setRepeats(true);
            timer.addActionListener(e -> {
                a.setFrame(a.current() + 1);
                timer.setDelay(a.delayMs(a.current()));
                onFrame.run();
            });
            timer.start();
        }
        void stop() { if (timer != null) { timer.stop(); timer = null; } }
    }

    private static final class PreviewPanel extends JPanel {
        private Drawing drawing;
        private String message;
        private BufferedImage cached;
        private int cachedSize;
        private final Animator animator = new Animator();
        PreviewPanel() { setBackground(Color.WHITE); }
        void set(Drawing d, String msg) {
            drawing = d; message = msg; cached = null; repaint();
            animator.start(d, () -> { cached = null; repaint(); });
        }
        @Override public void removeNotify() { animator.stop(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            int s = Math.min(getWidth(), getHeight());
            if (drawing != null && s > 8) {
                double sc = screenScale(g);
                int ps = (int) Math.round(s * sc);   // device pixels
                if (cached == null || cachedSize != ps) { cached = ThumbnailCache.render(drawing, ps, sc); cachedSize = ps; }
                drawDeviceImage(g, cached, (getWidth() - s) / 2, (getHeight() - s) / 2, sc);
            } else if (message != null) {
                g.setColor(Color.GRAY);
                g.drawString(message, 12, 24);
            }
        }
    }

    /** Drag a result to Finder/desktop: exports an SVG (vectors) or PNG (bitmaps) to a temp file. */
    private final class DragOut extends TransferHandler {
        @Override public int getSourceActions(JComponent c) { return COPY; }
        @Override protected Transferable createTransferable(JComponent c) {
            ClipartItem it = grid.getSelectedValue();
            if (it == null) return null;
            try {
                Path dir = Files.createTempDirectory("clipart");
                dir.toFile().deleteOnExit();
                boolean vec = it.isVector(), gif = it.ext().equals("gif");   // GIFs go as the original, so animations survive
                Path out = dir.resolve(catalog.exportName(it) + (gif ? ".gif" : vec ? ".svg" : ".png"));
                Exporter.exportItem(catalog, it, gif ? Exporter.Format.ORIGINAL : vec ? Exporter.Format.SVG : Exporter.Format.PNG, out, 1024);
                out.toFile().deleteOnExit();
                List<File> files = List.of(out.toFile());
                return new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
                    public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.javaFileListFlavor.equals(f); }
                    public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                        if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                        return files;
                    }
                };
            } catch (Exception e) {
                return null;
            }
        }
    }
}
