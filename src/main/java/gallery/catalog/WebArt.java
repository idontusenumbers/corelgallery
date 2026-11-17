package gallery.catalog;

import java.io.Serializable;
import java.util.*;

/**
 * The GIF "web art" of one source (buttons, bullets, rules, backgrounds...).  There are ~830,000 of them,
 * so they are kept in this compact form (shared folder table, no per-file token arrays) instead of the main index.
 */
final class WebArt implements Serializable {
    private static final long serialVersionUID = 1L;

    final String sourceId;
    final String[] folders;     // distinct folder paths
    final int[] folderOf;       // per file: index into folders
    final String[] names;       // per file: file name
    final int[] sizes;
    private transient String[] folderLower;

    private WebArt(String sourceId, String[] folders, int[] folderOf, String[] names, int[] sizes) {
        this.sourceId = sourceId;
        this.folders = folders;
        this.folderOf = folderOf;
        this.names = names;
        this.sizes = sizes;
    }

    int count() { return names.length; }

    String path(int i) {
        String f = folders[folderOf[i]];
        return f.isEmpty() ? names[i] : f + "/" + names[i];
    }

    ClipartItem item(int i) { return new ClipartItem(sourceId, path(i), sizes[i]); }

    String[] folderLower() {
        String[] l = folderLower;
        if (l == null) {
            l = new String[folders.length];
            for (int i = 0; i < l.length; i++) l[i] = folders[i].toLowerCase(Locale.ROOT);
            folderLower = l;
        }
        return l;
    }

    static final class Builder {
        private final String sourceId;
        private final Map<String, Integer> folderIdx = new HashMap<>();
        private final List<String> folderList = new ArrayList<>();
        private int[] fo = new int[1024], sz = new int[1024];
        private String[] nm = new String[1024];
        private int n;

        Builder(String sourceId) { this.sourceId = sourceId; }

        void add(String path, long size) {
            int s = path.lastIndexOf('/');
            String folder = s < 0 ? "" : path.substring(0, s);
            Integer fi = folderIdx.get(folder);
            if (fi == null) { fi = folderList.size(); folderIdx.put(folder, fi); folderList.add(folder); }
            if (n == nm.length) {
                int c = n * 2;
                fo = Arrays.copyOf(fo, c); sz = Arrays.copyOf(sz, c); nm = Arrays.copyOf(nm, c);
            }
            fo[n] = fi;
            nm[n] = path.substring(s + 1);
            sz[n] = (int) Math.min(size, Integer.MAX_VALUE);
            n++;
        }

        boolean isEmpty() { return n == 0; }

        WebArt build() {
            String[] folders = folderList.toArray(new String[0]);
            // sort folders case-insensitively, then files within a folder
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            String[] low = new String[folders.length];
            for (int i = 0; i < low.length; i++) low[i] = folders[i].toLowerCase(Locale.ROOT);
            Arrays.sort(order, (a, b) -> {
                if (fo[a] != fo[b]) {
                    int c = low[fo[a]].compareTo(low[fo[b]]);
                    if (c != 0) return c;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(nm[a], nm[b]);
            });
            int[] f2 = new int[n], s2 = new int[n];
            String[] n2 = new String[n];
            for (int i = 0; i < n; i++) { int o = order[i]; f2[i] = fo[o]; n2[i] = nm[o]; s2[i] = sz[o]; }
            return new WebArt(sourceId, folders, f2, n2, s2);
        }
    }
}
