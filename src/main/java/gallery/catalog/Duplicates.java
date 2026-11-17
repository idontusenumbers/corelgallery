package gallery.catalog;

import gallery.codec.CcxDecoder;
import gallery.drawing.VectorDrawing;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.geom.PathIterator;

/**
 * Finds clipart files that are the same picture stored more than once (different folders or discs).
 * Every CCX file carries a small preview bitmap (the RIFF "DISP" chunk, a 96x96 paletted DIB). Two copies of a
 * drawing rarely have byte-identical previews (different geometry anti-aliases differently), so previews are compared
 * by look in three steps:
 * <ol>
 * <li>each preview is reduced to a 32x32 "ink" thumbnail, a 4x4 colour thumbnail and a 64-bit hash (in memory);</li>
 * <li>candidate pairs share a slice of the hash and look alike at that size;</li>
 * <li>candidates are re-read at full 96x96 and compared with 1-pixel tolerance: same-named files may differ by up to
 * 6% of the drawn pixels, differently named ones must be practically identical, recoloured ones never match, and
 * differently named files in one folder (letters of an alphabet, calendar days) never match.</li>
 * </ol>
 * From each group of matches one copy is kept, the one with the smoothest geometry (most curves), and the rest are
 * hidden. The result is cached in dupes.ser.
 */
final class Duplicates implements Serializable {
    private static final long serialVersionUID = 1L;
    static final int VERSION = 12;

    interface Progress { void update(String phase, int done, int total); }

    final int version;
    final String signature;
    /** kept copy -> the copies hidden in its favour */
    final Map<ClipartItem, List<ClipartItem>> groups;
    private transient volatile Set<ClipartItem> hidden;

    Duplicates(String signature, Map<ClipartItem, List<ClipartItem>> groups) {
        this.version = VERSION;
        this.signature = signature;
        this.groups = groups;
    }

    Set<ClipartItem> hidden() {
        Set<ClipartItem> h = hidden;
        if (h == null) {
            h = new HashSet<>();
            for (List<ClipartItem> l : groups.values()) h.addAll(l);
            hidden = h;
        }
        return h;
    }

    // ------------------------------------------------------------------ computing

    private record Score(double curves, int shapes, int segments, boolean ok) {}

    private static final int PREFIX = 64 * 1024;
    private static final int MAX_HAMMING = 3;
    /** Allowed difference between two 32x32 "ink" thumbnails, as a share of the ink in either (0 = identical). */
    static final double MAX_INK_DIFF = Double.parseDouble(System.getProperty("gallery.dupInk", "0.35"));
    /** Allowed mean colour difference (per channel) between the 4x4 colour thumbnails. */
    static final double MAX_COLOR_DIFF = Double.parseDouble(System.getProperty("gallery.dupColor", "20"));
    /** Strongly differing share of the drawn pixels allowed between same-named copies / differently named ones. */
    static final double MAX_SAME_NAME = Double.parseDouble(System.getProperty("gallery.dupSame", "0.06"));
    /** Mean colour difference (per channel) over the drawn pixels. */
    static final double MAX_COLOR_INK = Double.parseDouble(System.getProperty("gallery.dupColorInk", "24"));
    static final double MAX_COLOR_SAME = Double.parseDouble(System.getProperty("gallery.dupColorSame", "60"));
    static final double MAX_OTHER_NAME = Double.parseDouble(System.getProperty("gallery.dupOther", "0.005"));
    /** Pixels allowed to differ at all between differently named copies. */
    static final double MAX_OTHER_PIXELS = Double.parseDouble(System.getProperty("gallery.dupPixels", "12"));
    static volatile java.util.function.Consumer<Object[]> PAIR_DEBUG;

    private static final class LongList {
        long[] a = new long[1024];
        int n;
        void add(long v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        long[] toArray() { return Arrays.copyOf(a, n); }
    }

    /** Full-size preview luminance (0 = black, 255 = white). */
    record Lum(int w, int h, byte[] v, byte[] min, byte[] rgb) {}

    private static Lum lumOf(Catalog cat, ClipartItem it) {
        try {
            byte[] d = cat.readPrefix(it, PREFIX);
            int[] r = findDisp(d);
            if (r != null && r[0] < 0) { d = cat.read(it); r = findDisp(d); }
            return r == null || r[0] < 0 ? null : lumGrid(d, r[0], r[1]);
        } catch (IOException e) {
            return null;
        }
    }

    static Lum lumGrid(byte[] d, int off, int len) {
        if (len < 48) return null;
        int o = off + 4;
        int hdr = le32(d, o), w = le32(d, o + 4), h = le32(d, o + 8), bpp = (d[o + 14] & 0xff) | (d[o + 15] & 0xff) << 8;
        int comp = le32(d, o + 16), used = le32(d, o + 32);
        boolean up = h > 0;
        h = Math.abs(h);
        if (hdr < 40 || w <= 0 || h <= 0 || w > 1024 || h > 1024 || comp != 0 || (bpp != 8 && bpp != 24)) return null;
        int ncol = bpp == 8 ? (used == 0 ? 256 : used) : 0;
        int pal = o + hdr, px = pal + ncol * 4, stride = (w * bpp / 8 + 3) / 4 * 4;
        if (px + (long) stride * h > off + len) return null;
        byte[] v = new byte[w * h], mn = new byte[w * h], rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            int row = px + (up ? h - 1 - y : y) * stride;
            for (int x = 0; x < w; x++) {
                int r, g, b;
                if (bpp == 8) { int p = pal + (d[row + x] & 0xff) * 4; b = d[p] & 0xff; g = d[p + 1] & 0xff; r = d[p + 2] & 0xff; }
                else { b = d[row + x * 3] & 0xff; g = d[row + x * 3 + 1] & 0xff; r = d[row + x * 3 + 2] & 0xff; }
                v[y * w + x] = (byte) (int) (0.299 * r + 0.587 * g + 0.114 * b);
                rgb[(y * w + x) * 3] = (byte) r; rgb[(y * w + x) * 3 + 1] = (byte) g; rgb[(y * w + x) * 3 + 2] = (byte) b;
                mn[y * w + x] = (byte) Math.min(r, Math.min(g, b));   // separates yellow ink from white paper, which luminance can't
            }
        }
        return new Lum(w, h, v, mn, rgb);
    }

    /**
     * {share of the drawn pixels (not paper-white in either) that differ strongly, number of pixels that differ at all}.
     * A pixel "differs" when, in brightness or in the darkest colour channel, one preview lies outside the range seen in
     * the other's 3x3 neighbourhood by more than 96 (strongly) or 32 (at all). Anti-aliasing and 1-pixel shifts pass;
     * a different glyph, clock hand or bar doesn't.
     */
    static double[] compare(Lum a, Lum b) {
        int w = a.w, h = a.h, strong = 0, any = 0, ink = 0;
        long colorSum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if ((a.min[i] & 0xff) < 239 || (b.min[i] & 0xff) < 239) {
                    ink++;
                    for (int k = 0; k < 3; k++) colorSum += Math.abs((a.rgb[i * 3 + k] & 0xff) - (b.rgb[i * 3 + k] & 0xff));
                }
                int d = Math.max(outside(a.v, b.v, w, h, x, y), outside(a.min, b.min, w, h, x, y));
                if (d > 96) strong++;
                if (d > 32) any++;
            }
        }
        return new double[]{ink == 0 ? 1 : (double) strong / ink, any, ink == 0 ? 0 : colorSum / (3.0 * ink)};
    }

    /** Names alike once the extension is dropped (the same clipart filed in two places). */
    private static String baseName(ClipartItem it) {
        String p = it.path();
        String n = p.substring(p.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n).toLowerCase(Locale.ROOT);
    }

    /** Same name: allow the geometry to have been redrawn. Different names: the previews must be near-identical. */
    static boolean accept(boolean sameName, double[] c) {
        // differently named files must also share their colours; same-named copies often come with another palette
        if (c[2] > (sameName ? MAX_COLOR_SAME : MAX_COLOR_INK)) return false;
        return sameName ? c[0] <= MAX_SAME_NAME : c[0] <= MAX_OTHER_NAME && c[1] <= MAX_OTHER_PIXELS;
    }

    /** How far a's pixel (x,y), or b's, lies outside the other image's 3x3 neighbourhood range (0 = inside). */
    private static int outside(byte[] a, byte[] b, int w, int h, int x, int y) {
        int va = a[y * w + x] & 0xff, vb = b[y * w + x] & 0xff;
        int loA = 255, hiA = 0, loB = 255, hiB = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = Math.max(0, Math.min(h - 1, y + dy));
            for (int dx = -1; dx <= 1; dx++) {
                int xx = Math.max(0, Math.min(w - 1, x + dx));
                int pa = a[yy * w + xx] & 0xff, pb = b[yy * w + xx] & 0xff;
                if (pa < loA) loA = pa;
                if (pa > hiA) hiA = pa;
                if (pb < loB) loB = pb;
                if (pb > hiB) hiB = pb;
            }
        }
        return Math.max(0, Math.max(Math.max(loB - va, va - hiB), Math.max(loA - vb, vb - hiA)));
    }

    static final class Preview {
        final byte[] ink;     // 32x32, 255 - luminance (so blank paper is 0)
        final byte[] rgb;     // 4x4 RGB
        final long hash;
        Preview(byte[] ink, byte[] rgb, long h) { this.ink = ink; this.rgb = rgb; hash = h; }
    }

    static Duplicates compute(Catalog cat, List<ClipartItem> ccx, String signature, Progress progress) {
        // phase 1: reduce every file's preview, one task per source so each disc is read in on-disc order
        Map<String, List<ClipartItem>> bySource = new LinkedHashMap<>();
        for (ClipartItem it : ccx) bySource.computeIfAbsent(it.sourceId(), k -> new ArrayList<>()).add(it);
        List<ClipartItem> items = new ArrayList<>();
        List<Preview> previews = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();
        int total = ccx.size();
        progress.update("Reading previews", 0, total);
        bySource.values().parallelStream().forEach(list -> {
            list.sort(Comparator.comparingLong(cat::orderOf));
            for (ClipartItem it : list) {
                try {
                    Preview pv = readPreview(cat, it);
                    if (pv != null) synchronized (items) { items.add(it); previews.add(pv); }
                } catch (IOException | RuntimeException ignored) {
                }
                int n = done.incrementAndGet();
                if (n % 256 == 0 || n == total) progress.update("Reading previews", n, total);
            }
        });

        // deterministic order, whatever order the reader threads finished in
        Integer[] perm = new Integer[items.size()];
        for (int i = 0; i < perm.length; i++) perm[i] = i;
        Arrays.sort(perm, Comparator.<Integer, String>comparing(i -> items.get(i).sourceId()).thenComparing(i -> items.get(i).path()));
        List<ClipartItem> items2 = new ArrayList<>();
        List<Preview> previews2 = new ArrayList<>();
        for (int i : perm) { items2.add(items.get(i)); previews2.add(previews.get(i)); }
        items.clear(); items.addAll(items2); previews.clear(); previews.addAll(previews2);

        // phase 2: candidate pairs share one of four interleaved 16-bit slices of the 64-bit hash and look alike
        // at thumbnail size (cheap, in memory)
        int n = items.size();
        long[] hash = new long[n];
        for (int i = 0; i < n; i++) hash[i] = previews.get(i).hash;
        progress.update("Matching previews", 0, TABLES);
        List<long[]> perBand = new ArrayList<>(Collections.nCopies(TABLES, null));
        AtomicInteger bandsDone = new AtomicInteger();
        java.util.stream.IntStream.range(0, TABLES).parallel().forEach(band -> {
            long[] keyed = new long[n];
            for (int i = 0; i < n; i++) keyed[i] = (long) slice(hash[i], band) << 32 | i;
            Arrays.sort(keyed);
            LongList out = new LongList();
            int start = 0;
            while (start < n) {
                int key = (int) (keyed[start] >>> 32), end = start + 1;
                while (end < n && (int) (keyed[end] >>> 32) == key) end++;
                int m = end - start;
                int[] ids = new int[m];
                for (int x = 0; x < m; x++) ids[x] = (int) keyed[start + x];
                if (m > 20000) {        // huge bucket: only neighbours in hash order
                    Integer[] o = new Integer[m];
                    for (int x = 0; x < m; x++) o[x] = ids[x];
                    Arrays.sort(o, Comparator.comparingLong(i -> hash[i]));
                    for (int x = 0; x < m; x++) ids[x] = o[x];
                }
                for (int x = 0; x < m; x++) {
                    int lim = m > 20000 ? Math.min(m, x + 65) : m;
                    int i = ids[x];
                    for (int y = x + 1; y < lim; y++) {
                        int j = ids[y];
                        if (Long.bitCount(hash[i] ^ hash[j]) <= MAX_HAMMING && similar(previews.get(i), previews.get(j)))
                            out.add((long) Math.min(i, j) << 32 | Math.max(i, j));
                    }
                }
                start = end;
            }
            synchronized (perBand) { perBand.set(band, out.toArray()); }
            progress.update("Matching previews", bandsDone.incrementAndGet(), TABLES);
        });
        LongList all = new LongList();
        for (long[] l : perBand) for (long v : l) all.add(v);
        // files with the same name in different folders are compared directly, whatever their hashes say: this is where
        // nearly all real duplicates are, and it also finds copies whose previews were rendered a little differently
        Map<String, List<Integer>> byName = new HashMap<>();
        for (int i = 0; i < n; i++) byName.computeIfAbsent(baseName(items.get(i)), k -> new ArrayList<>()).add(i);
        for (List<Integer> g : byName.values()) {
            if (g.size() < 2 || g.size() > 60) continue;
            for (int x = 0; x < g.size(); x++)
                for (int y = x + 1; y < g.size(); y++) {
                    int i = g.get(x), j = g.get(y);
                    ClipartItem p = items.get(i), q = items.get(j);
                    if (p.sourceId().equals(q.sourceId()) && p.folder().equalsIgnoreCase(q.folder())) continue;
                    if (similar(previews.get(i), previews.get(j))) all.add((long) i << 32 | j);
                }
        }
        long[] cand = all.toArray();
        Arrays.sort(cand);
        int nc = 0;
        for (int i = 0; i < cand.length; i++) if (i == 0 || cand[i] != cand[i - 1]) cand[nc++] = cand[i];
        cand = Arrays.copyOf(cand, nc);
        previews.clear();

        // phase 3: confirm candidates on the full 96x96 previews (read again from the disc), tolerating 1-pixel shifts
        final long[] pairs = cand;
        AtomicInteger done3 = new AtomicInteger();
        progress.update("Comparing previews", 0, pairs.length);
        int blocks = (pairs.length + 255) / 256;
        boolean[] ok = new boolean[pairs.length];
        java.util.stream.IntStream.range(0, blocks).parallel().forEach(blk -> {
            Map<Integer, Lum> cache = new HashMap<>();
            int from = blk * 256, to = Math.min(pairs.length, from + 256);
            for (int p = from; p < to; p++) {
                int i = (int) (pairs[p] >>> 32), j = (int) pairs[p];
                try {
                    Lum a = cache.computeIfAbsent(i, k -> lumOf(cat, items.get(k)));
                    Lum b = cache.computeIfAbsent(j, k -> lumOf(cat, items.get(k)));
                    if (a != null && b != null && a.w == b.w && a.h == b.h) {
                        double[] t = compare(a, b);
                        boolean sameName = baseName(items.get(i)).equals(baseName(items.get(j)));
                        // differently named files in one folder are siblings of a set (letters, calendar days, symbols)
                        // that merely look alike at preview size
                        ok[p] = accept(sameName, t) && (sameName || !items.get(i).folder().equalsIgnoreCase(items.get(j).folder())
                                || !items.get(i).sourceId().equals(items.get(j).sourceId()));
                        if (PAIR_DEBUG != null) PAIR_DEBUG.accept(new Object[]{items.get(i), items.get(j), t, ok[p]});
                    }
                } catch (RuntimeException ignored) {
                }
                int k = done3.incrementAndGet();
                if (k % 64 == 0 || k == pairs.length) progress.update("Comparing previews", k, pairs.length);
            }
        });
        int[] uf = new int[n];
        for (int i = 0; i < n; i++) uf[i] = i;
        Set<Long> edges = new HashSet<>();
        for (int p = 0; p < pairs.length; p++) if (ok[p]) { union(uf, (int) (pairs[p] >>> 32), (int) pairs[p]); edges.add(pairs[p]); }
        Map<Integer, List<Integer>> byRoot = new HashMap<>();
        for (int i = 0; i < n; i++) byRoot.computeIfAbsent(find(uf, i), k -> new ArrayList<>()).add(i);

        // phase 4: in each cluster of look-alikes keep the copy with the best-looking geometry, and hide only the
        // members that were matched with it directly (so a chain A~B~C never hides C on A's account)
        List<List<Integer>> dup = new ArrayList<>();
        int members = 0;
        for (List<Integer> g : byRoot.values()) if (g.size() > 1) { dup.add(g); members += g.size(); }
        AtomicInteger done4 = new AtomicInteger();
        int total4 = members;
        progress.update("Comparing duplicate copies", 0, total4);
        Map<ClipartItem, List<ClipartItem>> result = new ConcurrentHashMap<>();
        dup.parallelStream().forEach(g -> {
            Map<Integer, Score> sc = new HashMap<>();
            for (int i : g) {
                sc.put(i, score(cat, items.get(i)));
                int k = done4.incrementAndGet();
                if (k % 16 == 0 || k == total4) progress.update("Comparing duplicate copies", k, total4);
            }
            List<Integer> order = new ArrayList<>(g);
            order.sort(Comparator.<Integer>comparingInt(i -> sc.get(i).ok() ? 0 : 1)
                    .thenComparing(i -> -sc.get(i).curves())
                    .thenComparingInt(i -> -sc.get(i).shapes())
                    .thenComparingInt(i -> sc.get(i).segments())
                    .thenComparing(i -> items.get(i).sourceId())
                    .thenComparing(i -> items.get(i).path()));
            // greedy: the best copy takes everything it matched directly; leftovers pick the best of what remains
            Set<Integer> left = new LinkedHashSet<>(order);
            while (left.size() > 1) {
                int keep = left.iterator().next();
                List<ClipartItem> hide = new ArrayList<>();
                List<Integer> gone = new ArrayList<>();
                for (int i : left) {
                    if (i == keep) continue;
                    if (edges.contains((long) Math.min(i, keep) << 32 | Math.max(i, keep))) { hide.add(items.get(i)); gone.add(i); }
                }
                left.remove(keep);
                left.removeAll(gone);
                if (!hide.isEmpty()) result.put(items.get(keep), hide);
            }
        });
        return new Duplicates(signature, new HashMap<>(result));
    }

    /** Curve share of the path segments (higher = smoother, hand-built geometry), shape count, segment count. */
    private static Score score(Catalog cat, ClipartItem it) {
        try {
            VectorDrawing d = CcxDecoder.decode(cat.read(it));
            int seg = 0, cubic = 0;
            double[] c = new double[6];
            for (VectorDrawing.Item i : d.items()) {
                for (PathIterator p = i.path().getPathIterator(null); !p.isDone(); p.next()) {
                    int t = p.currentSegment(c);
                    if (t == PathIterator.SEG_CUBICTO || t == PathIterator.SEG_QUADTO) cubic++;
                    if (t != PathIterator.SEG_MOVETO && t != PathIterator.SEG_CLOSE) seg++;
                }
            }
            return new Score(seg == 0 ? 0 : (double) cubic / seg, d.items().size(), seg, true);
        } catch (IOException | RuntimeException e) {
            return new Score(0, 0, 0, false);
        }
    }

    private static final int TABLES = 4;

    /** Interleaved 16-bit slice of the hash: two hashes differing in at most 3 bits agree on at least one of the 4 slices. */
    private static int slice(long h, int band) {
        int k = 0;
        for (int b = 0; b < 16; b++) if ((h >>> (b * 4 + band) & 1) != 0) k |= 1 << b;
        return k;
    }

    private static int find(int[] uf, int x) {
        while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x]; }
        return x;
    }

    private static void union(int[] uf, int a, int b) {
        a = find(uf, a); b = find(uf, b);
        if (a != b) uf[Math.max(a, b)] = Math.min(a, b);
    }

    static boolean similar(Preview a, Preview b) {
        double[] d = diff(a, b);
        return d[0] <= MAX_INK_DIFF && d[1] <= MAX_COLOR_DIFF;
    }

    /** {ink difference as a share of the ink, mean colour difference per channel}. */
    static double[] diff(Preview a, Preview b) {
        long sd = 0, sm = 0;
        for (int i = 0; i < 1024; i++) {
            int x = a.ink[i] & 0xff, y = b.ink[i] & 0xff;
            sd += Math.abs(x - y);
            sm += Math.max(x, y);
        }
        long cd = 0;
        for (int i = 0; i < 48; i++) cd += Math.abs((a.rgb[i] & 0xff) - (b.rgb[i] & 0xff));
        return new double[]{sm == 0 ? 0 : (double) sd / sm, cd / 48.0};
    }

    private static Preview readPreview(Catalog cat, ClipartItem it) throws IOException {
        byte[] d = cat.readPrefix(it, PREFIX);
        int[] r = findDisp(d);
        if (r != null && r[0] < 0) {          // preview runs past the prefix: read the whole file
            d = cat.read(it);
            r = findDisp(d);
        }
        if (r == null || r[0] < 0) return null;
        return thumbnail(d, r[0], r[1]);
    }

    /** Decodes the DISP body (4 bytes, then a BITMAPINFOHEADER, palette, pixels) into a 16x16 RGB thumbnail and hash. */
    static Preview thumbnail(byte[] d, int off, int len) {
        if (len < 48) return null;
        int o = off + 4;
        int hdr = le32(d, o), w = le32(d, o + 4), h = le32(d, o + 8), bpp = (d[o + 14] & 0xff) | (d[o + 15] & 0xff) << 8;
        int comp = le32(d, o + 16), used = le32(d, o + 32);
        boolean up = h > 0;
        h = Math.abs(h);
        if (hdr < 40 || w <= 0 || h <= 0 || w > 1024 || h > 1024 || comp != 0 || (bpp != 8 && bpp != 24)) return null;
        int ncol = bpp == 8 ? (used == 0 ? 256 : used) : 0;
        int pal = o + hdr, px = pal + ncol * 4, stride = (w * bpp / 8 + 3) / 4 * 4;
        if (px + (long) stride * h > off + len) return null;
        long[] ink = new long[32 * 32];
        int[] cnt = new int[32 * 32];
        long[] col = new long[4 * 4 * 3];
        int[] ccnt = new int[16];
        for (int y = 0; y < h; y++) {
            int row = px + (up ? h - 1 - y : y) * stride, cy = y * 32 / h, qy = y * 4 / h;
            for (int x = 0; x < w; x++) {
                int r, g, b;
                if (bpp == 8) {
                    int p = pal + (d[row + x] & 0xff) * 4;
                    b = d[p] & 0xff; g = d[p + 1] & 0xff; r = d[p + 2] & 0xff;
                } else {
                    b = d[row + x * 3] & 0xff; g = d[row + x * 3 + 1] & 0xff; r = d[row + x * 3 + 2] & 0xff;
                }
                int c = cy * 32 + x * 32 / w;
                ink[c] += 255 - (int) (0.299 * r + 0.587 * g + 0.114 * b);
                cnt[c]++;
                int q = qy * 4 + x * 4 / w;
                col[q * 3] += r; col[q * 3 + 1] += g; col[q * 3 + 2] += b;
                ccnt[q]++;
            }
        }
        byte[] t = new byte[1024];
        for (int c = 0; c < 1024; c++) { if (cnt[c] == 0) return null; t[c] = (byte) (ink[c] / cnt[c]); }
        byte[] rgb = new byte[48];
        for (int q = 0; q < 16; q++) { if (ccnt[q] == 0) return null; for (int k = 0; k < 3; k++) rgb[q * 3 + k] = (byte) (col[q * 3 + k] / ccnt[q]); }
        double[] lum = new double[64];
        for (int c = 0; c < 1024; c++) lum[(c / 32 / 4) * 8 + (c % 32) / 4] += (t[c] & 0xff) / 16.0;
        double mean = 0, lo = 255, hi = 0;
        for (double v : lum) { mean += v / 64; lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (hi - lo < 4) return null;     // blank/flat preview says nothing about the drawing
        long hash = 0;
        for (int i = 0; i < 64; i++) if (lum[i] > mean) hash |= 1L << i;
        return new Preview(t, rgb, hash);
    }

    private static int le32(byte[] d, int o) {
        return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0xff) << 24;
    }

    /** {offset, length} of the DISP chunk body; {-1,0} when it is cut off by the end of {@code d}; null when absent. */
    private static int[] findDisp(byte[] d) {
        if (d.length < 20 || d[0] != 'R' || d[1] != 'I' || d[2] != 'F' || d[3] != 'F') return null;
        int pos = 12;
        while (pos + 8 <= d.length) {
            int sz = (d[pos + 4] & 0xff) | (d[pos + 5] & 0xff) << 8 | (d[pos + 6] & 0xff) << 16 | (d[pos + 7] & 0xff) << 24;
            if (sz < 0) return null;
            boolean disp = d[pos] == 'D' && d[pos + 1] == 'I' && d[pos + 2] == 'S' && d[pos + 3] == 'P';
            if (disp) return pos + 8 + (long) sz <= d.length ? new int[]{pos + 8, sz} : new int[]{-1, 0};
            if (d[pos] == 'p' && d[pos + 1] == 'a' && d[pos + 2] == 'c' && d[pos + 3] == 'k') return null;
            pos += 8 + sz + (sz & 1);
        }
        return new int[]{-1, 0};   // ran off the prefix before finding it
    }
}
