package gallery.catalog;

import gallery.codec.CcxDecoder;
import gallery.codec.GifReader;
import gallery.codec.WiDecoder;
import gallery.drawing.Drawing;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Turns a clipart file from a disc into a {@link Drawing}. */
public final class DrawingLoader {
    private DrawingLoader() {}

    public static Drawing load(Catalog catalog, ClipartItem item) throws Exception {
        byte[] data = catalog.read(item);
        if (item.ext().equals("wi")) return bitmap(WiDecoder.decode(data, catalog.wiCodec()));
        return load(item.ext(), data);
    }

    public static Drawing load(String ext, byte[] data) throws Exception {
        if (isBlank(data)) throw new IOException("This file is blank (all zero bytes) in the disc image");
        switch (ext) {
            case "ccx":
            case "cmx":
                try {
                    return CcxDecoder.decode(data);
                } catch (IOException | RuntimeException e) {
                    // fall back to the embedded low-res preview, if the file has one
                    try {
                        return bitmap(CcxDecoder.preview(data));
                    } catch (IOException | RuntimeException ignored) {
                        throw e;
                    }
                }
            case "wi":
                throw new IOException("Corel .WI photos need the catalog (they are decoded by swi32.dll from disc 1)");
            case "gif":
                try {
                    return GifReader.read(data);
                } catch (IOException | RuntimeException e) {
                    break;   // odd file: let ImageIO have a go at the first frame
                }
            case "cdr": case "wmf": case "emf": case "svg":
                throw new IOException("." + ext + " files are not supported yet");
            default:
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) throw new IOException("Unsupported image format ." + ext);
        return bitmap(img);
    }

    /** A few hundred files (mostly GIFs) are zero-filled in the disc images themselves. */
    private static boolean isBlank(byte[] d) {
        if (d.length == 0) return true;
        for (int i = 0; i < Math.min(d.length, 32); i++) if (d[i] != 0) return false;
        return true;
    }

    private static Drawing bitmap(BufferedImage img) {
        Rectangle2D b = new Rectangle2D.Double(0, 0, img.getWidth(), img.getHeight());
        return new Drawing() {
            public Rectangle2D bounds() { return b; }
            public void draw(Graphics2D g) { g.drawImage(img, 0, 0, null); }
        };
    }
}
