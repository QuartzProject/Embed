package org.quartzlang.embed.gradle

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/** What the compiler worker hands back to the task: whether the module compiled, its diagnostics and notes. */
internal class CompileReport(val ok: Boolean, val diagnostics: List<CompilerDiagnostic>, val notes: List<String>) {
    fun write(file: File) {
        val p = Properties()
        p["status"] = if (ok) "ok" else "failed"
        p["diagnostics"] = diagnostics.size.toString()
        diagnostics.forEachIndexed { i, d ->
            p["d.$i.severity"] = d.severity
            p["d.$i.rule"] = d.rule
            p["d.$i.file"] = d.file
            p["d.$i.line"] = d.line.toString()
            p["d.$i.column"] = d.column.toString()
            p["d.$i.message"] = d.message
        }
        p["notes"] = notes.size.toString()
        notes.forEachIndexed { i, n -> p["n.$i"] = n }
        file.parentFile.mkdirs()
        file.writeText(StringWriter().also { p.store(it, null) }.toString())
    }

    companion object {
        fun read(file: File): CompileReport {
            val p = Properties().apply { load(StringReader(file.readText())) }
            val diagnostics = (0 until p.getProperty("diagnostics").toInt()).map { i ->
                CompilerDiagnostic(p.getProperty("d.$i.severity"), p.getProperty("d.$i.rule"),
                    p.getProperty("d.$i.file"), p.getProperty("d.$i.line").toInt(),
                    p.getProperty("d.$i.column").toInt(), p.getProperty("d.$i.message"))
            }
            val notes = (0 until p.getProperty("notes").toInt()).map { p.getProperty("n.$it") }
            return CompileReport(p.getProperty("status") == "ok", diagnostics, notes)
        }
    }
}

/**
 * `path:line:col: severity: message [rule]`, the shape IDEs turn into links, with the file's real path and the
 * source line under it.
 */
internal fun CompilerDiagnostic.render(paths: Map<String, String>): String {
    val path = paths[file] ?: file
    val where = if (file.isEmpty()) "" else if (line == 0) "$path: " else "$path:$line:$column: "
    val ruleText = if (rule.isEmpty() || message.endsWith("[$rule]")) "" else " [$rule]"
    val head = "$where$severity: $message$ruleText"
    val source = paths[file]?.let(::File)?.takeIf { line > 0 && it.isFile }?.useLines { it.drop(line - 1).firstOrNull() }
        ?: return head
    val caret = " ".repeat((column - 1).coerceIn(0, source.length)) + "^"
    return "$head\n    $source\n    $caret"
}
