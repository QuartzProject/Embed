package org.quartzlang.embed.gradle

/** What the build does with a compiler warning. */
enum class QuartzSeverity {
    /** Drop it. */
    IGNORE,

    /** Print it and go on. */
    WARN,

    /** Fail the build. */
    ERROR,
}
