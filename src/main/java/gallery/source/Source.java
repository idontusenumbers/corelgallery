package gallery.source;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** Somewhere clipart files live: a disc image or an ordinary folder. */
public interface Source extends Closeable {
    record Entry(String path, long size) {}

    /** Stable identifier (absolute path of the image/folder). */
    String id();

    String displayName();

    /** All files, with '/'-separated paths relative to the source root. */
    List<Entry> list() throws IOException;

    byte[] read(String path) throws IOException;

    /** A reusable read buffer: after a read, the file is {@code data[0 .. length)}. */
    final class Buffer {
        public byte[] data = new byte[1 << 16];
        public int length;

        void ensure(int n) { if (data.length < n) data = new byte[Math.max(n, data.length + (data.length >> 1))]; }
    }

    /** Reads a whole file into {@code into}, reusing its array when it is big enough (no garbage per file). */
    default void read(String path, Buffer into) throws IOException {
        byte[] b = read(path);
        into.data = b;
        into.length = b.length;
    }

    /** The first {@code max} bytes of a file (all of it when shorter). */
    default byte[] readPrefix(String path, int max) throws IOException {
        byte[] b = read(path);
        return b.length <= max ? b : java.util.Arrays.copyOf(b, max);
    }

    /** Position of the file on the medium, so bulk reads can go in disc order. */
    default long order(String path) { return 0; }
}
