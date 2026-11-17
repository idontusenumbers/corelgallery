package gallery.drawing;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Format-neutral vector model: filled/stroked paths in painter's order. Coordinates have y pointing down. */
public final class VectorDrawing implements Drawing {

    public record Stop(double pos, int rgb) {}

    /** kind: NONE, SOLID, LINEAR, RADIAL. For gradients, angleDeg is counter-clockwise from "left to right". */
    public record Fill(Kind kind, int rgb, double angleDeg, List<Stop> stops) {
        public enum Kind { NONE, SOLID, LINEAR, RADIAL }
        public static final Fill NONE = new Fill(Kind.NONE, 0, 0, List.of());
        private static final Fill[] SOLID = new Fill[4096];   // recently used solid fills (immutable: sharing is safe)
        public static Fill solid(int rgb) {
            int h = (rgb ^ rgb >>> 12) & (SOLID.length - 1);
            Fill f = SOLID[h];
            if (f == null || f.rgb != rgb) SOLID[h] = f = new Fill(Kind.SOLID, rgb, 0, List.of());
            return f;
        }
    }

    /** {@code behind}: the outline is painted underneath the fill, so only its outer half shows. */
    public record Pen(int rgb, double width, boolean behind) {
        public Pen(int rgb, double width) { this(rgb, width, false); }
    }

    public record Item(Path2D path, Fill fill, Pen pen) {}

    private final Rectangle2D bounds;
    private final List<Item> items;

    public VectorDrawing(Rectangle2D bounds, List<Item> items) {
        this.bounds = bounds;
        this.items = List.copyOf(items);
    }

    @Override public Rectangle2D bounds() { return bounds; }
    @Override public VectorDrawing asVector() { return this; }
    public List<Item> items() { return items; }

    private static final Color[] COLORS = new Color[4096];   // recently used colours (immutable: sharing is safe)

    /** The opaque colour {@code rgb}. */
    public static Color color(int rgb) {
        int h = (rgb ^ rgb >>> 12) & (COLORS.length - 1);
        Color c = COLORS[h];
        if (c == null || (c.getRGB() & 0xFFFFFF) != (rgb & 0xFFFFFF)) COLORS[h] = c = new Color(rgb);
        return c;
    }

    // ---------------------------------------------------------------- gradient geometry (shared by all writers)

    /** Linear gradient axis for a shape: {x0, y0, x1, y1}. */
    public static double[] linearAxis(Fill f, Rectangle2D b) {
        double a = Math.toRadians(f.angleDeg());
        double dx = Math.cos(a), dy = -Math.sin(a);               // y-up angle -> y-down vector
        double cx = b.getCenterX(), cy = b.getCenterY();
        double half = (Math.abs(b.getWidth() * dx) + Math.abs(b.getHeight() * dy)) / 2;
        return new double[]{cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half};
    }

    /** Radial gradient: {cx, cy, r}. */
    public static double[] radialCircle(Rectangle2D b) {
        return new double[]{b.getCenterX(), b.getCenterY(), Math.max(1, Math.hypot(b.getWidth(), b.getHeight()) / 2)};
    }

    /** Stops sorted, positions as 0..1 and strictly increasing (required by Java2D). */
    public static List<Stop> normalizedStops(Fill f) {
        List<Stop> s = new ArrayList<>(f.stops());
        s.sort(Comparator.comparingDouble(Stop::pos));
        List<Stop> out = new ArrayList<>();
        double last = -1;
        for (Stop st : s) {
            double p = Math.max(0, Math.min(1, st.pos() / 100.0));
            if (p <= last) p = Math.min(1, last + 1e-4);
            out.add(new Stop(p, st.rgb()));
            last = p;
        }
        return out;
    }

    /** The Java2D paint for a fill over a shape with bounds {@code b}. */
    public static Paint paint(Fill f, Rectangle2D b) {
        if (f.kind() == Fill.Kind.SOLID) return color(f.rgb());
        List<Stop> st = normalizedStops(f);
        if (st.size() < 2 || b.getWidth() < 1e-6 && b.getHeight() < 1e-6) {
            return color(st.isEmpty() ? f.rgb() : st.get(0).rgb());
        }
        // Java2D wants fractions to start at 0 and end at 1
        List<Stop> s = new ArrayList<>(st);
        if (s.get(0).pos() > 0) s.add(0, new Stop(0, s.get(0).rgb()));
        if (s.get(s.size() - 1).pos() < 1) s.add(new Stop(1, s.get(s.size() - 1).rgb()));
        float[] fr = new float[s.size()];
        Color[] cl = new Color[s.size()];
        for (int i = 0; i < fr.length; i++) { fr[i] = (float) s.get(i).pos(); cl[i] = color(s.get(i).rgb()); }
        for (int i = 1; i < fr.length; i++) if (fr[i] <= fr[i - 1]) fr[i] = Math.min(1f, fr[i - 1] + 1e-4f);
        try {
            if (f.kind() == Fill.Kind.RADIAL) {
                double[] c = radialCircle(b);
                // Corel's first stop is the outer edge for radial fills; Java2D's is the centre
                Color[] rev = new Color[cl.length];
                float[] rf = new float[fr.length];
                for (int i = 0; i < cl.length; i++) { rev[i] = cl[cl.length - 1 - i]; rf[i] = 1f - fr[fr.length - 1 - i]; }
                return new RadialGradientPaint(new Point2D.Double(c[0], c[1]), (float) c[2], rf, rev);
            }
            double[] a = linearAxis(f, b);
            if (Math.hypot(a[2] - a[0], a[3] - a[1]) < 1e-6) return cl[0];
            return new LinearGradientPaint(new Point2D.Double(a[0], a[1]), new Point2D.Double(a[2], a[3]), fr, cl);
        } catch (IllegalArgumentException e) {
            return cl[0];
        }
    }

    /** Screen rendering: hairlines are kept at least ~0.8 device pixels wide so thin outlines stay visible. */
    @Override public void draw(Graphics2D g) { render(g, 0.8); }

    /** Exact rendering with no minimum line width. */
    public void drawExact(Graphics2D g) { render(g, 0); }

    private static void stroke(Graphics2D g, Item it, double scale, double minDevicePx) {
        if (it.pen() == null) return;
        double w = Math.max(it.pen().width(), minDevicePx / scale);
        g.setPaint(color(it.pen().rgb()));
        g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(it.path());
    }

    private void render(Graphics2D g, double minDevicePx) {
        AffineTransform t = g.getTransform();
        double scale = Math.sqrt(Math.abs(t.getDeterminant()));
        if (scale <= 0) scale = 1;
        Paint oldPaint = g.getPaint();
        Stroke oldStroke = g.getStroke();
        for (Item it : items) {
            boolean behind = it.pen() != null && it.pen().behind();
            if (behind) stroke(g, it, scale, minDevicePx);
            if (it.fill().kind() != Fill.Kind.NONE) {
                Rectangle2D pb = it.path().getBounds2D();
                g.setPaint(paint(it.fill(), pb));
                g.fill(it.path());
            }
            if (!behind) stroke(g, it, scale, minDevicePx);
        }
        g.setPaint(oldPaint);
        g.setStroke(oldStroke);
    }
}
