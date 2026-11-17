package gallery.drawing;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** A GIF: one or more fully composed frames. Painting shows the current frame (the first, until a viewer advances it). */
public final class AnimatedImage implements Drawing {
    private final BufferedImage[] frames;
    private final int[] delaysMs;
    private int current;

    public AnimatedImage(BufferedImage[] frames, int[] delaysMs) {
        this.frames = frames;
        this.delaysMs = delaysMs;
    }

    public int frameCount() { return frames.length; }
    public int delayMs(int i) { return delaysMs[i]; }
    public int current() { return current; }
    public void setFrame(int i) { current = Math.floorMod(i, frames.length); }

    @Override public Rectangle2D bounds() { return new Rectangle2D.Double(0, 0, frames[0].getWidth(), frames[0].getHeight()); }

    @Override public void draw(Graphics2D g) { g.drawImage(frames[current], 0, 0, null); }
}
