package gallery.export;

import gallery.catalog.Catalog;
import gallery.catalog.ClipartItem;
import gallery.catalog.DrawingLoader;
import gallery.drawing.Drawing;
import gallery.drawing.VectorDrawing;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.PathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;

/** Writes a {@link Drawing} out as SVG, PDF, EPS (all true vector), PNG, or copies the original disc file. */
public final class Exporter {
    private Exporter() {}

    public enum Format {
        SVG("SVG (vector)", "svg"),
        PDF("PDF (vector)", "pdf"),
        EPS("EPS (vector)", "eps"),
        PNG("PNG (raster)", "png"),
        ORIGINAL("Original file from disc", "");

        public final String label, ext;
        Format(String label, String ext) { this.label = label; this.ext = ext; }
        @Override public String toString() { return label; }
    }

    /** Export one catalog item. {@code size} = length of the longest side (px for SVG/PNG, pt for PDF/EPS). */
    public static void exportItem(Catalog catalog, ClipartItem item, Format f, Path out, int size) throws Exception {
        if (f == Format.ORIGINAL) {
            Files.write(out, catalog.read(item));
            return;
        }
        export(DrawingLoader.load(catalog, item), f, out, size);
    }

    public static void export(Drawing d, Format f, Path out, int size) throws Exception {
        if (f == Format.PNG) { png(d, out, size); return; }
        VectorDrawing v = d.asVector();
        if (v == null) throw new IOException("This item is a bitmap, so it can only be exported as PNG or as the original file");
        switch (f) {
            case SVG -> Files.writeString(out, svg(v, size), StandardCharsets.UTF_8);
            case PDF -> Files.write(out, pdf(v, size));
            case EPS -> Files.writeString(out, eps(v, size), StandardCharsets.ISO_8859_1);
            default -> throw new IllegalArgumentException("Unsupported format " + f);
        }
    }

    // ------------------------------------------------------------------ PNG

    private static void png(Drawing d, Path out, int size) throws IOException {
        ImageIO.write(render(d, size), "png", out.toFile());
    }

    /** Renders onto a transparent canvas whose longest side is {@code size} pixels. */
    public static BufferedImage render(Drawing d, int size) {
        Rectangle2D b = d.bounds();
        double scale = size / Math.max(b.getWidth(), b.getHeight());
        int w = Math.max(1, (int) Math.round(b.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(b.getHeight() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(scale, scale);
        g.translate(-b.getX(), -b.getY());
        d.draw(g);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ shared helpers

    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.indexOf('.') >= 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private static String hex(int rgb) { return String.format("#%06x", rgb & 0xFFFFFF); }

    private static String rgb01(int rgb) {
        return num(((rgb >> 16) & 255) / 255.0) + " " + num(((rgb >> 8) & 255) / 255.0) + " " + num((rgb & 255) / 255.0);
    }

    private static String rgb01(int rgb, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f %." + digits + "f %." + digits + "f",
                ((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0);
    }

    /** Path as svg 'd' data (M/L/C/Z). */
    private static String svgPath(Path2D p) {
        StringBuilder sb = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = p.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> sb.append('M').append(num(c[0])).append(' ').append(num(c[1]));
                case PathIterator.SEG_LINETO -> sb.append('L').append(num(c[0])).append(' ').append(num(c[1]));
                case PathIterator.SEG_CUBICTO -> sb.append('C').append(num(c[0])).append(' ').append(num(c[1])).append(' ')
                        .append(num(c[2])).append(' ').append(num(c[3])).append(' ').append(num(c[4])).append(' ').append(num(c[5]));
                case PathIterator.SEG_QUADTO -> sb.append('Q').append(num(c[0])).append(' ').append(num(c[1])).append(' ')
                        .append(num(c[2])).append(' ').append(num(c[3]));
                case PathIterator.SEG_CLOSE -> sb.append('Z');
                default -> { }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ SVG

    static String svg(VectorDrawing v, int size) {
        Rectangle2D b = v.bounds();
        double s = size / Math.max(b.getWidth(), b.getHeight());
        StringBuilder defs = new StringBuilder(), body = new StringBuilder();
        int gid = 0;
        for (VectorDrawing.Item it : v.items()) {
            VectorDrawing.Fill f = it.fill();
            String fillAttr = "none";
            if (f.kind() == VectorDrawing.Fill.Kind.SOLID) {
                fillAttr = hex(f.rgb());
            } else if (f.kind() != VectorDrawing.Fill.Kind.NONE) {
                List<VectorDrawing.Stop> st = VectorDrawing.normalizedStops(f);
                Rectangle2D pb = it.path().getBounds2D();
                if (st.size() < 2) {
                    fillAttr = hex(st.isEmpty() ? f.rgb() : st.get(0).rgb());
                } else {
                    String id = "g" + (++gid);
                    if (f.kind() == VectorDrawing.Fill.Kind.RADIAL) {
                        double[] c = VectorDrawing.radialCircle(pb);
                        defs.append("<radialGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" cx=\"")
                                .append(num(c[0])).append("\" cy=\"").append(num(c[1])).append("\" r=\"").append(num(c[2])).append("\">");
                        // Corel's first stop is the outer edge; SVG's is the centre
                        for (int i = st.size() - 1; i >= 0; i--)
                            defs.append("<stop offset=\"").append(num((1 - st.get(i).pos()) * 100)).append("%\" stop-color=\"")
                                    .append(hex(st.get(i).rgb())).append("\"/>");
                        defs.append("</radialGradient>\n");
                    } else {
                        double[] a = VectorDrawing.linearAxis(f, pb);
                        defs.append("<linearGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" x1=\"")
                                .append(num(a[0])).append("\" y1=\"").append(num(a[1])).append("\" x2=\"")
                                .append(num(a[2])).append("\" y2=\"").append(num(a[3])).append("\">");
                        for (VectorDrawing.Stop sp : st)
                            defs.append("<stop offset=\"").append(num(sp.pos() * 100)).append("%\" stop-color=\"")
                                    .append(hex(sp.rgb())).append("\"/>");
                        defs.append("</linearGradient>\n");
                    }
                    fillAttr = "url(#" + id + ")";
                }
            }
            String d = svgPath(it.path());
            VectorDrawing.Pen pen = it.pen();
            String strokeAttrs = pen == null ? "" : " stroke=\"" + hex(pen.rgb()) + "\" stroke-width=\"" + num(Math.max(pen.width(), 0.01))
                    + "\" stroke-linejoin=\"round\" stroke-linecap=\"round\"";
            if (pen != null && pen.behind()) {
                // the outline is painted first and the fill covers its inner half: two elements, which every editor understands
                body.append("<path d=\"").append(d).append("\" fill=\"none\"").append(strokeAttrs).append("/>\n");
                strokeAttrs = "";
            }
            body.append("<path d=\"").append(d).append("\" fill=\"").append(fillAttr).append('"');
            if (f.kind() != VectorDrawing.Fill.Kind.NONE) body.append(" fill-rule=\"evenodd\"");
            body.append(strokeAttrs).append("/>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + num(b.getWidth() * s) + "\" height=\"" + num(b.getHeight() * s)
                + "\" viewBox=\"" + num(b.getX()) + " " + num(b.getY()) + " " + num(b.getWidth()) + " " + num(b.getHeight()) + "\">\n"
                + (defs.length() > 0 ? "<defs>\n" + defs + "</defs>\n" : "")
                + body + "</svg>\n";
    }

    // ------------------------------------------------------------------ PDF / EPS shared path + function builders

    private static String pdfPath(Path2D p, boolean ps) {
        StringBuilder sb = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = p.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> sb.append(num(c[0])).append(' ').append(num(c[1])).append(ps ? " moveto\n" : " m\n");
                case PathIterator.SEG_LINETO -> sb.append(num(c[0])).append(' ').append(num(c[1])).append(ps ? " lineto\n" : " l\n");
                case PathIterator.SEG_CUBICTO -> sb.append(num(c[0])).append(' ').append(num(c[1])).append(' ')
                        .append(num(c[2])).append(' ').append(num(c[3])).append(' ').append(num(c[4])).append(' ').append(num(c[5]))
                        .append(ps ? " curveto\n" : " c\n");
                case PathIterator.SEG_QUADTO -> {
                    // not produced by the decoder; elevate to a cubic is not possible without the current point, so ignore
                }
                case PathIterator.SEG_CLOSE -> sb.append(ps ? "closepath\n" : "h\n");
                default -> { }
            }
        }
        return sb.toString();
    }

    /** PDF/PostScript function (type 2 for two stops, type 3 stitching otherwise) mapping 0..1 to RGB. */
    private static String functionDict(List<VectorDrawing.Stop> st, boolean reverse) {
        List<VectorDrawing.Stop> s = new ArrayList<>();
        for (VectorDrawing.Stop x : st) s.add(new VectorDrawing.Stop(reverse ? 1 - x.pos() : x.pos(), x.rgb()));
        if (reverse) java.util.Collections.reverse(s);
        if (s.get(0).pos() > 0) s.add(0, new VectorDrawing.Stop(0, s.get(0).rgb()));
        if (s.get(s.size() - 1).pos() < 1) s.add(new VectorDrawing.Stop(1, s.get(s.size() - 1).rgb()));
        if (s.size() == 2) {
            return "<< /FunctionType 2 /Domain [0 1] /C0 [" + rgb01(s.get(0).rgb(), 4) + "] /C1 [" + rgb01(s.get(1).rgb(), 4) + "] /N 1 >>";
        }
        StringBuilder fns = new StringBuilder(), bounds = new StringBuilder(), enc = new StringBuilder();
        for (int i = 0; i + 1 < s.size(); i++) {
            fns.append("<< /FunctionType 2 /Domain [0 1] /C0 [").append(rgb01(s.get(i).rgb(), 4)).append("] /C1 [")
                    .append(rgb01(s.get(i + 1).rgb(), 4)).append("] /N 1 >> ");
            if (i > 0) bounds.append(String.format(Locale.ROOT, "%.4f ", s.get(i).pos()));
            enc.append("0 1 ");
        }
        return "<< /FunctionType 3 /Domain [0 1] /Functions [" + fns + "] /Bounds [" + bounds + "] /Encode [" + enc + "] >>";
    }

    private static String shadingDict(VectorDrawing.Fill f, Rectangle2D pb, boolean level3Inline) {
        List<VectorDrawing.Stop> st = VectorDrawing.normalizedStops(f);
        if (f.kind() == VectorDrawing.Fill.Kind.RADIAL) {
            double[] c = VectorDrawing.radialCircle(pb);
            return "<< /ShadingType 3 /ColorSpace /DeviceRGB /Coords [" + num(c[0]) + " " + num(c[1]) + " 0 " + num(c[0]) + " " + num(c[1]) + " " + num(c[2])
                    + "] /Function " + functionDict(st, true) + " /Extend [true true] >>";
        }
        double[] a = VectorDrawing.linearAxis(f, pb);
        return "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [" + num(a[0]) + " " + num(a[1]) + " " + num(a[2]) + " " + num(a[3])
                + "] /Function " + functionDict(st, false) + " /Extend [true true] >>";
    }

    private static boolean isGradient(VectorDrawing.Fill f) {
        return (f.kind() == VectorDrawing.Fill.Kind.LINEAR || f.kind() == VectorDrawing.Fill.Kind.RADIAL)
                && VectorDrawing.normalizedStops(f).size() >= 2;
    }

    private static int flatColour(VectorDrawing.Fill f) {
        if (f.kind() == VectorDrawing.Fill.Kind.SOLID) return f.rgb();
        List<VectorDrawing.Stop> st = VectorDrawing.normalizedStops(f);
        return st.isEmpty() ? f.rgb() : st.get(0).rgb();
    }

    // ------------------------------------------------------------------ PDF

    static byte[] pdf(VectorDrawing v, int size) throws IOException {
        Rectangle2D b = v.bounds();
        double s = size / Math.max(b.getWidth(), b.getHeight());
        double pw = b.getWidth() * s, ph = b.getHeight() * s;

        StringBuilder content = new StringBuilder();
        List<String> shadings = new ArrayList<>();
        // model is y-down: PDF y = (bottom - y) * s
        content.append("q\n").append(num(s)).append(" 0 0 ").append(num(-s)).append(' ')
                .append(num(-b.getX() * s)).append(' ').append(num((b.getY() + b.getHeight()) * s)).append(" cm\n");
        content.append("1 j 1 J\n");
        for (VectorDrawing.Item it : v.items()) {
            String path = pdfPath(it.path(), false);
            VectorDrawing.Fill f = it.fill();
            VectorDrawing.Pen pen = it.pen();
            String strokeOps = pen == null ? "" : rgb01(pen.rgb()) + " RG " + num(Math.max(pen.width(), 0.01)) + " w\n" + path + "S\n";
            boolean behind = pen != null && pen.behind();
            if (behind) content.append(strokeOps);
            if (f.kind() != VectorDrawing.Fill.Kind.NONE) {
                if (isGradient(f)) {
                    shadings.add(shadingDict(f, it.path().getBounds2D(), false));
                    content.append("q\n").append(path).append("W* n\n/Sh").append(shadings.size()).append(" sh\nQ\n");
                } else {
                    content.append(rgb01(flatColour(f))).append(" rg\n").append(path).append("f*\n");
                }
            }
            if (!behind) content.append(strokeOps);
        }
        content.append("Q\n");

        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream dz = new DeflaterOutputStream(z)) {
            dz.write(content.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        byte[] stream = z.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        out.write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder res = new StringBuilder();
        if (!shadings.isEmpty()) {
            res.append("/Shading << ");
            for (int i = 0; i < shadings.size(); i++) res.append("/Sh").append(i + 1).append(' ').append(5 + i).append(" 0 R ");
            res.append(">>");
        }
        writeObj(out, offsets, "<< /Type /Catalog /Pages 2 0 R >>");
        writeObj(out, offsets, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        writeObj(out, offsets, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(pw) + " " + num(ph)
                + "] /Contents 4 0 R /Resources << " + res + " >> >>");
        offsets.add(out.size());
        out.write(("4 0 obj\n<< /Length " + stream.length + " /Filter /FlateDecode >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(stream);
        out.write("\nendstream\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        for (String sh : shadings) writeObj(out, offsets, sh);
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) x.append(String.format(Locale.ROOT, "%010d 00000 n \n", o));
        x.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.write(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static void writeObj(ByteArrayOutputStream out, List<Integer> offsets, String body) throws IOException {
        offsets.add(out.size());
        out.write(((offsets.size()) + " 0 obj\n" + body + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    // ------------------------------------------------------------------ EPS (PostScript Level 3 shading)

    static String eps(VectorDrawing v, int size) {
        Rectangle2D b = v.bounds();
        double s = size / Math.max(b.getWidth(), b.getHeight());
        int pw = (int) Math.ceil(b.getWidth() * s), ph = (int) Math.ceil(b.getHeight() * s);
        StringBuilder sb = new StringBuilder();
        sb.append("%!PS-Adobe-3.0 EPSF-3.0\n%%BoundingBox: 0 0 ").append(pw).append(' ').append(ph).append('\n')
                .append("%%HiResBoundingBox: 0 0 ").append(num(b.getWidth() * s)).append(' ').append(num(b.getHeight() * s)).append('\n')
                .append("%%LanguageLevel: 3\n%%Creator: Clipart Gallery\n%%EndComments\n");
        sb.append("gsave\n[").append(num(s)).append(" 0 0 ").append(num(-s)).append(' ').append(num(-b.getX() * s)).append(' ')
                .append(num((b.getY() + b.getHeight()) * s)).append("] concat\n1 setlinejoin 1 setlinecap\n");
        for (VectorDrawing.Item it : v.items()) {
            String path = pdfPath(it.path(), true);
            VectorDrawing.Fill f = it.fill();
            VectorDrawing.Pen pen = it.pen();
            String strokeOps = pen == null ? "" : rgb01(pen.rgb()) + " setrgbcolor " + num(Math.max(pen.width(), 0.01))
                    + " setlinewidth\nnewpath\n" + path + "stroke\n";
            boolean behind = pen != null && pen.behind();
            if (behind) sb.append(strokeOps);
            if (f.kind() != VectorDrawing.Fill.Kind.NONE) {
                if (isGradient(f)) {
                    sb.append("gsave newpath\n").append(path).append("eoclip newpath\n")
                            .append(shadingDict(f, it.path().getBounds2D(), true)).append(" shfill\ngrestore\n");
                } else {
                    sb.append(rgb01(flatColour(f))).append(" setrgbcolor\nnewpath\n").append(path).append("eofill\n");
                }
            }
            if (!behind) sb.append(strokeOps);
        }
        sb.append("grestore\nshowpage\n%%EOF\n");
        return sb.toString();
    }
}
