package org.quartzlang.embed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolves the '/'-separated paths Quartz code passes inside one directory, refusing any way out of it. */
public final class RootedPaths {
    private final Path root;
    private final Path realRoot;

    public RootedPaths(Path root) {
        try {
            Files.createDirectories(root);
            this.root = root.toAbsolutePath().normalize();
            this.realRoot = this.root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot use " + root + " as a root", e);
        }
    }

    public Path root() {
        return root;
    }

    /** {@code path} inside the root; throws SecurityException for absolute paths, '..' or links that leave it. */
    public Path resolve(String path) throws IOException {
        if (path.indexOf('\0') >= 0 || path.startsWith("/") || path.startsWith("\\") || path.contains(":")) {
            throw new SecurityException("'" + path + "' is not a relative path");
        }
        Path p = root.resolve(path.replace('\\', '/')).normalize();
        if (!p.startsWith(root)) {
            throw new SecurityException("'" + path + "' leaves the directory");
        }
        Path existing = p;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null && !existing.toRealPath().startsWith(realRoot)) {
            throw new SecurityException("'" + path + "' leaves the directory through a link");
        }
        return p;
    }

    /** {@code p} relative to the root, with '/' separators. */
    public String relative(Path p) {
        StringBuilder out = new StringBuilder();
        for (Path part : root.relativize(p)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }
}
