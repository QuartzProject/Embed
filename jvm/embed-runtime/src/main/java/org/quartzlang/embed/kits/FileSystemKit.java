package org.quartzlang.embed.kits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.quartzlang.embed.Kit;
import org.quartzlang.embed.KitContext;
import org.quartzlang.embed.RootedPaths;

/** {@code org.quartzlang.embed.fs}: files under one directory. */
public final class FileSystemKit implements Kit {
    private static final String P = "org.quartzlang.embed.fs.";
    private final RootedPaths paths;

    public FileSystemKit(RootedPaths paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "fs";
    }

    @Override
    public void bind(KitContext k) {
        k.bind(P + "exists", c -> Files.exists(path(c.stringArg(0)), LinkOption.NOFOLLOW_LINKS));
        k.bind(P + "isFile", c -> Files.isRegularFile(path(c.stringArg(0))));
        k.bind(P + "isDirectory", c -> Files.isDirectory(path(c.stringArg(0))));
        k.bind(P + "readText", c -> {
            Path p = path(c.stringArg(0));
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        });
        k.bind(P + "readBytes", c -> {
            Path p = path(c.stringArg(0));
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        });
        k.bind(P + "writeText", c -> write(c.stringArg(0), c.stringArg(1).getBytes(StandardCharsets.UTF_8), false));
        k.bind(P + "writeBytes", c -> write(c.stringArg(0), c.bytesArg(1), false));
        k.bind(P + "appendText", c -> write(c.stringArg(0), c.stringArg(1).getBytes(StandardCharsets.UTF_8), true));
        k.bind(P + "list", c -> {
            Path dir = path(c.stringArg(0));
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> s = Files.list(dir)) {
                return s.map(p -> p.getFileName().toString()).sorted().toList();
            }
        });
        k.bind(P + "walk", c -> {
            Path dir = path(c.stringArg(0));
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        out.add(dir.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            out.sort(null);
            return out;
        });
        k.bind(P + "createDirectories", c -> Files.createDirectories(path(c.stringArg(0))));
        k.bind(P + "delete", c -> delete(path(c.stringArg(0))));
        k.bind(P + "copy", c -> {
            Path to = path(c.stringArg(1));
            createParent(to);
            return Files.copy(path(c.stringArg(0)), to, StandardCopyOption.REPLACE_EXISTING);
        });
        k.bind(P + "move", c -> {
            Path to = path(c.stringArg(1));
            createParent(to);
            return Files.move(path(c.stringArg(0)), to, StandardCopyOption.REPLACE_EXISTING);
        });
        k.bind(P + "size", c -> Files.size(path(c.stringArg(0))));
        k.bind(P + "modified", c -> Files.getLastModifiedTime(path(c.stringArg(0))).toMillis());
    }

    private Path path(String text) throws IOException {
        return paths.resolve(text);
    }

    private Object write(String text, byte[] data, boolean append) throws IOException {
        Path p = path(text);
        createParent(p);
        if (append) {
            Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(p, data);
        }
        return null;
    }

    private static void createParent(Path p) throws IOException {
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
    }

    private boolean delete(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (target.equals(paths.root())) {
            throw new SecurityException("the root itself cannot be deleted");
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }
}
