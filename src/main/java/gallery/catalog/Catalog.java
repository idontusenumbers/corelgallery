package gallery.catalog;

import gallery.source.FolderSource;
import gallery.source.IsoSource;
import gallery.source.Source;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Scans sources, persists the file list, and answers keyword searches (using Corel's own index when present). */
public final class Catalog {

    public record SourceRecord(String id, String kind, long mtime, long size, int disc,
                               List<ClipartItem> items,
                               Map<String, Integer> extCounts,
                               List<String> otherFiles,
                               Map<String, List<String>> samples) implements Serializable {}

    /** Which kinds of files a search should return. */
    public enum Filter {
        ALL("All types"), VECTOR("Vector clipart"), IMAGES("Photos & bitmaps"), WEBART("Web art (GIF)");
        private final String label;
        Filter(String l) { label = l; }
        @Override public String toString() { return label; }
        boolean accepts(String ext) {
            return switch (this) {
                case ALL -> true;
                case VECTOR -> ClipartItem.VECTOR.contains(ext);
                case IMAGES -> !ClipartItem.VECTOR.contains(ext);
                case WEBART -> ClipartItem.WEBART.contains(ext);
            };
        }
    }

    private record Indexed(ClipartItem item, String[] name, String[] dirs, int lst) {}

    /** Buffer size for the .ser cache streams; larger than the 8 KiB default since the files run to tens of MB. */
    private static final int IO_BUFFER = 64 * 1024;
    private static final Set<String> DISC_EXTS = Set.of("iso", "img", "bin");
    private static final Pattern SEARCH_FILE = Pattern.compile("(?i)^Gallery/Programs/SEARCH\\.(IDX|LST)$");
    private static final Pattern MEDIA_NO = Pattern.compile("(?i)Media No:\\s*CD\\s*(\\d+)");

    private final Path home;
    private final Map<String, SourceRecord> records = new LinkedHashMap<>();
    private final Map<String, Source> open = new HashMap<>();
    private volatile List<Indexed> index = List.of();
    private volatile CorelIndex corel;
    private volatile int[] lstToIdx = new int[0];
    /** Corel's friendly names: "d:<folder>" from CATALOG.CFG, "f:<folder path>/<file>" from Longname.cfg (all lower case). */
    private volatile Map<String, String> names = new HashMap<>();
    /** GIF web art per source id, in source order. */
    private volatile Map<String, WebArt> webart = new LinkedHashMap<>();

    public Catalog() { this(Path.of(System.getProperty("user.home"), ".corelgallery")); }

    public Catalog(Path home) { this.home = home; }

    public Path homeDir() { return home; }

    // ---------------------------------------------------------------- persistence

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        try {
            Files.createDirectories(home);
            Path f = home.resolve("catalog.ser");
            if (Files.exists(f)) {
                try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(f), IO_BUFFER))) {
                    records.putAll((Map<String, SourceRecord>) in.readObject());
                }
            }
            Path c = home.resolve("corel.ser");
            if (Files.exists(c)) {
                try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(c), IO_BUFFER))) {
                    corel = (CorelIndex) in.readObject();
                }
            }
        } catch (Exception e) {
            records.clear();   // unreadable/old cache: start over
            corel = null;
        }
        loadAux();
        rebuildIndex();
    }

    /** Friendly names and GIF web art: cached in names.ser / webart.ser, rebuilt from the sources if either is missing. */
    @SuppressWarnings("unchecked")
    private void loadAux() {
        Path nf = home.resolve("names.ser"), wf = home.resolve("webart.ser");
        if (Files.exists(nf) && Files.exists(wf)) {
            try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(nf), IO_BUFFER));
                 ObjectInputStream in2 = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(wf), IO_BUFFER))) {
                Map<String, String> n = (Map<String, String>) in.readObject();
                Map<String, WebArt> w = (Map<String, WebArt>) in2.readObject();
                names = n;
                webart = w;
                return;
            } catch (Exception ignored) {
            }
        }
        // first run after an upgrade (or a lost cache): list the sources again
        Map<String, String> m = new HashMap<>();
        Map<String, WebArt> w = new LinkedHashMap<>();
        for (SourceRecord r : records.values()) {
            try {
                Source s = open.get(r.id());
                if (s == null) {
                    s = r.kind().equals("iso") ? new IsoSource(Path.of(r.id())) : new FolderSource(Path.of(r.id()));
                    open.put(r.id(), s);
                }
                List<Source.Entry> all = s.list();
                readNames(s, all, m);
                WebArt wa = buildWebArt(s.id(), all);
                if (wa != null) w.put(r.id(), wa);
            } catch (IOException | RuntimeException ignored) {
            }
        }
        names = m;
        webart = w;
        if (!records.isEmpty()) save();
    }

    private static WebArt buildWebArt(String id, List<Source.Entry> all) {
        WebArt.Builder b = new WebArt.Builder(id);
        for (Source.Entry e : all) if (ClipartItem.WEBART.contains(ClipartItem.extOf(e.path()))) b.add(e.path(), e.size());
        return b.isEmpty() ? null : b.build();
    }

    private void saveAux() {
        try (ObjectOutputStream out = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(home.resolve("names.ser"))))) {
            out.writeObject(new HashMap<>(names));
        } catch (IOException ignored) {
        }
        try (ObjectOutputStream out = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(home.resolve("webart.ser")), IO_BUFFER))) {
            out.writeObject(new LinkedHashMap<>(webart));
        } catch (IOException ignored) {
        }
    }

    /** CATALOG.CFG: "folder,Title" lines.  Longname.cfg: "file,Long name[,numbers]" lines for its own folder. */
    private static void readNames(Source s, List<Source.Entry> all, Map<String, String> out) {
        for (Source.Entry e : all) {
            String path = e.path();
            int sl = path.lastIndexOf('/');
            String file = path.substring(sl + 1).toLowerCase(Locale.ROOT);
            boolean cat = file.equals("catalog.cfg"), lng = file.equals("longname.cfg");
            if ((!cat && !lng) || e.size() > 1 << 20) continue;
            String folder = sl < 0 ? "" : path.substring(0, sl).toLowerCase(Locale.ROOT);
            String text;
            try { text = new String(s.read(path), java.nio.charset.Charset.forName("windows-1252")); }
            catch (IOException ex) { continue; }
            for (String line : text.split("\\r?\\n")) {
                String[] f = line.split(",");
                if (f.length < 2) continue;
                String key = f[0].trim().toLowerCase(Locale.ROOT), val = f[1].trim();
                if (key.isEmpty() || val.isEmpty()) continue;
                out.put(cat ? "d:" + key : "f:" + (folder.isEmpty() ? "" : folder + "/") + key, val);
            }
        }
    }

    /** Corel's long name for a file ("Briefcase" for Brefcse2.tif), or its plain base name. */
    public String displayName(ClipartItem it) {
        Map<String, String> n = names;
        if (!n.isEmpty()) {
            String folder = it.folder().toLowerCase(Locale.ROOT);
            String pre = folder.isEmpty() ? "f:" : "f:" + folder + "/";
            String v = n.get(pre + it.fileName().toLowerCase(Locale.ROOT));
            if (v == null) v = n.get(pre + it.baseName().toLowerCase(Locale.ROOT));
            if (v != null) return v;
        }
        return it.baseName();
    }

    /**
     * Our own table of expanded folder names (folder-names.tsv, built from sample images and Corel's keywords):
     * key, title, and optional extra search keywords (synonyms that shouldn't appear in the title).
     */
    private static final Map<String, String> TABLE = new HashMap<>(), KEYWORDS = new HashMap<>();

    static {
        try (InputStream in = Catalog.class.getResourceAsStream("folder-names.tsv")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] col = line.split("\t", 3);
                    if (col.length < 2 || col[0].isEmpty()) continue;
                    String key = col[0].toLowerCase(Locale.ROOT);
                    TABLE.put(key, col[1].trim());
                    if (col.length == 3 && !col[2].isBlank()) KEYWORDS.put(key, col[2].trim());
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Looks a folder up in one of our tables by full path, then parent/name, then name. */
    private static String lookup(Map<String, String> table, String lower) {
        int s = lower.lastIndexOf('/');
        String v;
        if (s >= 0 && (v = table.get(lower)) != null) return v;
        if (s >= 0) {
            int s2 = lower.lastIndexOf('/', s - 1);
            if ((v = table.get(lower.substring(s2 + 1))) != null) return v;
        }
        return table.get(lower.substring(s + 1));
    }

    /**
     * Friendly title for a folder, given its path (or just its name).  Order: Corel's own CATALOG.CFG,
     * then our table (full path, parent/name, name), then an automatic tidy-up of case and underscores.
     */
    public String folderTitle(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String v = names.get("d:" + lower.substring(lower.lastIndexOf('/') + 1));
        if (v != null) return v;
        if ((v = lookup(TABLE, lower)) != null) return v;
        return prettify(path.substring(path.lastIndexOf('/') + 1));
    }

    /** Space-separated titles (and extra keywords) of a folder and each of its parents ("Clipart Animals Birds"), for searching. */
    private String folderTitles(String folder) {
        StringBuilder t = new StringBuilder();
        for (int end = folder.indexOf('/'); end >= 0; end = folder.indexOf('/', end + 1))
            appendTitle(t, folder.substring(0, end)).append(' ');
        return appendTitle(t, folder).toString();
    }

    /** A folder's title plus any extra keywords our table has for it. */
    private StringBuilder appendTitle(StringBuilder t, String folder) {
        t.append(folderTitle(folder));
        String k = lookup(KEYWORDS, folder.toLowerCase(Locale.ROOT));
        return k == null ? t : t.append(' ').append(k);
    }

    /** "Cal_Sea" stays readable, "AFRICA" becomes "Africa", "Roman1" becomes "Roman 1". */
    static String prettify(String s) {
        String t = s.replace('_', ' ').trim();
        boolean lower = false, upper = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLowerCase(ch)) lower = true; else if (Character.isUpperCase(ch)) upper = true;
        }
        boolean code = t.matches(".*\\d[A-Za-z].*");          // codes like BN13BVL are left alone
        if (!(lower && upper) && !code) {
            StringBuilder sb = new StringBuilder();
            boolean cap = true;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                sb.append(cap ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
                cap = ch == ' ' || ch == '&' || ch == '-' || ch == '/';
            }
            t = sb.toString();
        }
        return t.replaceAll("(?<=\\p{L}{2})(?=\\d+$)", " ");
    }

    /** A file-system-safe base name for exports, based on the long name. */
    public String exportName(ClipartItem it) {
        String n = displayName(it).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return n.isEmpty() ? it.baseName() : n;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(home);
            try (ObjectOutputStream out = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(home.resolve("catalog.ser")), IO_BUFFER))) {
                out.writeObject(new LinkedHashMap<>(records));
            }
            saveAux();
            CorelIndex c = corel;
            if (c != null) {
                try (ObjectOutputStream out = new ObjectOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(home.resolve("corel.ser")), IO_BUFFER))) {
                    out.writeObject(c);
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- sources

    public synchronized boolean isEmpty() { return records.isEmpty(); }
    public synchronized List<String> sourceIds() { return new ArrayList<>(records.keySet()); }
    public int itemCount() { return index.size(); }
    public int webArtCount() { int n = 0; for (WebArt w : webart.values()) n += w.count(); return n; }
    public boolean hasCorelIndex() { return corel != null; }
    public int corelKeywordCount() { CorelIndex c = corel; return c == null ? 0 : c.keywordCount(); }

    private static boolean isDiscImage(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int d = n.lastIndexOf('.');
        return d >= 0 && DISC_EXTS.contains(n.substring(d + 1));
    }

    /** Add a disc image, a folder of disc images, or a plain folder of clipart. Returns items found. */
    public int addPath(Path p, Consumer<String> progress) throws IOException {
        p = p.toAbsolutePath().normalize();
        List<Path> images = new ArrayList<>();
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(Files::isRegularFile).filter(Catalog::isDiscImage).sorted().forEach(images::add);
            }
            if (images.isEmpty()) return scan(new FolderSource(p), "dir", progress);
        } else {
            images.add(p);
        }
        int n = 0;
        for (Path img : images) {
            try {
                n += scan(new IsoSource(img), "iso", progress);
            } catch (IOException e) {
                progress.accept("Skipped " + img.getFileName() + ": " + e.getMessage());
            }
        }
        return n;
    }

    private static int discNumber(Source s, List<Source.Entry> all) {
        for (Source.Entry e : all) {
            if (e.path().equalsIgnoreCase("volinfo.txt") && e.size() < 4096) {
                try {
                    Matcher m = MEDIA_NO.matcher(new String(s.read(e.path()), StandardCharsets.ISO_8859_1));
                    if (m.find()) return Integer.parseInt(m.group(1));
                } catch (IOException | NumberFormatException ignored) {
                }
            }
        }
        Matcher m = Pattern.compile("(\\d+)(?!.*\\d)").matcher(s.displayName());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private int scan(Source s, String kind, Consumer<String> progress) throws IOException {
        progress.accept("Scanning " + s.displayName() + " …");
        List<ClipartItem> items = new ArrayList<>();
        Map<String, Integer> ext = new TreeMap<>();
        Map<String, List<String>> samples = new TreeMap<>();
        List<String> others = new ArrayList<>();
        List<Source.Entry> all = s.list();
        String lstPath = null, idxPath = null;
        for (Source.Entry e : all) {
            String x = ClipartItem.extOf(e.path());
            ext.merge(x.isEmpty() ? "(none)" : x, 1, Integer::sum);
            Matcher m = SEARCH_FILE.matcher(e.path());
            if (m.matches()) { if (m.group(1).equalsIgnoreCase("LST")) lstPath = e.path(); else idxPath = e.path(); }
        }
        for (Source.Entry e : all) {
            String x = ClipartItem.extOf(e.path());
            String key = x.isEmpty() ? "(none)" : x;
            List<String> sm = samples.computeIfAbsent(key, k -> new ArrayList<>());
            if (sm.size() < 3) sm.add(e.path() + "  (" + e.size() + " bytes)");
            if (ClipartItem.isClipart(e.path())) items.add(new ClipartItem(s.id(), e.path(), e.size()));
            // list individually only files whose type is rare: bulk types are summarised by the samples
            else if (ext.get(key) <= 200 && others.size() < 400) others.add(e.path() + "  (" + e.size() + " bytes)");
        }
        items.sort(Comparator.comparing(ClipartItem::path, String.CASE_INSENSITIVE_ORDER));
        int disc = discNumber(s, all);
        CorelIndex found = null;
        if (lstPath != null && idxPath != null) {
            progress.accept("Reading Corel's keyword index from " + s.displayName() + " …");
            try {
                found = CorelIndex.parse(s.read(lstPath), s.read(idxPath));
                progress.accept("Corel index: " + found.keywordCount() + " keywords, " + found.fileCount() + " files");
            } catch (IOException e) {
                progress.accept("Could not read Corel's keyword index: " + e.getMessage());
            }
        }
        Path f = Path.of(s.id());
        long mtime = 0, size = 0;
        if (kind.equals("iso")) { mtime = Files.getLastModifiedTime(f).toMillis(); size = Files.size(f); }
        Map<String, String> found2 = new HashMap<>();
        readNames(s, all, found2);
        WebArt wa = buildWebArt(s.id(), all);
        synchronized (this) {
            Map<String, WebArt> w = new LinkedHashMap<>(webart);
            if (wa != null) w.put(s.id(), wa); else w.remove(s.id());
            webart = w;
            if (!found2.isEmpty()) { Map<String, String> m = new HashMap<>(names); m.putAll(found2); names = m; }
            Source old = open.put(s.id(), s);
            closeQuietly(old);
            if (found != null) corel = found;
            records.put(s.id(), new SourceRecord(s.id(), kind, mtime, size, disc, items, ext, others, samples));
            rebuildIndex();
            save();
        }
        progress.accept(s.displayName() + ": " + items.size() + " clipart files");
        return items.size();
    }

    public void rescanAll(Consumer<String> progress) throws IOException {
        List<SourceRecord> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(records.values()); }
        for (SourceRecord r : snapshot) {
            try {
                scan(r.kind().equals("iso") ? new IsoSource(Path.of(r.id())) : new FolderSource(Path.of(r.id())),
                        r.kind(), progress);
            } catch (IOException e) {
                progress.accept("Could not rescan " + r.id() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Throws away every derived index (file lists, long names, web art, Corel keywords, duplicate groups) and rebuilds
     * them from the sources.  Sources that can no longer be opened keep their old entries.  Blocking.
     */
    public void rebuildAll(Consumer<String> progress) throws IOException {
        synchronized (this) {
            names = new HashMap<>();
            dupes = null;
        }
        try { Files.deleteIfExists(home.resolve("dupes.ser")); } catch (IOException ignored) {}
        rescanAll(progress);
        synchronized (this) { save(); }
    }

    public synchronized void removeSource(String id) {
        closeQuietly(open.remove(id));
        records.remove(id);
        Map<String, WebArt> w = new LinkedHashMap<>(webart);
        w.remove(id);
        webart = w;
        rebuildIndex();
        save();
    }

    private static void closeQuietly(Source s) {
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    private volatile byte[] swi32;

    /**
     * Corel's WI photo codec (swi32.dll) from the user's own disc 1, or from -Dgallery.swi32=path,
     * or ~/.corelgallery/swi32.dll.  It is never redistributed with this program.
     */
    public byte[] wiCodec() throws IOException {
        byte[] c = swi32;
        if (c != null) return c;
        synchronized (this) {
            if (swi32 != null) return swi32;
            String prop = System.getProperty("gallery.swi32");
            Path[] files = {prop == null ? null : Path.of(prop), home.resolve("swi32.dll")};
            for (Path f : files)
                if (f != null && Files.isRegularFile(f)) return swi32 = Files.readAllBytes(f);
            byte[] found = findOnDiscs("programs/swi32.dll");
            if (found != null) {
                swi32 = found;
                try {   // keep a copy so later runs need no disc scan
                    Files.createDirectories(home);
                    Files.write(home.resolve("swi32.dll"), swi32);
                } catch (IOException ignored) {}
                return swi32;
            }
        }
        throw new IOException("Corel's swi32.dll (Gallery/Programs on disc 1) was not found in the added sources; "
                + "add disc 1, or set -Dgallery.swi32=/path/to/swi32.dll");
    }

    /** The first file on any added source whose path ends with the given lower-case suffix, or null. */
    public synchronized byte[] findOnDiscs(String suffix) throws IOException {
        for (String id : new ArrayList<>(records.keySet())) {
            SourceRecord r = records.get(id);
            Source s = open.get(id);
            if (s == null) {
                try {
                    s = r.kind().equals("iso") ? new IsoSource(Path.of(r.id())) : new FolderSource(Path.of(r.id()));
                } catch (IOException e) {
                    continue;   // disc moved or unmounted: try the others
                }
                open.put(id, s);
            }
            for (Source.Entry e : s.list())
                if (e.path().toLowerCase(Locale.ROOT).endsWith(suffix)) return s.read(e.path());
        }
        return null;
    }

    private Source sourceFor(String sourceId) throws IOException {
        synchronized (this) {
            Source s = open.get(sourceId);
            if (s == null) {
                SourceRecord r = records.get(sourceId);
                if (r == null) throw new IOException("Unknown source " + sourceId);
                s = r.kind().equals("iso") ? new IsoSource(Path.of(r.id())) : new FolderSource(Path.of(r.id()));
                open.put(sourceId, s);
            }
            return s;
        }
    }

    public byte[] read(ClipartItem item) throws IOException {
        return sourceFor(item.sourceId()).read(item.path());
    }

    /** Reads an item into a reusable buffer (see {@link Source.Buffer}). */
    public void read(ClipartItem item, Source.Buffer into) throws IOException {
        sourceFor(item.sourceId()).read(item.path(), into);
    }

    byte[] readPrefix(ClipartItem item, int max) throws IOException {
        return sourceFor(item.sourceId()).readPrefix(item.path(), max);
    }

    long orderOf(ClipartItem item) {
        try { return sourceFor(item.sourceId()).order(item.path()); } catch (IOException e) { return 0; }
    }

    // ---------------------------------------------------------------- duplicate previews

    public interface DupProgress { void update(String phase, int done, int total); }

    private volatile Duplicates dupes;

    public boolean duplicatesReady() { return dupes != null; }
    public int duplicateCount() { Duplicates d = dupes; return d == null ? 0 : d.hidden().size(); }

    /** True when {@code it} has the same preview as a better copy that is shown instead. */
    public boolean isHiddenDuplicate(ClipartItem it) { Duplicates d = dupes; return d != null && d.hidden().contains(it); }

    /** The copies hidden in favour of {@code it} (empty if none). */
    public List<ClipartItem> duplicatesOf(ClipartItem it) {
        Duplicates d = dupes;
        List<ClipartItem> l = d == null ? null : d.groups.get(it);
        return l == null ? List.of() : l;
    }

    /** Items with a hidden duplicate removed (the same list when nothing applies). */
    public List<ClipartItem> withoutDuplicates(List<ClipartItem> items) {
        Duplicates d = dupes;
        if (d == null || items.isEmpty()) return items;
        Set<ClipartItem> h = d.hidden();
        if (h.isEmpty()) return items;
        List<ClipartItem> out = new ArrayList<>(items.size());
        for (ClipartItem it : items) if (!h.contains(it)) out.add(it);
        return out;
    }

    private String dupSignature() {
        List<String> l = new ArrayList<>();
        synchronized (this) { for (SourceRecord r : records.values()) l.add(r.id() + "|" + r.mtime() + "|" + r.size()); }
        Collections.sort(l);
        return Duplicates.VERSION + "#" + index.size() + "#" + String.join(";", l);
    }

    /** Loads the cached duplicate groups, or works them out (reads the preview of every CCX file). Blocking. */
    public void computeDuplicates(DupProgress progress) {
        String sig = dupSignature();
        Path f = home.resolve("dupes.ser");
        if (Files.exists(f)) {
            try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(f), IO_BUFFER))) {
                Duplicates d = (Duplicates) in.readObject();
                if (d.version == Duplicates.VERSION && d.signature.equals(sig)) { dupes = d; return; }
            } catch (Exception ignored) {
            }
        }
        List<ClipartItem> ccx = new ArrayList<>();
        for (Indexed ix : index) if (ix.item().ext().equals("ccx")) ccx.add(ix.item());
        Duplicates d = Duplicates.compute(this, ccx, sig, progress::update);
        try {
            Files.createDirectories(home);
            try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(f), IO_BUFFER))) {
                out.writeObject(d);
            }
        } catch (IOException ignored) {
        }
        dupes = d;
    }

    // ---------------------------------------------------------------- index & search

    private static String[] tokens(String s) {
        List<String> out = new ArrayList<>();
        // split camelCase first ("GetWell" -> "Get Well"), then on punctuation and letter/digit boundaries
        String spaced = s.replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ");
        for (String t : spaced.toLowerCase(Locale.ROOT)
                .split("[^\\p{L}\\p{N}]+|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")) {
            if (t.length() > 0 && !t.chars().allMatch(Character::isDigit)) out.add(t);
        }
        // keep camelCase words whole too, so "getwell" finds "GetWell"
        for (String raw : s.split("[^\\p{L}\\p{N}]+")) {
            if (raw.matches(".*\\p{Ll}\\p{Lu}.*")) out.add(raw.toLowerCase(Locale.ROOT));
        }
        return out.toArray(new String[0]);
    }

    /** Optional ~/.corelgallery/keywords.tsv:  path-or-filename <TAB> extra keywords. */
    private Map<String, String> loadExtraKeywords() {
        Map<String, String> m = new HashMap<>();
        Path f = home.resolve("keywords.tsv");
        if (!Files.exists(f)) return m;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t > 0 && !line.startsWith("#"))
                    m.merge(line.substring(0, t).trim().toLowerCase(Locale.ROOT), line.substring(t + 1), (a, b) -> a + " " + b);
            }
        } catch (IOException ignored) {}
        return m;
    }

    /** Setup wizards, redistributables and the photo-editor install on disc 1 are not clipart. */
    private static boolean isInstallerPath(String path) {
        int s = path.indexOf('/');
        if (s < 0) return false;
        String top = path.substring(0, s).toLowerCase(Locale.ROOT);
        return top.equals("setup") || top.equals("redist") || top.equals("webspice") || top.equals("photohse");
    }

    private record Cand(ClipartItem it, int disc) {}

    /** Same folder and base name, whatever the extension or disc. */
    private static String dupKey(ClipartItem it) {
        String p = it.path();
        int d = p.lastIndexOf('.');
        return (d > p.lastIndexOf('/') ? p.substring(0, d) : p).toLowerCase(Locale.ROOT);
    }

    /** Lower is preferred when two files are the same picture. */
    private static int rank(String ext) {
        if (ClipartItem.VECTOR.contains(ext)) return 0;
        return switch (ext) { case "wi" -> 1; case "tif", "tiff" -> 2; case "png" -> 3; case "jpg", "jpeg" -> 4; case "bmp" -> 5; default -> 6; };
    }

    private void rebuildIndex() {
        dupes = null;
        Map<String, String> extra = loadExtraKeywords();
        CorelIndex ci = corel;
        // (disc, lower-case path) -> LST file number
        Map<String, Integer> lstKeys = null;
        if (ci != null) {
            lstKeys = new HashMap<>(ci.fileCount() * 2);
            for (int i = 0; i < ci.fileCount(); i++) lstKeys.put(CorelIndex.key(ci.disc[i], ci.path[i]), i);
        }
        // Some images exist twice (a .tif and a .bmp, or the same .jpg on several discs): keep the best copy.
        Map<String, Cand> best = new HashMap<>();
        Map<String, List<Cand>> dups = new HashMap<>();
        for (SourceRecord r : records.values()) {
            for (ClipartItem it : r.items()) {
                if (!ClipartItem.isClipart(it.path()) || isInstallerPath(it.path())) continue;
                String key = dupKey(it);
                Cand c = new Cand(it, r.disc()), prev = best.get(key);
                if (prev == null) { best.put(key, c); continue; }
                dups.computeIfAbsent(key, k -> { List<Cand> l = new ArrayList<>(); l.add(prev); return l; }).add(c);
                if (rank(it.ext()) < rank(prev.it().ext())) best.put(key, c);
            }
        }

        List<Indexed> idx = new ArrayList<>();
        Map<String, String[]> dirTokens = new HashMap<>();     // thousands of files share a folder
        int[] map = new int[ci == null ? 0 : ci.fileCount()];
        Arrays.fill(map, -1);
        for (SourceRecord r : records.values()) {
            for (ClipartItem it : r.items()) {
                if (!ClipartItem.isClipart(it.path()) || isInstallerPath(it.path())) continue;
                String key = dupKey(it);
                if (best.get(key).it() != it) continue;
                String more = extra.getOrDefault(it.path().toLowerCase(Locale.ROOT),
                        extra.getOrDefault(it.fileName().toLowerCase(Locale.ROOT), ""));
                String[] dirs = dirTokens.computeIfAbsent(it.folder(), fo -> {
                    String t = fo + " " + folderTitles(fo);
                    return tokens(t);
                });
                int lst = -1;
                if (lstKeys != null) {
                    // Corel's keywords may be filed under any of the copies
                    for (Cand cd : dups.getOrDefault(key, List.of(new Cand(it, r.disc())))) {
                        if (cd.disc() <= 0) continue;
                        Integer li = lstKeys.get(CorelIndex.key(cd.disc(), cd.it().path()));
                        if (li != null) { if (lst < 0) lst = li; map[li] = idx.size(); }
                    }
                }
                idx.add(new Indexed(it, tokens(it.baseName() + " " + displayName(it) + " " + more), dirs, lst));
            }
        }
        index = idx;
        lstToIdx = map;
    }

    private static boolean hit(String[] toks, String term) {
        String singular = term.length() > 3 && term.endsWith("s") ? term.substring(0, term.length() - 1) : null;
        for (String t : toks) {
            if (t.startsWith(term) || (singular != null && t.startsWith(singular))) return true;
            // 8.3 truncation: folder "Christma" should match the search "christmas"
            if (t.length() == 8 && term.length() > 8 && term.startsWith(t)) return true;
        }
        return false;
    }

    public List<ClipartItem> search(String query) { return search(query, Filter.ALL); }

    /**
     * Space-separated terms are ANDed. Each term matches Corel's keyword index (exact word or word prefix, plurals
     * tolerated) or the words of the file name / folder names. "-word" excludes; "ext:ccx" filters by type.
     */
    public List<ClipartItem> search(String query, Filter filter) { return search(query, filter, null); }

    /** A folder in the category tree. {@code prefix} is the '/'-terminated path shared by all its items. */
    public static final class Category {
        public final String name, prefix;
        public int count;
        public final List<Category> children = new ArrayList<>();
        Category(String name, String prefix) { this.name = name; this.prefix = prefix; }
    }

    /** Folder tree of everything the filter accepts; folders merge across discs, case-insensitively. */
    public Category categories(Filter filter) { return categories(filter, false); }

    /** As {@link #categories(Filter)}, optionally leaving out files hidden as duplicate previews. */
    public Category categories(Filter filter, boolean hideDuplicates) {
        Duplicates dd = hideDuplicates ? dupes : null;
        Set<ClipartItem> hid = dd == null ? Set.of() : dd.hidden();
        Map<String, Integer> counts = new HashMap<>();   // folder path -> files
        if (filter == Filter.WEBART) {
            for (WebArt w : webart.values()) {
                int[] per = new int[w.folders.length];
                for (int f : w.folderOf) per[f]++;
                for (int i = 0; i < per.length; i++) counts.merge(w.folders[i], per[i], Integer::sum);
            }
        } else {
            for (Indexed ix : index) {
                ClipartItem it = ix.item();
                if (filter.accepts(it.ext()) && !hid.contains(it)) counts.merge(it.folder(), 1, Integer::sum);
            }
        }
        return buildTree(counts);
    }

    /** Folder tree containing only the folders that hold {@code items} (counts are the matches). */
    public Category categories(List<ClipartItem> items) {
        Map<String, Integer> counts = new HashMap<>();
        for (ClipartItem it : items) counts.merge(it.folder(), 1, Integer::sum);
        return buildTree(counts);
    }

    private Category buildTree(Map<String, Integer> counts) {
        Category root = new Category("All items", "");
        Map<String, Category> byKey = new HashMap<>();
        byKey.put("", root);
        for (Map.Entry<String, Integer> en : counts.entrySet()) {
            String folder = en.getKey();
            int n = en.getValue();
            root.count += n;
            int from = 0;
            Category parent = root;
            while (from < folder.length()) {
                int e = folder.indexOf('/', from);
                if (e < 0) e = folder.length();
                String key = folder.substring(0, e).toLowerCase(Locale.ROOT);
                Category cat = byKey.get(key);
                if (cat == null) {
                    cat = new Category(folderTitle(folder.substring(0, e)), folder.substring(0, e) + "/");
                    byKey.put(key, cat);
                    parent.children.add(cat);
                }
                cat.count += n;
                parent = cat;
                from = e + 1;
            }
        }
        sortTree(root);
        return root;
    }

    /** Results backed by index arrays: a million-entry list costs a few MB and builds items on demand. */
    private static final class LazyItems extends AbstractList<ClipartItem> implements RandomAccess {
        private final WebArt[] src;
        private final int[] who, idx;
        private final int n;
        LazyItems(WebArt[] src, int[] who, int[] idx, int n) { this.src = src; this.who = who; this.idx = idx; this.n = n; }
        @Override public ClipartItem get(int i) { return src[who[i]].item(idx[i]); }
        @Override public int size() { return n; }
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        int m = needle.length();
        for (int i = hay.length() - m; i >= 0; i--) if (hay.regionMatches(true, i, needle, 0, m)) return true;
        return false;
    }

    private List<ClipartItem> searchWebArt(String query, String category) {
        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (t.isEmpty() || t.startsWith("ext:")) continue;
            if (t.startsWith("-") && t.length() > 1) exc.add(t.substring(1)); else inc.add(t);
        }
        WebArt[] src = webart.values().toArray(new WebArt[0]);
        int cap = 1024, n = 0;
        int[] who = new int[cap], idx = new int[cap];
        for (int s = 0; s < src.length; s++) {
            WebArt w = src[s];
            String[] fl = new String[w.folders.length];
            for (int f = 0; f < fl.length; f++) fl[f] = (w.folders[f] + " " + folderTitles(w.folders[f])).toLowerCase(Locale.ROOT);
            boolean[] ok = new boolean[fl.length];
            boolean[][] incIn = new boolean[inc.size()][fl.length], excIn = new boolean[exc.size()][fl.length];
            for (int f = 0; f < fl.length; f++) {
                ok[f] = category == null || category.isEmpty()
                        || (w.folders[f] + "/").regionMatches(true, 0, category, 0, category.length());
                for (int t = 0; t < inc.size(); t++) incIn[t][f] = fl[f].contains(inc.get(t));
                for (int t = 0; t < exc.size(); t++) excIn[t][f] = fl[f].contains(exc.get(t));
            }
            outer:
            for (int i = 0; i < w.names.length; i++) {
                int f = w.folderOf[i];
                if (!ok[f]) continue;
                String name = w.names[i];
                for (int t = 0; t < inc.size(); t++)
                    if (!incIn[t][f] && !containsIgnoreCase(name, inc.get(t))) continue outer;
                for (int t = 0; t < exc.size(); t++)
                    if (excIn[t][f] || containsIgnoreCase(name, exc.get(t))) continue outer;
                if (n == cap) { cap *= 2; who = Arrays.copyOf(who, cap); idx = Arrays.copyOf(idx, cap); }
                who[n] = s; idx[n] = i; n++;
            }
        }
        return new LazyItems(src, who, idx, n);
    }

    private static void sortTree(Category c) {
        c.children.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (Category k : c.children) sortTree(k);
    }

    /** Like {@link #search(String, Filter)}, restricted to one folder (null or "" = everything). */
    public List<ClipartItem> search(String query, Filter filter, String category) {
        if (filter == Filter.WEBART) return searchWebArt(query, category);
        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
        String ext = null;
        for (String t : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.startsWith("ext:")) ext = t.substring(4);
            else if (t.startsWith("-") && t.length() > 1) exc.add(t.substring(1));
            else inc.add(t);
        }
        List<Indexed> idx = index;
        CorelIndex ci = corel;
        int[] l2i = lstToIdx;
        int n = idx.size();

        // per-term score from the keyword database: 6 exact keyword, 4 keyword prefix
        int[][] kw = new int[inc.size()][];
        for (int t = 0; t < inc.size(); t++) {
            if (ci == null) break;
            String term = inc.get(t);
            int[] sc = new int[n];
            boolean any = false;
            for (int f : ci.exact(term)) { int i = f < l2i.length ? l2i[f] : -1; if (i >= 0) { sc[i] = 6; any = true; } }
            if (term.length() >= 3)
                for (int f : ci.prefix(term)) { int i = f < l2i.length ? l2i[f] : -1; if (i >= 0 && sc[i] == 0) { sc[i] = 4; any = true; } }
            kw[t] = any ? sc : null;
        }
        // exclusions via keywords too
        boolean[][] excKw = new boolean[exc.size()][];
        for (int t = 0; t < exc.size(); t++) {
            if (ci == null) break;
            boolean[] b = new boolean[n];
            boolean any = false;
            for (int f : ci.exact(exc.get(t))) { int i = f < l2i.length ? l2i[f] : -1; if (i >= 0) { b[i] = true; any = true; } }
            excKw[t] = any ? b : null;
        }

        record Scored(ClipartItem item, int score) {}
        List<Scored> hits = new ArrayList<>();
        outer:
        for (int i = 0; i < n; i++) {
            Indexed ix = idx.get(i);
            if (ext != null && !ix.item().ext().equals(ext)) continue;
            if (!filter.accepts(ix.item().ext())) continue;
            if (category != null && !category.isEmpty()
                    && !ix.item().path().regionMatches(true, 0, category, 0, category.length())) continue;
            int score = 0;
            for (int t = 0; t < inc.size(); t++) {
                String term = inc.get(t);
                int s = kw[t] != null ? kw[t][i] : 0;
                if (hit(ix.name(), term)) s = Math.max(s, 3) + (s > 0 ? 1 : 0);
                else if (s == 0 && hit(ix.dirs(), term)) s = 1;
                if (s == 0) continue outer;
                score += s;
            }
            for (int t = 0; t < exc.size(); t++) {
                if (hit(ix.name(), exc.get(t)) || hit(ix.dirs(), exc.get(t)) || (excKw[t] != null && excKw[t][i])) continue outer;
            }
            hits.add(new Scored(ix.item(), score));
        }
        hits.sort(Comparator.<Scored>comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.item().path(), String.CASE_INSENSITIVE_ORDER));
        List<ClipartItem> out = new ArrayList<>(hits.size());
        for (Scored s : hits) out.add(s.item());
        return out;
    }

    /** Corel's own keywords for an item (empty if the keyword database has none, or isn't loaded). */
    /** For each folder: its most common Corel keywords (across the files in it). Used to work out what a short folder name means. */
    public Map<String, List<String>> topKeywordsByFolder(int n) {
        CorelIndex ci = corel;
        Map<String, List<String>> out = new HashMap<>();
        if (ci == null) return out;
        Map<String, Map<String, Integer>> acc = new HashMap<>();
        for (Indexed ix : index) {
            if (ix.lst() < 0) continue;
            Map<String, Integer> m = acc.computeIfAbsent(ix.item().folder(), k -> new HashMap<>());
            for (String k : ci.keywordsOf(ix.lst())) m.merge(k, 1, Integer::sum);
        }
        for (var e : acc.entrySet()) {
            List<Map.Entry<String, Integer>> l = new ArrayList<>(e.getValue().entrySet());
            l.sort((a, b) -> b.getValue() - a.getValue());
            List<String> top = new ArrayList<>();
            for (int i = 0; i < Math.min(n, l.size()); i++) top.add(l.get(i).getKey() + ":" + l.get(i).getValue());
            out.put(e.getKey(), top);
        }
        return out;
    }

    public List<String> keywordsOf(ClipartItem item) {
        CorelIndex ci = corel;
        if (ci == null) return List.of();
        for (Indexed ix : index) {
            if (ix.item().equals(item)) return ix.lst() < 0 ? List.of() : ci.keywordsOf(ix.lst());
        }
        return List.of();
    }

    // ---------------------------------------------------------------- report

    /** Per-disc file-type census and the non-clipart files. */
    public synchronized String report() {
        StringBuilder sb = new StringBuilder();
        CorelIndex ci = corel;
        if (ci != null)
            sb.append("Corel keyword database: ").append(ci.keywordCount()).append(" keywords for ")
                    .append(ci.fileCount()).append(" files\n\n");
        for (SourceRecord r : records.values()) {
            sb.append("== ").append(r.id()).append(r.disc() > 0 ? "   (disc " + r.disc() + ")" : "").append("\n");
            sb.append("   clipart files: ").append(r.items().size()).append("\n");
            sb.append("   by extension: ").append(r.extCounts()).append("\n");
            sb.append("   examples per extension:\n");
            for (var en : r.samples().entrySet())
                for (String ex : en.getValue()) sb.append("      [").append(en.getKey()).append("] ").append(ex).append("\n");
            if (!r.otherFiles().isEmpty()) {
                sb.append("   other non-clipart files, rare types only (first ").append(r.otherFiles().size()).append("):\n");
                for (String o : r.otherFiles()) sb.append("      ").append(o).append("\n");
            }
            sb.append("\n");
        }
        return sb.length() == 0 ? "No sources added yet." : sb.toString();
    }
}
