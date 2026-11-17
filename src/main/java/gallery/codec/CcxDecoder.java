package gallery.codec;

import gallery.drawing.VectorDrawing;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decoder for Corel Gallery .CCX files: a RIFF "CDRX" container holding a zlib-compressed 16-bit
 * CMX ("Corel Binary Meta File") page stream plus resource tables, and a 96x96 8-bit preview bitmap.
 * Layouts were worked out from the discs themselves (validated against ~900 files: every record
 * length computed from these layouts matches the declared length).
 *
 * <pre>
 * RIFF 'CDRX'
 *   cont  header (file id, bounding box, instruction count)
 *   ccmm  index offsets
 *   DISP  preview: u32 tag, BITMAPINFOHEADER, 256 x BGRA palette, 8-bit rows bottom-up
 *   pack  u32 uncompressedSize, 'CPng', u32 flags, zlib stream  ->  page chunk + resource tables
 * page chunk: 'page' u32 size, then instructions
 *   instruction: u16 size, s16 code, payload  (size 0xFFFF => u32 size follows, then s16 code)
 *     -9 BeginPage (bbox)   -67 PolyCurve   (-11/-12 layer, -13/-14 group, -10 EndPage: ignored)
 *   PolyCurve payload: u8 mask (1=fill, 2=outline)
 *     fill:    u16 type; 1 uniform = u16 colour, u16 screen; 2 fountain = 18 header bytes, u16 n, n x (u16 colour, u16 pos%)
 *     outline: u16 outline index
 *     u16 n, n x (s16 x, s16 y), n x u8 node type, 4 x s16 bbox   (record padded to even length)
 *     node type: bits 0xC0 = 00 move, 40 line, 80 cubic start (+ two 0xC0 points), 08 = closed subpath
 * resource tables after the page chunk: rclr (colours), rpen (pens), rotl (outlines), ...
 * </pre>
 */
public final class CcxDecoder {
    private CcxDecoder() {}

    private static int u16(byte[] d, int o) { return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8; }
    private static int s16(byte[] d, int o) { return (short) u16(d, o); }
    private static long u32(byte[] d, int o) { return u16(d, o) | (long) u16(d, o + 2) << 16; }
    private static int s32(byte[] d, int o) { return (int) u32(d, o); }

    private record Chunk(String tag, int dataOff, int len) {}

    private static List<Chunk> chunks(byte[] d, int pos, int end) {
        List<Chunk> out = new ArrayList<>();
        while (pos + 8 <= end) {
            String tag = tag(d, pos);
            long len = u32(d, pos + 4);
            if (len < 0 || pos + 8 + len > end) { out.add(new Chunk(tag, pos + 8, (int) Math.max(0, end - pos - 8))); break; }
            out.add(new Chunk(tag, pos + 8, (int) len));
            pos += 8 + (int) len + ((int) len & 1);
        }
        return out;
    }

    private static final String[] TAGS = {"cont", "ccmm", "DISP", "pack", "page", "rclr", "rpen", "rotl", "rdot", "rarr", "rscr", "rott", "indx", "LIST"};

    /** A chunk's four-character tag (the common ones without allocating). */
    private static String tag(byte[] d, int p) {
        for (String t : TAGS)
            if (d[p] == t.charAt(0) && d[p + 1] == t.charAt(1) && d[p + 2] == t.charAt(2) && d[p + 3] == t.charAt(3)) return t;
        return new String(d, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static boolean isRiff(byte[] d, int len) {
        return len > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F';
    }

    private static Map<String, Chunk> topLevel(byte[] d, int len) throws IOException {
        if (!isRiff(d, len)) throw new IOException("Not a RIFF file");
        Map<String, Chunk> m = new LinkedHashMap<>();
        for (Chunk c : chunks(d, 12, len)) m.putIfAbsent(c.tag(), c);
        return m;
    }

    /**
     * Working buffers for decoding: a reusable Inflater and page buffer.  Keep one per thread to decode many files
     * without garbage; {@link #close()} it when done.
     */
    public static final class Scratch implements AutoCloseable {
        private final Inflater inflater = new Inflater();
        private byte[] page;
        private final CompactDrawing.Builder builder = new CompactDrawing.Builder();

        /** {@code reusable}: keep a buffer that grows to fit (for many files); otherwise size it to each file. */
        public Scratch(boolean reusable) { page = new byte[reusable ? 1 << 16 : 0]; }

        private void ensure(int n) { if (page.length < n) page = Arrays.copyOf(page, Math.max(n, page.length + (page.length >> 1))); }

        @Override public void close() { inflater.end(); }
    }

    /** Receives the decoded shapes in painter's order.  Raw CMX coordinates: y points up. */
    interface Sink {
        /** A shape with up to {@code nodes} nodes starts. */
        void begin(VectorDrawing.Fill fill, VectorDrawing.Pen pen, int nodes);
        void moveTo(int x, int y);
        void lineTo(int x, int y);
        void curveTo(int x1, int y1, int x2, int y2, int x3, int y3);
        void closePath();
        void end();
        /** Union of the shapes' bounds (y down), or null if there are none. */
        Rectangle2D contentBounds();
    }

    /** Builds a VectorDrawing: one Path2D per shape. */
    private static final class PathSink implements Sink {
        final List<VectorDrawing.Item> items = new ArrayList<>();
        private Path2D.Double path;
        private VectorDrawing.Fill fill;
        private VectorDrawing.Pen pen;

        @Override public void begin(VectorDrawing.Fill f, VectorDrawing.Pen p, int nodes) {
            fill = f;
            pen = p;
            path = new Path2D.Double(Path2D.WIND_EVEN_ODD, nodes + 8);   // sized up front: no regrowing
        }
        @Override public void moveTo(int x, int y) { path.moveTo(x, -y); }
        @Override public void lineTo(int x, int y) { path.lineTo(x, -y); }
        @Override public void curveTo(int x1, int y1, int x2, int y2, int x3, int y3) { path.curveTo(x1, -y1, x2, -y2, x3, -y3); }
        @Override public void closePath() { path.closePath(); }
        @Override public void end() { items.add(new VectorDrawing.Item(path, fill, pen)); }
        @Override public Rectangle2D contentBounds() {
            Rectangle2D u = null;
            for (VectorDrawing.Item it : items) {
                Rectangle2D b = it.path().getBounds2D();
                u = u == null ? b : u.createUnion(b);
            }
            return u;
        }
    }

    /** Decompresses the page chunk (with the resource tables following it) into {@code s.page}; returns its length. */
    private static int pageBlock(byte[] d, int len, Map<String, Chunk> top, Scratch s) throws IOException {
        Chunk pack = top.get("pack");
        if (pack != null) {
            if (pack.len() < 13) throw new IOException("Truncated pack chunk");
            Inflater inf = s.inflater;
            inf.reset();
            inf.setInput(d, pack.dataOff() + 12, pack.len() - 12);
            long expect = u32(d, pack.dataOff());
            s.ensure((int) Math.min(Math.max(expect, 1024), 1 << 26));
            int n = 0;
            try {
                while (!inf.finished()) {
                    if (n == s.page.length) {
                        if (n > (1 << 27)) throw new IOException("Page data too large");
                        s.ensure(n * 2);
                    }
                    int k = inf.inflate(s.page, n, s.page.length - n);
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    n += k;
                }
            } catch (DataFormatException e) {
                throw new IOException("Corrupt compressed page data", e);
            }
            return n;
        }
        Chunk page = top.get("page");
        if (page != null) {
            int from = page.dataOff() - 8, n = len - from;
            s.ensure(n);
            System.arraycopy(d, from, s.page, 0, n);
            return n;
        }
        throw new IOException("No page data in file");
    }

    // ------------------------------------------------------------------ resource tables

    private static int cmyk(int c, int m, int y, int k, int max) {
        double cc = Math.min(1, (double) c / max), mm = Math.min(1, (double) m / max);
        double yy = Math.min(1, (double) y / max), kk = Math.min(1, (double) k / max);
        // Corel's own conversion adds the black instead of multiplying: R = 255 * (1 - C - K), verified against its exports
        int r = (int) Math.round(255 * Math.max(0, 1 - cc - kk));
        int g = (int) Math.round(255 * Math.max(0, 1 - mm - kk));
        int b = (int) Math.round(255 * Math.max(0, 1 - yy - kk));
        return r << 16 | g << 8 | b;
    }

    private static int[] colours(byte[] t, Chunk tc) throws IOException {
        if (tc == null || tc.len() < 2) return new int[0];
        int end = tc.dataOff() + tc.len();
        int n = u16(t, tc.dataOff()), q = tc.dataOff() + 2;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            if (q + 2 > end) throw new IOException("Truncated colour table");
            int model = t[q] & 0xff;
            q += 2;                                     // model, palette
            if (model == 5) {                            // RGB
                if (q + 3 > end) throw new IOException("Truncated colour table");
                out[i] = (t[q] & 0xff) << 16 | (t[q + 1] & 0xff) << 8 | (t[q + 2] & 0xff);
                q += 3;
            } else {                                     // 1,2,3: four-component CMYK (percent, or 0..255)
                if (q + 4 > end) throw new IOException("Truncated colour table");
                int c = t[q] & 0xff, m = t[q + 1] & 0xff, y = t[q + 2] & 0xff, k = t[q + 3] & 0xff;
                int max = Math.max(Math.max(c, m), Math.max(y, k)) <= 100 ? 100 : 255;
                out[i] = cmyk(c, m, y, k, max);
                q += 4;
            }
        }
        return out;
    }

    private static double[] penWidths(byte[] t, Chunk tc, boolean[] behind) throws IOException {
        if (tc == null || tc.len() < 2) return new double[0];
        int end = tc.dataOff() + tc.len();
        int n = u16(t, tc.dataOff()), q = tc.dataOff() + 2;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            if (q + 10 > end) throw new IOException("Truncated pen table");
            out[i] = u16(t, q);
            int flags = u16(t, q + 8);
            q += 10;
            if (i < behind.length) behind[i] = (flags & 2) != 0;
            if ((flags & 2) != 0) {                      // 2x3 transform (6 doubles): the nib is scaled by it
                if (q + 48 > end) throw new IOException("Truncated pen table");
                double a = dbl(t, q), b = dbl(t, q + 8), c = dbl(t, q + 16), dd = dbl(t, q + 24);
                double sc = Math.sqrt(Math.abs(a * dd - b * c));
                if (sc > 0 && sc < 1e6) out[i] *= sc;
                q += 48;
            }
        }
        return out;
    }

    private static int u16Or0(byte[] t, Chunk c) { return c == null || c.len() < 2 ? 0 : u16(t, c.dataOff()); }

    private static double dbl(byte[] d, int o) {
        return Double.longBitsToDouble(u32(d, o) | u32(d, o + 4) << 32);
    }

    /** Outline i -> {colour index, pen index}, both 1-based. */
    private static int[][] outlines(byte[] t, Chunk c) {
        if (c == null || c.len() < 2) return new int[0][];
        int o = c.dataOff();
        int n = Math.min(u16(t, o), (c.len() - 2) / 12);
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) out[i] = new int[]{u16(t, o + 2 + 12 * i + 4), u16(t, o + 2 + 12 * i + 8)};
        return out;
    }

    private static int colour(int[] cols, int idx1) {
        return idx1 >= 1 && idx1 <= cols.length ? cols[idx1 - 1] : 0;
    }

    // ------------------------------------------------------------------ decode

    public static VectorDrawing decode(byte[] d) throws IOException {
        try (Scratch s = new Scratch(false)) {
            PathSink sink = new PathSink();
            Rectangle2D bounds = decode(d, d.length, s, sink);
            return new VectorDrawing(bounds, sink.items);
        }
    }

    /**
     * The compact form used for thumbnails, from the first {@code len} bytes of {@code d}.  Uses (and reuses)
     * {@code s}'s buffers, so decoding many files this way makes little garbage.
     */
    public static CompactDrawing decodeCompact(byte[] d, int len, Scratch s) throws IOException {
        s.builder.reset();
        Rectangle2D bounds = decode(d, len, s, s.builder);
        return s.builder.build(bounds);
    }

    private static Rectangle2D decode(byte[] d, int len, Scratch s, Sink sink) throws IOException {
        Map<String, Chunk> top = topLevel(d, len);
        int plen = pageBlock(d, len, top, s);
        byte[] page = s.page;
        if (plen < 8 || page[0] != 'p' || page[1] != 'a' || page[2] != 'g' || page[3] != 'e')
            throw new IOException("Unexpected page data");
        int psz = (int) Math.min(plen, u32(page, 4) + 8);

        Map<String, Chunk> tab = new HashMap<>();
        for (Chunk c : chunks(page, psz, plen)) tab.putIfAbsent(c.tag(), c);
        int[] cols = colours(page, tab.get("rclr"));
        boolean[] behind = new boolean[u16Or0(page, tab.get("rpen"))];
        double[] pens = penWidths(page, tab.get("rpen"), behind);
        int[][] outl = outlines(page, tab.get("rotl"));
        // one Pen per outline, made once per file rather than once per shape
        VectorDrawing.Pen[] outlinePens = new VectorDrawing.Pen[outl.length];
        for (int i = 0; i < outl.length; i++) {
            int[] o = outl[i];
            double w = o[1] >= 1 && o[1] <= pens.length ? pens[o[1] - 1] : 0;
            outlinePens[i] = new VectorDrawing.Pen(colour(cols, o[0]), w, o[1] >= 1 && o[1] <= behind.length && behind[o[1] - 1]);
        }

        Rectangle2D[] pageBox = new Rectangle2D[2];   // [1] set when a proc chunk was expanded
        records(page, 8, psz, cols, outlinePens, sink, pageBox, 0);
        Rectangle2D box = pageBox[0];
        if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0 || pageBox[1] != null) {
            Rectangle2D u = sink.contentBounds();
            if (u != null) box = u;
            else if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0)
                throw new IOException("Drawing is empty");
        }
        return box;
    }

    /** Walks a record stream; nested 'proc' chunks (drawing procedures/groups) are expanded in place. */
    private static void records(byte[] page, int p, int psz, int[] cols, VectorDrawing.Pen[] outlinePens,
                                Sink sink, Rectangle2D[] pageBox, int depth) {
        while (p + 4 <= psz) {
            if (page[p] == 'p' && page[p + 1] == 'r' && page[p + 2] == 'o' && page[p + 3] == 'c' && p + 8 <= psz) {
                long len = u32(page, p + 4);
                int end = (int) Math.min(psz, p + 8 + len);
                pageBox[1] = new Rectangle2D.Double();
                if (depth < 8) records(page, p + 8, end, cols, outlinePens, sink, pageBox, depth + 1);
                p = end + (int) (len & 1);
                continue;
            }
            int size = u16(page, p), hdr = 4, code = s16(page, p + 2);
            if (size == 0xFFFF) {
                if (p + 8 > psz) break;
                size = (int) u32(page, p + 2);
                code = s16(page, p + 6);
                hdr = 8;
            }
            if (size < hdr || p + size > psz + 1) break;
            int end = Math.min(p + size, psz);
            if (code == -9 && p + hdr + 14 <= end) {
                int x1 = s16(page, p + hdr + 6), y1 = s16(page, p + hdr + 8);
                int x2 = s16(page, p + hdr + 10), y2 = s16(page, p + hdr + 12);
                if (pageBox[0] == null) pageBox[0] = box(x1, -y1, x2, -y2);
            } else if (code == -67) {
                polyCurve(page, p + hdr, end, cols, outlinePens, sink);
            }
            p += size;
        }
    }


    private static Rectangle2D box(double xa, double ya, double xb, double yb) {
        return new Rectangle2D.Double(Math.min(xa, xb), Math.min(ya, yb), Math.abs(xb - xa), Math.abs(yb - ya));
    }

    /** Sends one PolyCurve record to the sink, unless it is invisible or malformed. */
    private static void polyCurve(byte[] d, int q, int end, int[] cols, VectorDrawing.Pen[] outlinePens, Sink sink) {
        if (q >= end) return;
        int mask = d[q++] & 0xff;
        if ((mask & 0xFC) != 0) return;             // lens / canvas / container: not seen in the library
        VectorDrawing.Fill fill = VectorDrawing.Fill.NONE;
        VectorDrawing.Pen pen = null;
        if ((mask & 1) != 0) {
            if (q + 2 > end) return;
            int ft = u16(d, q);
            q += 2;
            switch (ft) {
                case 0 -> { }
                case 1 -> {
                    if (q + 4 > end) return;
                    fill = VectorDrawing.Fill.solid(colour(cols, u16(d, q)));
                    q += 4;
                }
                case 2 -> {
                    if (q + 20 > end) return;
                    long kind = u32(d, q + 2);
                    double angle = s32(d, q + 6) / 10.0;
                    q += 18;
                    int ns = u16(d, q);
                    q += 2;
                    if (q + 4 * ns > end) return;
                    List<VectorDrawing.Stop> stops = new ArrayList<>();
                    for (int i = 0; i < ns; i++)
                        stops.add(new VectorDrawing.Stop(u16(d, q + 4 * i + 2), colour(cols, u16(d, q + 4 * i))));
                    q += 4 * ns;
                    boolean radial = kind == 2 || kind == 4;     // 1 linear, 2 radial, 3 conical, 4 square
                    fill = stops.isEmpty() ? VectorDrawing.Fill.NONE
                            : new VectorDrawing.Fill(radial ? VectorDrawing.Fill.Kind.RADIAL : VectorDrawing.Fill.Kind.LINEAR,
                            stops.get(0).rgb(), angle, stops);
                }
                default -> { return; }                       // unknown fill type: skip the shape
            }
        }
        if ((mask & 2) != 0) {
            if (q + 2 > end) return;
            int oi = u16(d, q);
            q += 2;
            if (oi >= 1 && oi <= outlinePens.length) pen = outlinePens[oi - 1];
        }
        if (fill.kind() == VectorDrawing.Fill.Kind.NONE && pen == null) return;   // invisible (page frames etc.)
        if (q + 2 > end) return;
        int n = u16(d, q);
        q += 2;
        if (n == 0 || q + 5 * n > end) return;
        int px = q, tp = q + 4 * n;
        sink.begin(fill, pen, n);
        boolean open = false;
        for (int i = 0; i < n; ) {
            int t = d[tp + i] & 0xff;
            int x = s16(d, px + 4 * i), y = s16(d, px + 4 * i + 2);
            int kind = t & 0xC0;
            if (kind == 0) {                                // move
                sink.moveTo(x, y);
                open = true;
                i++;
            } else if (!open) {
                i++;
            } else if (kind == 0x40) {                      // line
                sink.lineTo(x, y);
                if ((t & 8) != 0 && nextIsMoveOrEnd(d, tp, n, i)) sink.closePath();
                i++;
            } else if (kind == 0xC0 && i + 2 < n && (d[tp + i + 1] & 0xC0) == 0xC0 && (d[tp + i + 2] & 0xC0) == 0x80) {
                // cubic Bezier: two control points (0xC0) followed by the end node (0x80)
                sink.curveTo(x, y,
                        s16(d, px + 4 * (i + 1)), s16(d, px + 4 * (i + 1) + 2),
                        s16(d, px + 4 * (i + 2)), s16(d, px + 4 * (i + 2) + 2));
                if ((d[tp + i + 2] & 8) != 0 && nextIsMoveOrEnd(d, tp, n, i + 2)) sink.closePath();
                i += 3;
            } else if (kind == 0x80) {                      // stray end node: treat as a straight segment
                sink.lineTo(x, y);
                if ((t & 8) != 0 && nextIsMoveOrEnd(d, tp, n, i)) sink.closePath();
                i++;
            } else {
                i++;
            }
        }
        sink.end();
    }

    /** A closed subpath carries its 0x08 flag on its last segment; close only there. */
    private static boolean nextIsMoveOrEnd(byte[] d, int tp, int n, int i) {
        return i + 1 >= n || (d[tp + i + 1] & 0xC0) == 0;
    }

    // ------------------------------------------------------------------ preview bitmap

    /** The embedded 96x96 preview (dithered, low quality): used only if the vector data cannot be decoded. */
    public static BufferedImage preview(byte[] d) throws IOException {
        Chunk c = topLevel(d, d.length).get("DISP");
        if (c == null || c.len() < 4 + 40 + 1024) throw new IOException("No preview");
        int o = c.dataOff() + 4;
        int w = s32(d, o + 4), h = s32(d, o + 8), bpp = u16(d, o + 14);
        if (bpp != 8 || w <= 0 || h <= 0 || w > 1024 || h > 1024) throw new IOException("Unsupported preview");
        int pal = o + 40, px = pal + 1024;
        if (px + w * h > c.dataOff() + c.len()) throw new IOException("Truncated preview");
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = d[px + (h - 1 - y) * w + x] & 0xff;
                img.setRGB(x, y, (d[pal + 4 * idx + 2] & 0xff) << 16 | (d[pal + 4 * idx + 1] & 0xff) << 8 | (d[pal + 4 * idx] & 0xff));
            }
        }
        return img;
    }
}
