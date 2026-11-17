package gallery.catalog;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public record ClipartItem(String sourceId, String path, long size) implements Serializable {
    /** Corel .CCX (compressed CMX) and plain CMX/CDR. Only CCX/CMX have a decoder. */
    public static final Set<String> VECTOR = Set.of("ccx", "cmx", "cdr", "wmf", "emf", "svg");
    /** GIF web art lives in its own compact store (see WebArt), not in the main index. */
    public static final Set<String> WEBART = Set.of("gif");

    /** GIFs are kept out of the main index: the discs carry ~830,000 web-graphic button/bullet variants. */
    public static final Set<String> RASTER = Set.of("bmp", "png", "jpg", "jpeg", "tif", "tiff");
    /** Corel .WI photos: decoded by running swi32.dll from the user's disc 1 (see WiDecoder). */
    public static final Set<String> PROPRIETARY = Set.of("wi");

    public static boolean isClipart(String path) {
        String e = extOf(path);
        return VECTOR.contains(e) || RASTER.contains(e) || PROPRIETARY.contains(e);
    }

    public static String extOf(String path) {
        int s = path.lastIndexOf('/');
        int d = path.lastIndexOf('.');
        return d > s ? path.substring(d + 1).toLowerCase(Locale.ROOT) : "";
    }

    public String fileName() { return path.substring(path.lastIndexOf('/') + 1); }

    public String baseName() {
        String n = fileName();
        int d = n.lastIndexOf('.');
        return d <= 0 ? n : n.substring(0, d);
    }

    public String ext() { return extOf(path); }

    public String folder() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    public String sourceName() {
        Path p = Path.of(sourceId).getFileName();
        return p == null ? sourceId : p.toString();
    }

    public boolean isVector() { return VECTOR.contains(ext()); }
}
