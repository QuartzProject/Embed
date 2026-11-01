package org.quartzlang.embed.gradle

/** Quartz `///` text as Javadoc: escaped so it cannot close the comment or start a block tag. */
internal object Javadoc {
    fun text(doc: String): String = doc.trim()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("*/", "*&#47;").replace("@", "&#64;")

    /** A comment at [indent] with [lead] (already Javadoc) and the Quartz [doc] as paragraphs after it. */
    fun comment(indent: String, lead: String, doc: String, tags: List<String> = emptyList()): String {
        val lines = mutableListOf<String>()
        if (lead.isNotEmpty()) lines += lead
        val body = text(doc)
        if (body.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += "<p>"
            lines += body.lines().map { it.trimEnd() }
        }
        if (tags.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += tags
        }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return "$indent/** ${lines[0]} */\n"
        return buildString {
            append("$indent/**\n")
            lines.forEach { append(if (it.isEmpty()) "$indent *\n" else "$indent * $it\n") }
            append("$indent */\n")
        }
    }
}
