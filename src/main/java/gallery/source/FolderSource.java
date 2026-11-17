package gallery.source;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** A plain directory (e.g. a mounted or extracted disc). */
public final class FolderSource implements Source {
    private final Path root;

    public FolderSource(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override public String id() { return root.toString(); }

    @Override public String displayName() {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    @Override public List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                out.add(new Entry(root.relativize(p).toString().replace('\\', '/'), Files.size(p)));
            }
        }
        return out;
    }

    @Override public byte[] read(String path) throws IOException {
        return Files.readAllBytes(root.resolve(path));
    }

    @Override public byte[] readPrefix(String path, int max) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(root.resolve(path))) {
            return in.readNBytes(max);
        }
    }

    @Override public void close() {}
}
