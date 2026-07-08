package org.quartzlang.embed.gradle

/** One compiler diagnostic, with the source lines the compiler printed under it. */
internal data class Diagnostic(
    val file: String?,
    val line: Int,
    val column: Int,
    val severity: String,
    val message: String,
    val context: List<String>,
) {
    /** `path:line:col: severity: message`, the shape IDEs turn into links, with the file's real path. */
    fun render(paths: Map<String, String>): String {
        val where = file?.let { "${paths[it] ?: it}:$line:$column: " } ?: ""
        return (listOf("$where$severity: $message") + context).joinToString("\n")
    }

    companion object {
        private val located = Regex("""^(.+?):(\d+):(\d+): (error|warning|note): (.*)$""")
        private val bare = Regex("""^(error|warning|note): (.*)$""")

        fun parse(text: String): List<Diagnostic> {
            val out = mutableListOf<Diagnostic>()
            for (line in text.lines()) {
                val m = located.matchEntire(line)
                val b = if (m == null) bare.matchEntire(line) else null
                when {
                    m != null -> out += Diagnostic(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                        m.groupValues[4], m.groupValues[5], emptyList())
                    b != null -> out += Diagnostic(null, 0, 0, b.groupValues[1], b.groupValues[2], emptyList())
                    line.isNotBlank() && out.isNotEmpty() -> out[out.size - 1] =
                        out.last().copy(context = out.last().context + line)
                    line.isNotBlank() -> out += Diagnostic(null, 0, 0, "error", line, emptyList())
                }
            }
            return out
        }
    }
}
