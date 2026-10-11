package org.quartzlang.embed;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.quartzlang.embed.api.EmbedLayout;
import org.quartzlang.embed.kits.ArchivesKit;
import org.quartzlang.embed.kits.FileSystemKit;
import org.quartzlang.embed.kits.HttpKit;
import org.quartzlang.embed.kits.ProcessKit;
import org.quartzlang.embed.kits.TerminalKit;

/** The kits Embed ships. Each grants one capability; a module only gets what the application passes. */
public final class Kits {
    public static final List<String> NAMES = EmbedLayout.KIT_NAMES;

    private Kits() {}

    /** Files under {@code root} and nothing else. */
    public static Kit fileSystem(Path root) {
        return new FileSystemKit(new RootedPaths(root));
    }

    /** Files under {@code root}, where relative paths start, and the files or directories in {@code more}. */
    public static Kit fileSystem(Path root, Collection<Path> more) {
        return new FileSystemKit(new RootedPaths(root, List.copyOf(more)));
    }

    /**
     * The paths a command line names, for a tool that may touch exactly what its user pointed it at: every argument
     * that does not start with '-', as given relative to the working directory, whether it exists or not.
     */
    public static List<Path> argumentPaths(String... args) {
        List<Path> out = new ArrayList<>();
        for (String arg : args) {
            if (arg.isEmpty() || arg.startsWith("-") || arg.indexOf('\0') >= 0) {
                continue;
            }
            try {
                out.add(Path.of(arg).toAbsolutePath().normalize());
            } catch (InvalidPathException ignored) {
                // not a path on this system
            }
        }
        return out;
    }

    public static Kit terminal() {
        return new TerminalKit();
    }

    /** Arguments, environment, exit and running programs: a module with it can do what the user can. */
    public static Kit process(String... args) {
        return new ProcessKit(List.of(args));
    }

    /** Answers {@code org.quartzlang.http.send} of the library, which fails without it. */
    public static Kit http() {
        return new HttpKit();
    }

    /** Zip and tar archives under {@code root}. */
    public static Kit archives(Path root) {
        return new ArchivesKit(new RootedPaths(root));
    }

    /** Zip and tar archives under {@code root} and in {@code more}, as {@link #fileSystem(Path, Collection)}. */
    public static Kit archives(Path root, Collection<Path> more) {
        return new ArchivesKit(new RootedPaths(root, List.copyOf(more)));
    }
}
