package gallery.drawing;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

/** A loaded piece of clipart that can paint itself into any Graphics2D. */
public interface Drawing {
    /** Extent in the drawing's native coordinate space (y grows downward). */
    Rectangle2D bounds();

    /** Paint in native coordinates (caller applies translate/scale). */
    void draw(Graphics2D g);

    /** The resolution-independent model, or null for bitmaps. Only these can be exported as SVG/PDF/EPS. */
    default VectorDrawing asVector() { return null; }
}
