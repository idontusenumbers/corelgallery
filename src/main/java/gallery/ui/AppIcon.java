package gallery.ui;

import gallery.catalog.Catalog;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The application icon: Corel Gallery's own icon, taken from Gallery/Programs/Gallery.exe on the user's disc 1
 * (never bundled), enlarged with hard pixel edges and cached as ~/.corelgallery/icon.png.
 */
final class AppIcon {
    private static final int CANVAS = 512, SCALE = 14;   // 32 px icon -> 448 px, with a margin like other Dock icons

    private AppIcon() {}

    /** The cached icon, or one extracted from the discs (then cached). Null if disc 1 is not among the sources. */
    static BufferedImage load(Catalog catalog) {
        Path cache = catalog.homeDir().resolve("icon.png");
        try {
            if (Files.isRegularFile(cache)) return ImageIO.read(cache.toFile());
        } catch (IOException ignored) {
        }
        try {
            byte[] exe = catalog.findOnDiscs("gallery/programs/gallery.exe");
            if (exe == null) return null;
            BufferedImage small = fromExe(exe);
            if (small == null) return null;
            BufferedImage big = enlarge(small);
            try {
                Files.createDirectories(cache.getParent());
                ImageIO.write(big, "png", cache.toFile());
            } catch (IOException ignored) {
            }
            return big;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Sets the Dock / taskbar icon where the platform allows it, and the window icon. */
    static void apply(BufferedImage icon, Window w) {
        if (icon == null) return;
        w.setIconImage(icon);
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
                Taskbar.getTaskbar().setIconImage(icon);
        } catch (RuntimeException ignored) {
        }
    }

    private static BufferedImage enlarge(BufferedImage src) {
        int s = Math.min(SCALE, CANVAS / Math.max(src.getWidth(), src.getHeight()));
        int w = src.getWidth() * s, h = src.getHeight() * s;
        BufferedImage out = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, (CANVAS - w) / 2, (CANVAS - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------ Windows PE icon resources

    private static final int RT_ICON = 3, RT_GROUP_ICON = 14;

    /** The best image (largest, then deepest colour) of the first icon group in a Windows executable. */
    static BufferedImage fromExe(byte[] exe) {
        ByteBuffer b = ByteBuffer.wrap(exe).order(ByteOrder.LITTLE_ENDIAN);
        int pe = b.getInt(0x3c);
        if (b.getInt(pe) != 0x00004550) return null;   // "PE\0\0"
        int nsec = b.getShort(pe + 6) & 0xffff, optSize = b.getShort(pe + 20) & 0xffff, opt = pe + 24;
        int magic = b.getShort(opt) & 0xffff;
        int dirs = opt + (magic == 0x20b ? 112 : 96);
        int rsrcRva = b.getInt(dirs + 2 * 8);
        if (rsrcRva == 0) return null;
        int[][] secs = new int[nsec][];
        for (int i = 0; i < nsec; i++) {
            int s = opt + optSize + 40 * i;
            secs[i] = new int[]{b.getInt(s + 12), Math.max(b.getInt(s + 8), b.getInt(s + 16)), b.getInt(s + 20)};
        }
        Res r = new Res(b, secs, rva(secs, rsrcRva));
        List<int[]> groups = r.entries(0, RT_GROUP_ICON);
        if (groups.isEmpty()) return null;
        byte[] grp = r.leaf(groups.get(0)[1]);
        ByteBuffer g = ByteBuffer.wrap(grp).order(ByteOrder.LITTLE_ENDIAN);
        int count = g.getShort(4) & 0xffff, bestId = -1, bestScore = -1;
        for (int i = 0; i < count; i++) {
            int e = 6 + 14 * i;
            int w = grp[e] & 0xff, bpp = g.getShort(e + 6) & 0xffff, id = g.getShort(e + 12) & 0xffff;
            int score = (w == 0 ? 256 : w) * 64 + bpp;
            if (score > bestScore) { bestScore = score; bestId = id; }
        }
        for (int[] ic : r.entries(0, RT_ICON)) if (ic[0] == bestId) return decodeDib(r.leaf(ic[1]));
        return null;
    }

    private static int rva(int[][] secs, int rva) {
        for (int[] s : secs) if (rva >= s[0] && rva < s[0] + s[1]) return rva - s[0] + s[2];
        throw new IllegalArgumentException("bad RVA");
    }

    private record Res(ByteBuffer b, int[][] secs, int base) {
        /** Entries {id, offset} of a resource directory, or of the subdirectory with the given id (type >= 0). */
        List<int[]> entries(int dirOff, int type) {
            List<int[]> out = new ArrayList<>();
            for (int[] e : list(dirOff)) if (e[0] == type && (e[1] & 0x80000000) != 0) return list(e[1] & 0x7fffffff);
            return out;
        }
        List<int[]> list(int dirOff) {
            int n = (b.getShort(base + dirOff + 12) & 0xffff) + (b.getShort(base + dirOff + 14) & 0xffff);
            List<int[]> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int at = base + dirOff + 16 + 8 * i;
                out.add(new int[]{b.getInt(at), b.getInt(at + 4)});
            }
            return out;
        }
        /** Follows the first entry down to the data and returns its bytes. */
        byte[] leaf(int off) {
            while ((off & 0x80000000) != 0) off = list(off & 0x7fffffff).get(0)[1];
            int at = rva(secs, b.getInt(base + off)), size = b.getInt(base + off + 4);
            byte[] d = new byte[size];
            b.get(at, d);
            return d;
        }
    }

    /** An icon image: BITMAPINFOHEADER (height doubled), palette, bottom-up XOR pixels, then a 1-bit AND mask. */
    private static BufferedImage decodeDib(byte[] dib) {
        ByteBuffer b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        if (dib.length > 8 && (dib[0] & 0xff) == 0x89 && dib[1] == 'P') {   // Vista-style PNG icon
            try { return ImageIO.read(new java.io.ByteArrayInputStream(dib)); } catch (IOException e) { return null; }
        }
        int hdr = b.getInt(0), w = b.getInt(4), h = Math.abs(b.getInt(8)) / 2, bpp = b.getShort(14) & 0xffff;
        int colors = b.getInt(32);
        if (colors == 0 && bpp <= 8) colors = 1 << bpp;
        int pal = hdr, px = pal + 4 * (bpp <= 8 ? colors : 0);
        int stride = ((w * bpp + 31) / 32) * 4, maskStride = ((w + 31) / 32) * 4, mask = px + stride * h;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean hasAlpha = false;
        if (bpp == 32) for (int i = px + 3; i < mask; i += 4) if (dib[i] != 0) { hasAlpha = true; break; }
        for (int y = 0; y < h; y++) {
            int row = px + stride * (h - 1 - y), mrow = mask + maskStride * (h - 1 - y);
            for (int x = 0; x < w; x++) {
                int argb;
                if (bpp <= 8) {
                    int bit = x * bpp, v = ((dib[row + bit / 8] & 0xff) >> (8 - bpp - bit % 8)) & ((1 << bpp) - 1);
                    argb = 0xff000000 | (b.getInt(pal + 4 * v) & 0xffffff);
                } else if (bpp == 24) {
                    int o = row + 3 * x;
                    argb = 0xff000000 | (dib[o + 2] & 0xff) << 16 | (dib[o + 1] & 0xff) << 8 | (dib[o] & 0xff);
                } else {
                    argb = b.getInt(row + 4 * x);
                    if (!hasAlpha) argb |= 0xff000000;
                }
                boolean transparent = mrow + x / 8 < dib.length && ((dib[mrow + x / 8] >> (7 - x % 8)) & 1) != 0;
                if (transparent && !hasAlpha) argb = 0;
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }
}
