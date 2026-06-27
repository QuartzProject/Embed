package org.quartzlang.embed;

import org.quartzlang.jvm.Limits;

/** Budgets that suit the two usual hosts. */
public final class EmbedLimits {
    /** A plugin inside a server: 32 MiB and 10 million fuel per call, so one bad handler cannot stall a tick. */
    public static final Limits PLUGIN = Limits.DEFAULT.withMemory(32L << 20).withFuel(10_000_000);
    /** A command line tool, which owns the process: 1 GiB and fuel that does not run out in practice. */
    public static final Limits CLI = Limits.DEFAULT.withMemory(1L << 30).withFuel(Long.MAX_VALUE / 4);

    private EmbedLimits() {}
}
