package gallery.codec;

import gallery.drawing.AnimatedImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Reads GIFs (including animated ones) into fully composed frames, honouring offsets, transparency and disposal. */
public final class GifReader {
    private GifReader() {}

    private static final long MAX_PIXELS = 48_000_000L;   // keeps a pathological animation from eating the heap

    public static AnimatedImage read(byte[] data) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("No GIF reader available");
        ImageReader rd = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            rd.setInput(in, false, false);
            int n = rd.getNumImages(true);
            if (n <= 0) throw new IOException("Empty GIF");
            int w = 0, h = 0;
            try {
                IIOMetadata sm = rd.getStreamMetadata();
                if (sm != null) {
                    IIOMetadataNode lsd = find((IIOMetadataNode) sm.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                    if (lsd != null) {
                        w = attr(lsd, "logicalScreenWidth", 0);
                        h = attr(lsd, "logicalScreenHeight", 0);
                    }
                }
            } catch (RuntimeException ignored) {
            }
            BufferedImage first = rd.read(0);
            w = Math.max(w, first.getWidth());
            h = Math.max(h, first.getHeight());
            if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) throw new IOException("Unsupported GIF size");
            int keep = (int) Math.max(1, Math.min(n, MAX_PIXELS / ((long) w * h)));

            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            BufferedImage restore = null;
            int prevDisp = 0, px = 0, py = 0, pw = 0, ph = 0;
            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            for (int i = 0; i < keep; i++) {
                BufferedImage fr = i == 0 ? first : rd.read(i);
                int left = 0, top = 0, disp = 0, delay = 0;
                try {
                    IIOMetadataNode root = (IIOMetadataNode) rd.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                    IIOMetadataNode id = find(root, "ImageDescriptor");
                    if (id != null) { left = attr(id, "imageLeftPosition", 0); top = attr(id, "imageTopPosition", 0); }
                    IIOMetadataNode gce = find(root, "GraphicControlExtension");
                    if (gce != null) {
                        delay = attr(gce, "delayTime", 0) * 10;
                        String d = gce.getAttribute("disposalMethod");
                        disp = "restoreToBackgroundColor".equals(d) ? 2 : "restoreToPrevious".equals(d) ? 3 : 0;
                    }
                } catch (RuntimeException ignored) {
                }
                Graphics2D g = canvas.createGraphics();
                try {
                    // undo the previous frame as it asked
                    if (prevDisp == 2) {
                        g.setComposite(AlphaComposite.Clear);
                        g.fillRect(px, py, pw, ph);
                    } else if (prevDisp == 3 && restore != null) {
                        g.setComposite(AlphaComposite.Src);
                        g.drawImage(restore, 0, 0, null);
                    }
                    restore = disp == 3 ? copy(canvas) : null;
                    g.setComposite(AlphaComposite.SrcOver);
                    g.drawImage(fr, left, top, null);
                } finally { g.dispose(); }
                frames.add(copy(canvas));
                delays.add(delay <= 10 ? 100 : delay);   // browsers treat 0-10 ms as 100 ms
                prevDisp = disp; px = left; py = top; pw = fr.getWidth(); ph = fr.getHeight();
            }
            int[] d = new int[delays.size()];
            for (int i = 0; i < d.length; i++) d[i] = delays.get(i);
            return new AnimatedImage(frames.toArray(new BufferedImage[0]), d);
        } finally {
            rd.dispose();
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    private static IIOMetadataNode find(IIOMetadataNode n, String name) {
        if (n == null) return null;
        if (name.equals(n.getNodeName())) return n;
        for (int i = 0; i < n.getLength(); i++) {
            IIOMetadataNode r = find((IIOMetadataNode) n.item(i), name);
            if (r != null) return r;
        }
        return null;
    }

    private static int attr(IIOMetadataNode n, String name, int def) {
        try { return Integer.parseInt(n.getAttribute(name)); } catch (NumberFormatException e) { return def; }
    }
}
