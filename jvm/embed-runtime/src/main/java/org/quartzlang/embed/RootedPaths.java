package org.quartzlang.embed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the '/'-separated paths Quartz code passes inside the granted paths, refusing any way out of them. The
 * first root is a directory where relative paths start; the others may be single files or directories, and are
 * reached by absolute paths or by relative ones that lead into them.
 */
public final class RootedPaths {
    private final List<Path> roots = new ArrayList<>();
    private final List<Path> realRoots = new ArrayList<>();

    public RootedPaths(Path root) {
        this(root, List.of());
    }

    public RootedPaths(Path root, List<Path> more) {
        try {
            Files.createDirectories(root);
            add(root);
            for (Path p : more) {
                add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot use " + root + " as a root", e);
        }
    }

    private void add(Path p) throws IOException {
        Path absolute = p.toAbsolutePath().normalize();
        roots.add(absolute);
        realRoots.add(real(absolute));
    }

    /** The directory relative paths start at. */
    public Path root() {
        return roots.get(0);
    }

    public List<Path> roots() {
        return List.copyOf(roots);
    }

    /** {@code path} inside a granted path; throws SecurityException naming why it is not. */
    public Path resolve(String path) throws IOException {
        if (path.indexOf('\0') >= 0) {
            throw new SecurityException("'" + path + "' is not a path");
        }
        String text = path.replace('\\', '/');
        Path given = Path.of(text);
        if (!given.isAbsolute() && (text.startsWith("/") || text.contains(":"))) {
            throw new SecurityException("'" + path + "' is not a relative path");
        }
        Path p = (given.isAbsolute() ? given : root().resolve(text)).normalize();
        for (int i = 0; i < roots.size(); ++i) {
            if (!p.startsWith(roots.get(i))) {
                continue;
            }
            if (!real(p).startsWith(realRoots.get(i))) {
                throw new SecurityException("'" + path + "' leaves the granted paths through a link");
            }
            return p;
        }
        throw new SecurityException("'" + path + "' is outside the granted paths");
    }

    /** The real path of {@code p}: links resolved up to its deepest existing ancestor, the rest appended. */
    private static Path real(Path p) throws IOException {
        Path existing = p;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return p;
        }
        return existing.toRealPath().resolve(existing.relativize(p));
    }

    /** {@code p} relative to the first root, with '/' separators, or absolute when it lies elsewhere. */
    public String relative(Path p) {
        if (!p.startsWith(root())) {
            return p.toString().replace('\\', '/');
        }
        StringBuilder out = new StringBuilder();
        for (Path part : root().relativize(p)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }
}
