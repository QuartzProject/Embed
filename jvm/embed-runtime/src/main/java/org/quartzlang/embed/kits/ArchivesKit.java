package org.quartzlang.embed.kits;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.quartzlang.embed.Kit;
import org.quartzlang.embed.KitContext;
import org.quartzlang.embed.RootedPaths;
import org.quartzlang.jvm.HostCall;
import org.quartzlang.jvm.Result;

/** {@code org.quartzlang.embed.archives}: zip, jar and tar archives inside one directory. */
public final class ArchivesKit implements Kit {
    private static final String P = "org.quartzlang.embed.archives.";
    private final RootedPaths paths;

    public ArchivesKit(RootedPaths paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "archives";
    }

    @Override
    public void bind(KitContext k) {
        bind(k, "entries", c -> {
            try (ZipFile zip = new ZipFile(paths.resolve(c.stringArg(0)).toFile())) {
                return zip.stream().filter(e -> !e.isDirectory()).map(ZipEntry::getName).toList();
            }
        });
        bind(k, "unzip", c -> unzip(paths.resolve(c.stringArg(0)), paths.resolve(c.stringArg(1))));
        bind(k, "untar", c -> untar(paths.resolve(c.stringArg(0)), paths.resolve(c.stringArg(1))));
        bind(k, "zip", c -> zip(paths.resolve(c.stringArg(0)), paths.resolve(c.stringArg(1))));
    }

    /** One operation; what it throws becomes the error. */
    @FunctionalInterface
    private interface Operation {
        Object run(HostCall call) throws IOException;
    }

    private static void bind(KitContext k, String name, Operation operation) {
        k.bind(P + name, c -> {
            try {
                return Result.ok(operation.run(c));
            } catch (IOException | SecurityException e) {
                return Result.err(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private long unzip(Path archive, Path target) throws IOException {
        long written = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                Path out = inside(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                ++written;
            }
        }
        return written;
    }

    private long untar(Path archive, Path target) throws IOException {
        long written = 0;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive))) {
            raw.mark(2);
            boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b;
            raw.reset();
            InputStream in = gzip ? new GZIPInputStream(raw) : raw;
            byte[] header = new byte[512];
            String longName = null;
            while (in.readNBytes(header, 0, 512) == 512 && header[0] != 0) {
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                String name = longName != null ? longName : tarName(header);
                longName = null;
                long padded = (size + 511) / 512 * 512;
                if (type == 'L') {
                    longName = new String(in.readNBytes((int) size), StandardCharsets.UTF_8).replace("\0", "");
                    in.skipNBytes(padded - size);
                    continue;
                }
                if (type == '0' || type == '\0') {
                    Path out = inside(target, name);
                    Files.createDirectories(out.getParent());
                    try (OutputStream o = Files.newOutputStream(out)) {
                        copy(in, o, size);
                    }
                    in.skipNBytes(padded - size);
                    ++written;
                } else {
                    if (type == '5') {
                        Files.createDirectories(inside(target, name));
                    }
                    in.skipNBytes(padded);
                }
            }
        }
        return written;
    }

    private long zip(Path directory, Path archive) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(directory)) {
            files = new ArrayList<>(s.filter(Files::isRegularFile).filter(p -> !p.equals(archive)).sorted().toList());
        }
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Path file : files) {
                out.putNextEntry(new ZipEntry(directory.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return files.size();
    }

    private Path inside(Path target, String entry) throws IOException {
        Path out = target.resolve(entry).normalize();
        if (!out.startsWith(target) || entry.startsWith("/") || entry.contains("\\")) {
            throw new SecurityException("archive entry '" + entry + "' would land outside the target");
        }
        return paths.resolve(paths.relative(out));
    }

    private static String tarName(byte[] header) {
        String name = cString(header, 0, 100);
        String prefix = cString(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String cString(byte[] b, int at, int length) {
        int end = at;
        while (end < at + length && b[end] != 0) {
            ++end;
        }
        return new String(b, at, end - at, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int at, int length) {
        long n = 0;
        for (int i = at; i < at + length && b[i] != 0 && b[i] != ' '; ++i) {
            n = n * 8 + (b[i] - '0');
        }
        return n;
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                throw new IOException("truncated tar entry");
            }
            out.write(buffer, 0, n);
            left -= n;
        }
    }
}
