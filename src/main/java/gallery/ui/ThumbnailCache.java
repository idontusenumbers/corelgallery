package gallery.ui;

import gallery.catalog.Catalog;
import gallery.catalog.ClipartItem;
import gallery.catalog.DrawingLoader;
import gallery.codec.CcxDecoder;
import gallery.drawing.Drawing;
import gallery.source.Source;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Thumbnails for the grid, cached in two tiers around the scroll position, as the grid describes with
 * {@link #setWindow}:
 * <ul>
 * <li><b>art</b>, for the whole window: each item's drawing in a compact form ({@link CompactDrawing} for CCX vector
 *     art, a handful of primitive arrays; for bitmaps, the original, or a copy shrunk to exactly the size it is
 *     drawn at, so a bitmap is never enlarged unless the original itself is smaller);</li>
 * <li><b>rasters</b>, for the part of the window nearest the visible cells: the art drawn at the thumbnail size.</li>
 * </ul>
 * Rasters come from a pool and are reused, and render threads decode with reusable buffers, so scrolling makes very
 * little garbage, and so few GC pauses.
 * <p>
 * Threads: the entry table and each entry's raster belong to the UI thread ({@link #setWindow}, {@link #get}, and
 * results, which are published through the {@code ui} executor).  Render threads take work from a task list under
 * {@code lock}.
 */
public final class ThumbnailCache {
    /**
     * Memory for spare rasters kept for reuse (a raster is 4 bytes a device pixel: 64 KB at 128 px, 1 MB at 256 px on
     * a Retina screen).  Rasters are premultiplied ARGB, the screen pipeline's own format (no conversion when shown).
     */
    private static final long POOL_BYTES = 32L << 20;
    /** Margin around the drawing inside a thumbnail, in pixels. */
    private static final double PAD = 4;
    private static final Color CLEAR = new Color(0, true), BROKEN_GREY = new Color(0xBBBBBB);
    /** Art for items that cannot be loaded: drawn as a crossed box. */
    private static final Rectangle2D UNIT = new Rectangle2D.Double(0, 0, 1, 1);
    private static final Drawing BROKEN = new Drawing() {
        public Rectangle2D bounds() { return UNIT; }
        public void draw(Graphics2D g) { }
    };

    private static final class Entry {
        final ClipartItem item;
        Entry(ClipartItem item) { this.item = item; }

        // UI thread only
        int gen;
        BufferedImage img;

        volatile Drawing art;
        volatile boolean wantRaster;
        volatile int imgSize;           // size img was drawn at; 0 = no raster
        volatile boolean live = true;   // still in the table

        // guarded by lock
        boolean busy;                   // a render thread is working on it
        boolean rasterPending;          // a finished raster is on its way to the UI thread
    }

    private final Catalog catalog;
    private final Executor ui;
    private final Consumer<ClipartItem> onRendered;
    private volatile int size;          // raster size in device pixels
    private volatile double scale = 1;  // device pixels per logical pixel (2 on Retina screens)

    // UI thread only
    private final HashMap<ClipartItem, Entry> entries = new HashMap<>();
    private int gen;
    private Entry[] staging = new Entry[256];   // the next task list, swapped in by setWindow

    private final Object lock = new Object();
    // guarded by lock: the window's items in priority order, walked from cursor; and painted items, which go first
    private Entry[] tasks = new Entry[256];
    private int taskCount, cursor;
    private final ArrayDeque<Entry> urgent = new ArrayDeque<>();

    private final ArrayDeque<BufferedImage> pool = new ArrayDeque<>();   // guarded by itself

    /**
     * @param ui         runs tasks on the UI thread
     * @param onRendered runs on the UI thread when an item's raster changes
     */
    public ThumbnailCache(Catalog catalog, int size, Executor ui, Consumer<ClipartItem> onRendered) {
        this.catalog = catalog;
        this.size = size;
        this.ui = ui;
        this.onRendered = onRendered;
        int n = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(this::work, "thumbs");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * UI thread.  Changes the thumbnail size: {@code logical} pixels on a screen with {@code screenScale} device
     * pixels per logical one; rasters are drawn at device resolution.  Rasters at the old size stay usable until
     * redrawn (shrunk if larger; shown at their own size if smaller, never stretched).  Bitmaps that were shrunk for
     * the old size are reloaded from the original when the new size needs more pixels.
     */
    public void setSize(int logical, double screenScale) {
        int newSize = (int) Math.max(1, Math.round(logical * screenScale));
        if (newSize == size && screenScale == scale) return;
        scale = screenScale;
        size = newSize;   // render threads now treat bitmaps shrunk for a smaller size as missing (see usable)
        synchronized (pool) { pool.clear(); }
        synchronized (lock) {
            cursor = 0;          // every raster in the window needs drawing again
            urgent.clear();
            lock.notifyAll();
        }
    }

    /**
     * UI thread.  Moves the window.
     *
     * @param todo       items to prepare, most important first (visible, then ahead)
     * @param rasterTodo how many of the first {@code todo} items need rasters (the rest: art only)
     * @param past       items just scrolled past: kept if already prepared, never prepared
     * @param rasterPast how many of the first {@code past} items keep their rasters
     *                   Everything else is dropped, and queued work outside the window is skipped.
     */
    public void setWindow(List<ClipartItem> todo, int rasterTodo, List<ClipartItem> past, int rasterPast) {
        int g = ++gen, n = todo.size();
        if (staging.length < n) staging = new Entry[n * 2];
        else Arrays.fill(staging, null);
        for (int i = 0; i < n; i++) staging[i] = mark(todo.get(i), g, i < rasterTodo);
        for (int i = 0; i < past.size(); i++) mark(past.get(i), g, i < rasterPast);
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.gen != g) {
                it.remove();
                e.live = false;
                dropRaster(e);
            } else if (!e.wantRaster) {
                dropRaster(e);
            }
        }
        synchronized (lock) {
            Entry[] t = tasks;
            tasks = staging;
            staging = t;
            taskCount = n;
            cursor = 0;
            urgent.clear();
            lock.notifyAll();
        }
    }

    private Entry mark(ClipartItem item, int g, boolean raster) {
        Entry e = entries.get(item);
        if (e == null) entries.put(item, e = new Entry(item));
        e.gen = g;
        e.wantRaster = raster;
        return e;
    }

    private void dropRaster(Entry e) {
        if (e.img == null) return;
        recycle(e.img);
        e.img = null;
        e.imgSize = 0;
    }

    /** UI thread.  Empties the window: nothing is prepared and everything is dropped. */
    public void clearQueue() { setWindow(List.of(), 0, List.of(), 0); }

    /**
     * UI thread.  The item's raster (possibly at an older size: the caller scales it), or null.  A painted item
     * whose raster is missing jumps the queue, in case the window has not caught up with the scroll position.
     */
    public BufferedImage get(ClipartItem item) {
        Entry e = entries.get(item);
        if (e == null) {
            entries.put(item, e = new Entry(item));
            e.gen = gen;
        }
        e.wantRaster = true;
        if (e.imgSize != size) {
            synchronized (lock) {
                if (!e.busy && !e.rasterPending && !urgent.contains(e)) {
                    urgent.addFirst(e);
                    lock.notify();
                }
            }
        }
        return e.img;
    }

    // ------------------------------------------------------------ render threads

    private boolean needsWork(Entry e) {
        return e.live && !e.busy && (!usable(e.art) || e.wantRaster && !e.rasterPending && e.imgSize != size);
    }

    /** False for missing art, and for a bitmap shrunk for a smaller size than the current one (it would be enlarged). */
    private boolean usable(Drawing art) {
        return art != null && !(art instanceof Bitmap b && b.reduced && b.longest < target(size, scale));
    }

    /** The next entry to work on (marked busy): painted ones first, then the window in order. */
    private Entry next() throws InterruptedException {
        synchronized (lock) {
            for (;;) {
                Entry e;
                while ((e = urgent.pollFirst()) != null)
                    if (needsWork(e)) { e.busy = true; return e; }
                while (cursor < taskCount) {
                    e = tasks[cursor++];
                    if (needsWork(e)) { e.busy = true; return e; }
                }
                lock.wait();
            }
        }
    }

    private void work() {
        Source.Buffer buf = new Source.Buffer();
        CcxDecoder.Scratch scratch = new CcxDecoder.Scratch(true);
        try {
            for (;;) {
                Entry e = next();
                try {
                    Drawing art = e.art;
                    if (!usable(art)) e.art = art = load(e.item, buf, scratch);
                    int sz = size;
                    double sc = scale;
                    if (e.live && e.wantRaster && e.imgSize != sz) {
                        BufferedImage img = take(sz);
                        paint(art, img, sz, sc);
                        synchronized (lock) { e.rasterPending = true; }
                        ui.execute(() -> publish(e, img, sz));
                    }
                } catch (Throwable t) {
                    e.art = BROKEN;
                } finally {
                    synchronized (lock) { e.busy = false; }
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    /** UI thread: installs a finished raster, unless the window or size has moved on meanwhile. */
    private void publish(Entry e, BufferedImage img, int sz) {
        synchronized (lock) { e.rasterPending = false; }
        if (!e.live || !e.wantRaster || sz != size) {
            recycle(img);
            if (e.live && e.wantRaster) synchronized (lock) { urgent.addFirst(e); lock.notify(); }   // redo at the new size
            return;
        }
        BufferedImage old = e.img;
        e.img = img;
        e.imgSize = sz;
        if (old != null) recycle(old);
        onRendered.accept(e.item);
    }

    /** The item's art: compact vectors for CCX/CMX, a shrunk copy for bitmaps, {@link #BROKEN} if unreadable. */
    private Drawing load(ClipartItem item, Source.Buffer buf, CcxDecoder.Scratch scratch) {
        try {
            String ext = item.ext();
            if (ext.equals("ccx") || ext.equals("cmx")) {
                catalog.read(item, buf);
                try {
                    return CcxDecoder.decodeCompact(buf.data, buf.length, scratch);
                } catch (IOException | RuntimeException e) {
                    // not decodable as vectors: the general loader falls back to the embedded preview, or explains
                    return bitmap(DrawingLoader.load(ext, Arrays.copyOf(buf.data, buf.length)), size, scale);
                }
            }
            return bitmap(DrawingLoader.load(catalog, item), size, scale);
        } catch (Throwable e) {
            return BROKEN;
        }
    }

    /** Device pixels available for the drawing in a raster of {@code size} device pixels. */
    private static int target(int size, double scale) { return (int) Math.max(1, Math.round(size - 2 * PAD * scale)); }

    /**
     * Bitmap art.  {@code reduced}: a shrunk copy, whose longest side is {@code longest}; otherwise the original
     * resolution, which may be enlarged.
     */
    private record Bitmap(BufferedImage img, Rectangle2D bounds, boolean reduced, int longest) implements Drawing {
        public void draw(Graphics2D g) { g.drawImage(img, 0, 0, null); }
    }

    /**
     * Bitmap art for a thumbnail of size {@code sz}: the original if it fits, otherwise a copy shrunk to exactly the
     * size it will be drawn at (so it is resampled once, and the cache never holds full-size images).
     */
    private static Drawing bitmap(Drawing d, int sz, double scale) {
        Rectangle2D b = d.bounds();
        double longest = Math.max(1, Math.max(b.getWidth(), b.getHeight()));
        int target = target(sz, scale);
        boolean reduced = longest > target;
        double s = reduced ? target / longest : 1;
        int w = (int) Math.max(1, Math.round(b.getWidth() * s)), h = (int) Math.max(1, Math.round(b.getHeight() * s));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(w / Math.max(b.getWidth(), 1e-9), h / Math.max(b.getHeight(), 1e-9));
            g.translate(-b.getX(), -b.getY());
            d.draw(g);
        } finally { g.dispose(); }
        return new Bitmap(img, new Rectangle2D.Double(0, 0, w, h), reduced, Math.max(w, h));
    }

    // ------------------------------------------------------------ raster pool

    private BufferedImage take(int sz) {
        BufferedImage img;
        synchronized (pool) { img = pool.poll(); }
        return img != null && img.getWidth() == sz ? img : new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB_PRE);
    }

    private void recycle(BufferedImage img) {
        if (img.getWidth() != size) return;
        long max = Math.max(8, POOL_BYTES / (4L * img.getWidth() * img.getHeight()));
        synchronized (pool) { if (pool.size() < max) pool.push(img); }
    }

    // ------------------------------------------------------------ drawing

    /** Draws {@code d} centred in a new {@code size}-pixel square image (also used by the Quick Look view). */
    public static BufferedImage render(Drawing d, int size) { return render(d, size, 1); }

    /** As {@link #render(Drawing, int)}, for a screen with {@code scale} device pixels per logical pixel. */
    public static BufferedImage render(Drawing d, int size, double scale) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        paint(d, img, size, scale);
        return img;
    }

    /** Clears {@code img} and draws {@code d} centred in it; margins and lines are {@code scale} times thicker. */
    private static void paint(Drawing d, BufferedImage img, int size, double scale) {
        Graphics2D g = img.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(CLEAR);
            g.fillRect(0, 0, size, size);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (d == BROKEN) {
                g.setColor(BROKEN_GREY);
                g.setStroke(new BasicStroke((float) (2 * scale)));
                int m = size / 4;
                g.drawRect(m, m, size - 2 * m, size - 2 * m);
                g.drawLine(m, m, size - m, size - m);
                g.drawLine(size - m, m, m, size - m);
                return;
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Rectangle2D b = d.bounds();
            int fit = target(size, scale);
            double s = Math.min(fit / Math.max(b.getWidth(), 1e-9), fit / Math.max(b.getHeight(), 1e-9));
            if (d instanceof Bitmap bm && s == 1) {   // already exactly the size it is drawn at: copy it, on whole pixels
                g.drawImage(bm.img(), (size - bm.img().getWidth()) / 2, (size - bm.img().getHeight()) / 2, null);
                return;
            }
            g.translate(size / 2.0, size / 2.0);
            g.scale(s, s);
            g.translate(-b.getCenterX(), -b.getCenterY());
            d.draw(g);
        } finally { g.dispose(); }
    }
}
