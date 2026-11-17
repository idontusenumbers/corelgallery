package gallery.source;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Pure-Java ISO 9660 reader (with Joliet long/Unicode names when present).
 * Handles cooked .iso (2048-byte sectors) and raw .bin/.img dumps
 * (2352-byte Mode 1 / Mode 2 Form 1 and 2336-byte sectors).
 */
public final class IsoSource implements Source {
    /** {sectorSize, offset of the 2048 user-data bytes within a sector}. */
    private static final int[][] LAYOUTS = {{2048, 0}, {2352, 16}, {2352, 24}, {2336, 8}};

    private final Path file;
    private final RandomAccessFile raf;
    private int sectorSize, dataOffset;
    private boolean joliet;
    private long rootLba, rootLen;
    private Map<String, long[]> files;   // path -> {lba, size}
    private List<Entry> entries;

    public IsoSource(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.raf = new RandomAccessFile(this.file.toFile(), "r");
        try {
            detect();
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    @Override public String id() { return file.toString(); }
    @Override public String displayName() { return file.getFileName().toString(); }

    private void detect() throws IOException {
        for (int[] l : LAYOUTS) {
            sectorSize = l[0];
            dataOffset = l[1];
            byte[] s = readSector(16);
            if (s != null && isCd001(s)) {
                parseDescriptors();
                return;
            }
        }
        throw new IOException("Not an ISO 9660 image: " + file.getFileName());
    }

    private static boolean isCd001(byte[] s) {
        return s[1] == 'C' && s[2] == 'D' && s[3] == '0' && s[4] == '0' && s[5] == '1';
    }

    private void parseDescriptors() throws IOException {
        boolean havePrimary = false;
        for (long lba = 16; lba < 16 + 64; lba++) {
            byte[] s = readSector(lba);
            if (s == null || !isCd001(s)) break;
            int type = s[0] & 0xff;
            if (type == 255) break;
            boolean isJoliet = type == 2 && s[88] == '%' && s[89] == '/'
                    && (s[90] == '@' || s[90] == 'C' || s[90] == 'E');
            if ((type == 1 && !havePrimary) || isJoliet) {
                rootLba = u32(s, 156 + 2);
                rootLen = u32(s, 156 + 10);
                havePrimary = true;
                joliet = isJoliet;
                if (isJoliet) break;       // prefer Joliet
            }
        }
        if (!havePrimary) throw new IOException("No usable volume descriptor in " + file.getFileName());
    }

    private byte[] readSector(long lba) throws IOException {
        long pos = lba * sectorSize + dataOffset;
        if (pos + 2048 > raf.length()) return null;
        byte[] b = new byte[2048];
        raf.seek(pos);
        raf.readFully(b);
        return b;
    }

    private byte[] readExtent(long lba, long len) throws IOException {
        if (len > Integer.MAX_VALUE - 16) throw new IOException("File too large");
        byte[] out = new byte[(int) len];
        readExtent(lba, (int) len, out);
        return out;
    }

    /** Reads {@code len} bytes of the extent into the start of {@code out}. */
    private void readExtent(long lba, int len, byte[] out) throws IOException {
        if (sectorSize == 2048 && dataOffset == 0) {
            raf.seek(lba * 2048);
            raf.readFully(out, 0, len);
            return;
        }
        int done = 0;
        for (long s = lba; done < len; s++) {
            byte[] sec = readSector(s);
            if (sec == null) throw new IOException("Unexpected end of image");
            int n = (int) Math.min(2048, len - done);
            System.arraycopy(sec, 0, out, done, n);
            done += n;
        }
    }

    @Override public synchronized List<Entry> list() throws IOException {
        if (entries != null) return entries;
        files = new LinkedHashMap<>();
        List<Entry> out = new ArrayList<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        stack.push(new Object[]{rootLba, rootLen, ""});
        while (!stack.isEmpty()) {
            Object[] d = stack.pop();
            long lba = (Long) d[0], len = (Long) d[1];
            String prefix = (String) d[2];
            if (!seen.add(lba)) continue;
            byte[] dir;
            try {
                dir = readExtent(lba, len);
            } catch (IOException e) {
                continue;   // damaged directory: skip it, keep going
            }
            int pos = 0;
            while (pos < dir.length) {
                int rl = dir[pos] & 0xff;
                if (rl == 0) {                       // padding to next sector
                    pos = (pos / 2048 + 1) * 2048;
                    continue;
                }
                if (pos + 33 > dir.length) break;
                long extent = u32(dir, pos + 2);
                long size = u32(dir, pos + 10);
                int flags = dir[pos + 25] & 0xff;
                int nl = dir[pos + 32] & 0xff;
                if (pos + 33 + nl > dir.length) break;
                boolean dotEntry = nl == 1 && (dir[pos + 33] == 0 || dir[pos + 33] == 1);
                if (!dotEntry) {
                    String path = prefix + decode(dir, pos + 33, nl);
                    if ((flags & 2) != 0) {
                        stack.push(new Object[]{extent, size, path + "/"});
                    } else {
                        files.put(path, new long[]{extent, size});
                        out.add(new Entry(path, size));
                    }
                }
                pos += rl;
            }
        }
        entries = out;
        return out;
    }

    private String decode(byte[] b, int off, int len) {
        String n = joliet ? new String(b, off, len, StandardCharsets.UTF_16BE)
                          : new String(b, off, len, StandardCharsets.ISO_8859_1);
        int semi = n.lastIndexOf(';');
        if (semi >= 0) n = n.substring(0, semi);
        if (!joliet && n.endsWith(".")) n = n.substring(0, n.length() - 1);
        return n;
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    @Override public synchronized byte[] read(String path) throws IOException {
        list();
        long[] f = files.get(path);
        if (f == null) throw new IOException("No such file on disc: " + path);
        return readExtent(f[0], f[1]);
    }

    @Override public synchronized void read(String path, Buffer into) throws IOException {
        list();
        long[] f = files.get(path);
        if (f == null) throw new IOException("No such file on disc: " + path);
        if (f[1] > Integer.MAX_VALUE - 16) throw new IOException("File too large");
        into.ensure((int) f[1]);
        readExtent(f[0], (int) f[1], into.data);
        into.length = (int) f[1];
    }

    @Override public synchronized byte[] readPrefix(String path, int max) throws IOException {
        list();
        long[] f = files.get(path);
        if (f == null) throw new IOException("No such file on disc: " + path);
        return readExtent(f[0], Math.min(f[1], max));
    }

    @Override public synchronized long order(String path) {
        try { list(); } catch (IOException e) { return 0; }
        long[] f = files.get(path);
        return f == null ? 0 : f[0];
    }

    @Override public synchronized void close() throws IOException { raf.close(); }
}
