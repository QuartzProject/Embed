package org.quartzlang.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootedPathsTest {
    @Test
    void refusesEveryWayOut(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.createSymbolicLink(root.resolve("link"), outside);
        RootedPaths paths = new RootedPaths(root);

        assertEquals(root.resolve("a/b.txt"), paths.resolve("a/./c/../b.txt"));
        assertThrows(SecurityException.class, () -> paths.resolve("../outside/x"));
        assertThrows(SecurityException.class, () -> paths.resolve("a/../../outside"));
        assertThrows(SecurityException.class, () -> paths.resolve(outside.toString()));
        assertThrows(SecurityException.class, () -> paths.resolve("link/new.txt"));
        assertThrows(SecurityException.class, () -> paths.resolve("link"));

        Path granted = Files.writeString(outside.resolve("granted.txt"), "x");
        RootedPaths more = new RootedPaths(root, List.of(granted));
        assertEquals(granted, more.resolve(granted.toString()));
        assertEquals(granted, more.resolve("../outside/granted.txt"));
        assertThrows(SecurityException.class, () -> more.resolve(outside.resolve("other.txt").toString()));
    }
}
