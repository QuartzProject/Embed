package org.quartzlang.embed.gradle

/** A top-level `pub fn` as written: the facade takes parameter names and handle type names from here. */
internal data class PublicFunction(val name: String, val params: List<Pair<String, String>>, val result: String?)

/** Reads what the facade and the kit selection need from Quartz sources, without a full parser. */
internal object QuartzSources {
    private val kitUse = Regex("""\borg\.quartzlang\.embed\.([a-z]+)\b""")

    /** Kits a source names, by import or by qualified use. */
    fun kitsUsed(text: String): Set<String> = kitUse.findAll(stripComments(text)).map { it.groupValues[1] }.toSet()

    /** Top-level `pub fn` declarations that are neither generic, `suspend` nor `@test`. */
    fun publicFunctions(text: String): List<PublicFunction> {
        val tokens = tokenize(text)
        val out = mutableListOf<PublicFunction>()
        var depth = 0
        var attributes = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "{" -> ++depth
                t == "}" -> --depth
                depth == 0 && t == "@" && i + 1 < tokens.size -> attributes += tokens[++i]
                depth == 0 && t == "pub" && tokens.getOrNull(i + 1) == "fn" -> {
                    val parsed = header(tokens, i + 2)
                    if (parsed != null && "test" !in attributes) out += parsed
                    attributes = mutableListOf()
                }
                depth == 0 && (t == "fn" || t == "struct" || t == "enum" || t == "state" || t == "impl") ->
                    attributes = mutableListOf()
            }
            ++i
        }
        return out
    }

    private fun header(tokens: List<String>, start: Int): PublicFunction? {
        val name = tokens.getOrNull(start) ?: return null
        if (tokens.getOrNull(start + 1) != "(") return null
        var i = start + 2
        val params = mutableListOf<Pair<String, String>>()
        var current = mutableListOf<String>()
        var nesting = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (nesting == 0 && (t == ")" || t == ",")) {
                if (current.isNotEmpty()) params += param(current) ?: return null
                current = mutableListOf()
                if (t == ")") break
            } else {
                if (t == "(" || t == "[" || t == "<") ++nesting
                if (t == ")" || t == "]" || t == ">") --nesting
                current += t
            }
            ++i
        }
        val result = if (tokens.getOrNull(i + 1) == "->") {
            val type = StringBuilder()
            var j = i + 2
            while (j < tokens.size && tokens[j] != "{" && tokens[j] != "\n") type.append(tokens[j++])
            type.toString()
        } else {
            null
        }
        return PublicFunction(name, params, result)
    }

    private fun param(tokens: List<String>): Pair<String, String>? {
        if (tokens.size < 3 || tokens[1] != ":") return null
        val type = tokens.drop(2).takeWhile { it != "=" }.joinToString("")
        return tokens[0] to type
    }

    /** Words, punctuation and `->`; strings, comments and line breaks dropped. */
    private fun tokenize(text: String): List<String> {
        val src = stripComments(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> i = skipString(src, i)
                c.isLetterOrDigit() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.')) ++i
                    out += src.substring(start, i)
                    continue
                }
                c == '-' && src.getOrNull(i + 1) == '>' -> {
                    out += "->"
                    i += 2
                    continue
                }
                c == '\'' -> {
                    i += 2
                    while (i < src.length && src[i] != '\'') ++i
                }
                !c.isWhitespace() -> out += c.toString()
            }
            ++i
        }
        return out
    }

    private fun skipString(src: String, start: Int): Int {
        if (src.startsWith("\"\"\"", start)) {
            val end = src.indexOf("\"\"\"", start + 3)
            return if (end < 0) src.length else end + 2
        }
        var i = start + 1
        var braces = 0
        while (i < src.length) {
            when (src[i]) {
                '\\' -> ++i
                '{' -> ++braces
                '}' -> --braces
                '"' -> if (braces <= 0) return i else i = skipString(src, i)
            }
            ++i
        }
        return i
    }

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text.startsWith("//", i)) {
                while (i < text.length && text[i] != '\n') ++i
            } else if (text.startsWith("/*", i)) {
                val end = text.indexOf("*/", i + 2)
                i = if (end < 0) text.length else end + 2
            } else if (text[i] == '"') {
                val end = skipString(text, i)
                out.append(text, i, minOf(end + 1, text.length))
                i = end + 1
            } else {
                out.append(text[i++])
            }
        }
        return out.toString()
    }
}
