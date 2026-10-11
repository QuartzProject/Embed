package org.quartzlang.embed.gradle

/** What the build does with a compiler warning, for every rule or for one. */
enum class QuartzSeverity {
    /** Drop it. */
    IGNORE,

    /** Print it and go on. */
    WARN,

    /** Fail the build. */
    ERROR;

    /** The level qz_compile_with takes. */
    internal val level: String
        get() = when (this) {
            IGNORE -> "ignore"
            WARN -> "warning"
            ERROR -> "error"
        }
}
