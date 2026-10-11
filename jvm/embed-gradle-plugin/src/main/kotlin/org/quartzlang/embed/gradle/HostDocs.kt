package org.quartzlang.embed.gradle

import java.io.File

/**
 * KDoc and Javadoc of the host API, read from its sources: the description before the first block tag, keyed by the
 * simple name of the declaring class (`FileKt` for top-level Kotlin functions) and the member name, or the class
 * alone. Javadoc does not survive compilation, so this reads text and matches by name, without a parser.
 */
internal class HostDocs private constructor(private val docs: Map<Pair<String, String>, List<String>>) {
    fun method(owner: String, name: String): List<String> = docs[simple(owner) to name] ?: emptyList()

    fun type(owner: String): List<String> = docs[simple(owner) to ""] ?: emptyList()

    private fun simple(owner: String) = owner.substringAfterLast('.').substringAfterLast('$')

    companion object {
        private val typeDecl = Regex("""\b(?:class|interface|object|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val kotlinFun = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][A-Za-z0-9_<>?,. ]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val javaMethod = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val jvmName = Regex("""@file:JvmName\("([^"]+)"\)""")

        fun read(files: Iterable<File>): HostDocs {
            val docs = mutableMapOf<Pair<String, String>, List<String>>()
            files.filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .forEach { readFile(it, docs) }
            return HostDocs(docs)
        }

        fun empty() = HostDocs(emptyMap())

        private fun readFile(file: File, docs: MutableMap<Pair<String, String>, List<String>>) {
            val lines = file.readLines()
            val text = lines.joinToString("\n")
            val fileClass = jvmName.find(text)?.groupValues?.get(1)
                ?: (file.nameWithoutExtension + if (file.name.endsWith(".kt")) "Kt" else "")
            val types = mutableListOf<Pair<Int, String>>()
            var pending: List<String>? = null
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                val indent = line.length - line.trimStart().length
                when {
                    trimmed.startsWith("/**") -> {
                        val block = mutableListOf(trimmed)
                        while (!block.last().contains("*/") && i + 1 < lines.size) block += lines[++i].trim()
                        pending = description(block)
                    }
                    trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("//") -> Unit
                    else -> {
                        while (types.isNotEmpty() && types.last().first >= indent) types.removeAt(types.size - 1)
                        val owner = types.lastOrNull()?.second ?: fileClass
                        val type = typeDecl.find(trimmed)?.takeIf { !trimmed.contains("fun ") }
                        val member = kotlinFun.find(trimmed)?.groupValues?.get(1)
                            ?: javaMethod.find(trimmed)?.groupValues?.get(1)?.takeIf { type == null && !trimmed.startsWith("return") }
                        if (type != null) {
                            pending?.let { docs[type.groupValues[1] to ""] = it }
                            types += indent to type.groupValues[1]
                        } else if (member != null) {
                            pending?.let { docs[owner to member] = it }
                        }
                        pending = null
                    }
                }
                ++i
            }
        }

        private fun description(block: List<String>): List<String> {
            val out = mutableListOf<String>()
            for (raw in block) {
                val line = raw.removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trim()
                if (line.startsWith("@") || line.startsWith("<p>") && out.isNotEmpty()) break
                if (line.isNotEmpty()) out += line.replace(Regex("""\{@(?:code|link|linkplain)\s+([^}]*)}"""), "`$1`")
            }
            return out
        }
    }
}
