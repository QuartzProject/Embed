package org.quartzlang.embed.kits;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
import org.quartzlang.jvm.HostCall;
import org.quartzlang.jvm.Result;

/** {@code org.quartzlang.embed.fs}: files inside the granted paths; failures are {@code Err} values, not traps. */
public final class FileSystemKit implements Kit {
    private static final String P = "org.quartzlang.embed.fs.";
    private final RootedPaths paths;

    /** One operation on resolved paths; what it throws becomes the error. */
    @FunctionalInterface
    private interface Operation {
        Object run(HostCall call) throws IOException;
    }

    public FileSystemKit(RootedPaths paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "fs";
    }

    @Override
    public void bind(KitContext k) {
        k.bind(P + "exists", c -> test(c, p -> Files.exists(p, LinkOption.NOFOLLOW_LINKS)));
        k.bind(P + "isFile", c -> test(c, Files::isRegularFile));
        k.bind(P + "isDirectory", c -> test(c, Files::isDirectory));
        bind(k, "readText", c -> {
            byte[] bytes = Files.readAllBytes(path(c, 0));
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new IOException(c.stringArg(0) + " is not UTF-8 text");
            }
        });
        bind(k, "readBytes", c -> Files.readAllBytes(path(c, 0)));
        bind(k, "writeText", c -> write(c, c.stringArg(1).getBytes(StandardCharsets.UTF_8), false));
        bind(k, "writeBytes", c -> write(c, c.bytesArg(1), false));
        bind(k, "appendText", c -> write(c, c.stringArg(1).getBytes(StandardCharsets.UTF_8), true));
        bind(k, "list", c -> {
            try (Stream<Path> s = Files.list(path(c, 0))) {
                return s.map(p -> p.getFileName().toString()).sorted().toList();
            }
        });
        bind(k, "walk", c -> walk(path(c, 0)));
        bind(k, "createDirectories", c -> {
            Path dir = path(c, 0);
            boolean existed = Files.isDirectory(dir);
            Files.createDirectories(dir);
            return !existed;
        });
        bind(k, "delete", c -> delete(path(c, 0)));
        bind(k, "copy", c -> {
            Path from = path(c, 0);
            Path to = path(c, 1);
            createParent(to);
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            return true;
        });
        bind(k, "move", c -> {
            Path from = path(c, 0);
            Path to = path(c, 1);
            createParent(to);
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return true;
        });
        bind(k, "size", c -> Files.size(path(c, 0)));
        bind(k, "modified", c -> Files.getLastModifiedTime(path(c, 0)).toMillis());
    }

    private void bind(KitContext k, String name, Operation operation) {
        k.bind(P + name, c -> {
            try {
                return Result.ok(operation.run(c));
            } catch (NoSuchFileException e) {
                return Result.err(c.stringArg(0) + ": no such file or directory");
            } catch (IOException | SecurityException e) {
                return Result.err(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private boolean test(HostCall c, java.util.function.Predicate<Path> test) {
        try {
            return test.test(path(c, 0));
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private Path path(HostCall c, int index) throws IOException {
        return paths.resolve(c.stringArg(index));
    }

    private long write(HostCall c, byte[] data, boolean append) throws IOException {
        Path p = path(c, 0);
        createParent(p);
        if (append) {
            Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(p, data);
        }
        return data.length;
    }

    private static void createParent(Path p) throws IOException {
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
    }

    private static List<String> walk(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException(dir.getFileName() + " is not a directory");
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
    }

    private boolean delete(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (paths.roots().contains(target)) {
            throw new SecurityException("a granted path itself cannot be deleted");
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
