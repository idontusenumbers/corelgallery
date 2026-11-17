package gallery.catalog;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reader for Corel Gallery's own search database (Gallery/Programs/SEARCH.LST + SEARCH.IDX on disc 1).
 *
 * <pre>
 * SEARCH.LST  "Corel Image List Version 2.0", 32-byte magic, u32 file count, then records:
 *     folder:  u16 len, path ("Photos\USGarden\"), u8 disc number
 *     file:    u16 len, name, u32 byte-offset of its folder record
 *   (a file belongs to the most recent folder; it is identified elsewhere by its own byte offset)
 * SEARCH.IDX  "Corel Image Index Version 2.0"; at 0x40: u16 discs, u32 ?, u32 dictionary offset, u32 entry count.
 *   dictionary: entries  u8 len, keyword, u32 offset of its posting list
 *   posting list:  u16 kind, u32 count, count x u32 LST offsets of the matching files
 * </pre>
 */
public final class CorelIndex implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Per LST file (in list order): disc number and '/'-separated path in the disc's own spelling. */
    public final int[] disc;
    public final String[] path;
    /** Sorted lower-case keywords and, parallel, the sorted LST file numbers each one matches. */
    private final String[] words;
    private final int[][] postings;

    private CorelIndex(int[] disc, String[] path, String[] words, int[][] postings) {
        this.disc = disc;
        this.path = path;
        this.words = words;
        this.postings = postings;
    }

    public int fileCount() { return path.length; }
    public int keywordCount() { return words.length; }

    private static long u32(byte[] d, int o) {
        return (d[o] & 0xffL) | (d[o + 1] & 0xffL) << 8 | (d[o + 2] & 0xffL) << 16 | (d[o + 3] & 0xffL) << 24;
    }

    private static int u16(byte[] d, int o) { return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8; }

    public static boolean looksLikeLst(byte[] d) { return d.length > 40 && startsWith(d, "Corel Image List"); }
    public static boolean looksLikeIdx(byte[] d) { return d.length > 100 && startsWith(d, "Corel Image Index"); }

    private static boolean startsWith(byte[] d, String s) {
        for (int i = 0; i < s.length(); i++) if (d[i] != s.charAt(i)) return false;
        return true;
    }

    public static CorelIndex parse(byte[] lst, byte[] idx) throws IOException {
        if (!looksLikeLst(lst)) throw new IOException("SEARCH.LST is not a Corel Image List");
        if (!looksLikeIdx(idx)) throw new IOException("SEARCH.IDX is not a Corel Image Index");

        // ---- LST
        int expected = (int) Math.min(u32(lst, 0x20), 5_000_000);
        int[] disc = new int[Math.max(expected, 16)];
        String[] path = new String[disc.length];
        int[] offs = new int[disc.length];
        Map<Integer, String> folderPath = new HashMap<>();
        Map<Integer, Integer> folderDisc = new HashMap<>();
        int n = 0, pos = 0x24;
        while (pos + 2 <= lst.length) {
            int len = u16(lst, pos);
            if (pos + 2 + len > lst.length) throw new IOException("Truncated SEARCH.LST at " + pos);
            String s = new String(lst, pos + 2, len, StandardCharsets.ISO_8859_1);
            if (s.endsWith("\\")) {
                if (pos + 3 + len > lst.length) throw new IOException("Truncated SEARCH.LST at " + pos);
                folderPath.put(pos, s.replace('\\', '/'));
                folderDisc.put(pos, lst[pos + 2 + len] & 0xff);
                pos += 3 + len;
            } else {
                if (pos + 6 + len > lst.length) throw new IOException("Truncated SEARCH.LST at " + pos);
                int folder = (int) u32(lst, pos + 2 + len);
                String fp = folderPath.get(folder);
                if (fp == null) throw new IOException("SEARCH.LST file entry at " + pos + " has no folder");
                if (n == disc.length) {
                    disc = Arrays.copyOf(disc, n * 2);
                    path = Arrays.copyOf(path, n * 2);
                    offs = Arrays.copyOf(offs, n * 2);
                }
                disc[n] = folderDisc.get(folder);
                path[n] = fp + s;
                offs[n] = pos;
                n++;
                pos += 6 + len;
            }
        }
        disc = Arrays.copyOf(disc, n);
        path = Arrays.copyOf(path, n);
        offs = Arrays.copyOf(offs, n);

        // ---- IDX
        long dictStart = u32(idx, 0x46);
        long dictCount = u32(idx, 0x4a);
        if (dictStart <= 0 || dictStart >= idx.length || dictCount <= 0 || dictCount > 5_000_000)
            throw new IOException("Unexpected SEARCH.IDX header");
        TreeMap<String, int[]> merged = new TreeMap<>();
        int q = (int) dictStart;
        for (long i = 0; i < dictCount && q < idx.length; i++) {
            int wl = idx[q] & 0xff;
            if (q + 1 + wl + 4 > idx.length) throw new IOException("Truncated SEARCH.IDX dictionary");
            String word = new String(idx, q + 1, wl, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT).trim();
            long ptr = u32(idx, q + 1 + wl);
            q += 1 + wl + 4;
            if (word.isEmpty() || ptr + 6 > idx.length) continue;
            long cnt = u32(idx, (int) ptr + 2);
            if (cnt < 0 || ptr + 6 + 4 * cnt > idx.length) continue;
            int[] files = new int[(int) cnt];
            int k = 0;
            for (int j = 0; j < cnt; j++) {
                int fi = Arrays.binarySearch(offs, (int) u32(idx, (int) ptr + 6 + 4 * j));
                if (fi >= 0) files[k++] = fi;
            }
            files = Arrays.copyOf(files, k);
            Arrays.sort(files);
            merged.merge(word, files, CorelIndex::union);
        }
        String[] words = merged.keySet().toArray(new String[0]);
        int[][] postings = merged.values().toArray(new int[0][]);
        return new CorelIndex(disc, path, words, postings);
    }

    private static int[] union(int[] a, int[] b) {
        int[] r = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length || j < b.length) {
            int v = j >= b.length || (i < a.length && a[i] <= b[j]) ? a[i++] : b[j++];
            if (k == 0 || r[k - 1] != v) r[k++] = v;
        }
        return Arrays.copyOf(r, k);
    }

    // ------------------------------------------------------------------ queries

    /** Files whose keyword equals the term (plural/singular tolerated). Empty if none. */
    public int[] exact(String term) {
        int[] best = lookupWord(term);
        String alt = term.endsWith("s") && term.length() > 3 ? term.substring(0, term.length() - 1)
                : (term.length() > 2 ? term + "s" : null);
        if (alt != null) {
            int[] more = lookupWord(alt);
            if (more.length > 0) best = best.length == 0 ? more : union(best, more);
        }
        return best;
    }

    private int[] lookupWord(String w) {
        int i = Arrays.binarySearch(words, w);
        return i >= 0 ? postings[i] : new int[0];
    }

    /** Files with any keyword that starts with the prefix (and is longer than it). */
    public int[] prefix(String p) {
        int i = Arrays.binarySearch(words, p);
        if (i < 0) i = -i - 1; else i++;
        int[] out = new int[0];
        for (; i < words.length && words[i].startsWith(p); i++) out = union(out, postings[i]);
        return out;
    }

    /** Every keyword that matches the given LST file (for display). */
    public List<String> keywordsOf(int file) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < words.length; i++)
            if (Arrays.binarySearch(postings[i], file) >= 0) out.add(words[i]);
        return out;
    }

    /** Index of the LST entry for (disc, path) with '/' separators, or -1. Case-insensitive. */
    public static String key(int disc, String path) {
        return disc + "|" + path.toLowerCase(Locale.ROOT);
    }
}
