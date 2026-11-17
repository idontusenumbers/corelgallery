package gallery.codec;

import gallery.drawing.Drawing;
import gallery.drawing.VectorDrawing;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;

/**
 * A read-only {@link VectorDrawing} packed into a few primitive arrays, for the thumbnail cache.
 * Coordinates are kept as CMX's own 16-bit integers; fills are turned into {@link Paint}s once, when the drawing is
 * built.  Drawing it allocates almost nothing (one reusable {@link Shape} walks the arrays), so thumbnails can be
 * redrawn as often as needed without making garbage.  It paints exactly what the equivalent VectorDrawing paints.
 */
public final class CompactDrawing implements Drawing {
    private static final byte MOVE = 0, LINE = 1, CUBIC = 2, CLOSE = 3;

    private final Rectangle2D bounds;
    private final short[] xy;         // raw CMX coordinates, x then y (y up: negated when drawn)
    private final byte[] seg;         // MOVE / LINE / CUBIC / CLOSE
    private final int[] segStart;     // per shape, plus one: shape i is seg[segStart[i] .. segStart[i + 1])
    private final int[] xyStart;      // per shape: its first coordinate in xy
    private final Paint[] fill;       // per shape; null = not filled
    private final Color[] pen;        // per shape; null = no outline
    private final double[] penWidth;
    private final boolean[] behind;   // outline painted under the fill

    private CompactDrawing(Rectangle2D bounds, short[] xy, byte[] seg, int[] segStart, int[] xyStart,
                           Paint[] fill, Color[] pen, double[] penWidth, boolean[] behind) {
        this.bounds = bounds;
        this.xy = xy;
        this.seg = seg;
        this.segStart = segStart;
        this.xyStart = xyStart;
        this.fill = fill;
        this.pen = pen;
        this.penWidth = penWidth;
        this.behind = behind;
    }

    @Override public Rectangle2D bounds() { return bounds; }

    /** Same as {@link VectorDrawing#draw}: hairlines kept at least ~0.8 device pixels wide. */
    @Override public void draw(Graphics2D g) {
        AffineTransform t = g.getTransform();
        double scale = Math.sqrt(Math.abs(t.getDeterminant()));
        if (scale <= 0) scale = 1;
        double minWidth = 0.8 / scale;
        Paint oldPaint = g.getPaint();
        Stroke oldStroke = g.getStroke();
        ShapeView shape = new ShapeView();
        BasicStroke stroke = null;
        float strokeWidth = -1;
        for (int i = 0; i < fill.length; i++) {
            shape.item = i;
            boolean under = pen[i] != null && behind[i];
            for (int pass = 0; pass < 3; pass++) {
                if (pass == 1) {
                    if (fill[i] != null) { g.setPaint(fill[i]); g.fill(shape); }
                } else if (pen[i] != null && (pass == 0) == under) {
                    float w = (float) Math.max(penWidth[i], minWidth);
                    if (w != strokeWidth) { stroke = new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); strokeWidth = w; }
                    g.setPaint(pen[i]);
                    g.setStroke(stroke);
                    g.draw(shape);
                }
            }
        }
        g.setPaint(oldPaint);
        g.setStroke(oldStroke);
    }

    /** One shape of the drawing as a java.awt.Shape.  Reuses a single iterator: one iteration at a time, one thread. */
    private final class ShapeView implements Shape {
        int item;
        private final Iter iter = new Iter();

        @Override public PathIterator getPathIterator(AffineTransform at) { iter.start(item, at); return iter; }
        @Override public PathIterator getPathIterator(AffineTransform at, double flatness) {
            return toPath(item).getPathIterator(at, flatness);
        }
        // rarely used by Java2D: answer them from an ordinary path
        @Override public Rectangle getBounds() { return toPath(item).getBounds(); }
        @Override public Rectangle2D getBounds2D() { return toPath(item).getBounds2D(); }
        @Override public boolean contains(double x, double y) { return toPath(item).contains(x, y); }
        @Override public boolean contains(Point2D p) { return toPath(item).contains(p); }
        @Override public boolean intersects(double x, double y, double w, double h) { return toPath(item).intersects(x, y, w, h); }
        @Override public boolean intersects(Rectangle2D r) { return toPath(item).intersects(r); }
        @Override public boolean contains(double x, double y, double w, double h) { return toPath(item).contains(x, y, w, h); }
        @Override public boolean contains(Rectangle2D r) { return toPath(item).contains(r); }
    }

    private final class Iter implements PathIterator {
        private int s, end, p;
        private AffineTransform at;
        private final double[] tmp = new double[6];

        void start(int item, AffineTransform at) {
            s = segStart[item];
            end = segStart[item + 1];
            p = xyStart[item];
            this.at = at == null || at.isIdentity() ? null : at;
        }

        @Override public int getWindingRule() { return WIND_EVEN_ODD; }
        @Override public boolean isDone() { return s >= end; }
        @Override public void next() { p += points(seg[s++]) * 2; }

        @Override public int currentSegment(double[] c) {
            byte t = seg[s];
            int n = points(t);
            for (int k = 0; k < n; k++) { c[2 * k] = xy[p + 2 * k]; c[2 * k + 1] = -xy[p + 2 * k + 1]; }
            if (at != null && n > 0) at.transform(c, 0, c, 0, n);
            return type(t);
        }

        @Override public int currentSegment(float[] c) {
            int type = currentSegment(tmp);
            for (int k = 0; k < 6; k++) c[k] = (float) tmp[k];
            return type;
        }
    }

    private static int points(byte t) { return t == CUBIC ? 3 : t == CLOSE ? 0 : 1; }

    private static int type(byte t) {
        return switch (t) {
            case MOVE -> PathIterator.SEG_MOVETO;
            case LINE -> PathIterator.SEG_LINETO;
            case CUBIC -> PathIterator.SEG_CUBICTO;
            default -> PathIterator.SEG_CLOSE;
        };
    }

    /** Shape i as an ordinary path (for the rarely used Shape methods, and for bounds while building). */
    private Path2D.Double toPath(int i) {
        return appendPath(new Path2D.Double(Path2D.WIND_EVEN_ODD), xy, seg, segStart[i], segStart[i + 1], xyStart[i]);
    }

    private static Path2D.Double appendPath(Path2D.Double path, short[] xy, byte[] seg, int s, int end, int p) {
        for (; s < end; s++) {
            switch (seg[s]) {
                case MOVE -> path.moveTo(xy[p], -xy[p + 1]);
                case LINE -> path.lineTo(xy[p], -xy[p + 1]);
                case CUBIC -> path.curveTo(xy[p], -xy[p + 1], xy[p + 2], -xy[p + 3], xy[p + 4], -xy[p + 5]);
                default -> path.closePath();
            }
            p += points(seg[s]) * 2;
        }
        return path;
    }

    /**
     * Builds CompactDrawings from the decoder's shapes.  Reusable (one per thread): its working arrays only grow, so
     * after warming up, building a drawing allocates little more than the finished drawing's own arrays.
     */
    static final class Builder implements CcxDecoder.Sink {
        private short[] xy = new short[8192];
        private byte[] seg = new byte[4096];
        private int nxy, nseg, n;
        private int[] segStart = new int[256], xyStart = new int[256];
        private Paint[] fill = new Paint[256];
        private Color[] pen = new Color[256];
        private double[] penWidth = new double[256];
        private boolean[] behind = new boolean[256];
        private VectorDrawing.Fill curFill;
        private final Path2D.Double tmp = new Path2D.Double(Path2D.WIND_EVEN_ODD, 1024);
        // colours already made for this drawing (most drawings use a few dozen)
        private final int[] rgbs = new int[64];
        private final Color[] colors = new Color[64];
        private int ncol;

        void reset() {
            nxy = nseg = n = ncol = 0;
            Arrays.fill(colors, null);
        }

        @Override public void begin(VectorDrawing.Fill f, VectorDrawing.Pen p, int nodes) {
            if (n + 1 >= segStart.length) {
                int c = segStart.length * 2;
                segStart = Arrays.copyOf(segStart, c);
                xyStart = Arrays.copyOf(xyStart, c);
                fill = Arrays.copyOf(fill, c);
                pen = Arrays.copyOf(pen, c);
                penWidth = Arrays.copyOf(penWidth, c);
                behind = Arrays.copyOf(behind, c);
            }
            // each node adds at most 2 coordinates, and at most one segment plus a close
            if (nxy + 2 * nodes > xy.length) xy = Arrays.copyOf(xy, Math.max(xy.length * 2, nxy + 2 * nodes));
            if (nseg + 2 * nodes > seg.length) seg = Arrays.copyOf(seg, Math.max(seg.length * 2, nseg + 2 * nodes));
            segStart[n] = nseg;
            xyStart[n] = nxy;
            curFill = f;
            pen[n] = p == null ? null : color(p.rgb());
            penWidth[n] = p == null ? 0 : p.width();
            behind[n] = p != null && p.behind();
        }

        @Override public void moveTo(int x, int y) { seg[nseg++] = MOVE; xy[nxy++] = (short) x; xy[nxy++] = (short) y; }
        @Override public void lineTo(int x, int y) { seg[nseg++] = LINE; xy[nxy++] = (short) x; xy[nxy++] = (short) y; }
        @Override public void curveTo(int x1, int y1, int x2, int y2, int x3, int y3) {
            seg[nseg++] = CUBIC;
            xy[nxy++] = (short) x1; xy[nxy++] = (short) y1;
            xy[nxy++] = (short) x2; xy[nxy++] = (short) y2;
            xy[nxy++] = (short) x3; xy[nxy++] = (short) y3;
        }
        @Override public void closePath() { seg[nseg++] = CLOSE; }

        @Override public void end() {
            VectorDrawing.Fill f = curFill;
            segStart[n + 1] = nseg;
            fill[n] = switch (f.kind()) {
                case NONE -> null;
                case SOLID -> color(f.rgb());
                // gradients depend on the shape's bounds, exactly as VectorDrawing works them out when it draws
                default -> VectorDrawing.paint(f, itemBounds(n));
            };
            n++;
        }

        private Rectangle2D itemBounds(int i) {
            tmp.reset();
            return appendPath(tmp, xy, seg, segStart[i], segStart[i + 1], xyStart[i]).getBounds2D();
        }

        @Override public Rectangle2D contentBounds() {
            Rectangle2D u = null;
            for (int i = 0; i < n; i++) {
                Rectangle2D b = itemBounds(i);
                u = u == null ? b : u.createUnion(b);
            }
            return u;
        }

        private Color color(int rgb) {
            for (int i = 0; i < ncol; i++) if (rgbs[i] == rgb) return colors[i];
            Color c = VectorDrawing.color(rgb);
            if (ncol < rgbs.length) { rgbs[ncol] = rgb; colors[ncol++] = c; }
            return c;
        }

        CompactDrawing build(Rectangle2D bounds) {
            return new CompactDrawing(bounds, Arrays.copyOf(xy, nxy), Arrays.copyOf(seg, nseg), Arrays.copyOf(segStart, n + 1),
                    Arrays.copyOf(xyStart, n), Arrays.copyOf(fill, n), Arrays.copyOf(pen, n),
                    Arrays.copyOf(penWidth, n), Arrays.copyOf(behind, n));
        }
    }
}
