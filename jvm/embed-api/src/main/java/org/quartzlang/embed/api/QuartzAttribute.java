package org.quartzlang.embed.api;

import java.util.List;
import java.util.Optional;

/**
 * An attribute of a Quartz function as the source wrote it, such as {@code @command("heal", perm = "x.heal")} or
 * {@code @on(PlayerJoin, priority = High)}.
 *
 * @param name the attribute without {@code @}: {@code command}, {@code on}, {@code requires}, {@code test}
 * @param args its arguments in order
 */
public record QuartzAttribute(String name, List<Arg> args) {
    /** What kind of literal an argument is; {@code NAME} is a bare or dotted name such as {@code PlayerJoin}. */
    public enum Kind {
        INT,
        FLOAT,
        BOOL,
        STR,
        NAME
    }

    /**
     * One argument.
     *
     * @param key the name of a named argument, empty for a positional one
     * @param value the literal as text: {@code 12}, {@code 0.5}, {@code true}, the string, or the name
     */
    public record Arg(String key, Kind kind, String value) {
        public long asLong() {
            return Long.parseLong(value);
        }

        public double asDouble() {
            return Double.parseDouble(value);
        }

        public boolean asBoolean() {
            return Boolean.parseBoolean(value);
        }
    }

    public QuartzAttribute {
        args = List.copyOf(args);
    }

    /** Positional argument {@code index}, counting only arguments without a key. */
    public Optional<Arg> positional(int index) {
        return args.stream().filter(a -> a.key().isEmpty()).skip(index).findFirst();
    }

    /** The named argument {@code key}. */
    public Optional<Arg> named(String key) {
        return args.stream().filter(a -> a.key().equals(key)).findFirst();
    }

    /** The first positional argument as text: the command name of {@code @command}, the event of {@code @on}. */
    public String value() {
        return positional(0).map(Arg::value).orElse("");
    }

    /** The named argument {@code key} as text, or {@code fallback}. */
    public String get(String key, String fallback) {
        return named(key).map(Arg::value).orElse(fallback);
    }
}
