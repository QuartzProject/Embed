package org.quartzlang.embed;

import java.nio.file.Path;
import java.util.List;
import org.quartzlang.embed.kits.ArchivesKit;
import org.quartzlang.embed.kits.FileSystemKit;
import org.quartzlang.embed.kits.HttpKit;
import org.quartzlang.embed.kits.ProcessKit;
import org.quartzlang.embed.kits.TerminalKit;

/** The kits Embed ships. Each grants one capability; a module only gets what the application passes. */
public final class Kits {
    public static final List<String> NAMES = List.of("fs", "terminal", "process", "http", "archives");

    private Kits() {}

    /** Files under {@code root} and nothing else. */
    public static Kit fileSystem(Path root) {
        return new FileSystemKit(new RootedPaths(root));
    }

    public static Kit terminal() {
        return new TerminalKit();
    }

    /** Arguments, environment, exit and running programs: a module with it can do what the user can. */
    public static Kit process(String... args) {
        return new ProcessKit(List.of(args));
    }

    public static Kit http() {
        return new HttpKit();
    }

    /** Zip and tar archives under {@code root}. */
    public static Kit archives(Path root) {
        return new ArchivesKit(new RootedPaths(root));
    }
}
