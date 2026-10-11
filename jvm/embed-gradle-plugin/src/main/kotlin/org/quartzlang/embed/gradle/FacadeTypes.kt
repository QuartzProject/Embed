package org.quartzlang.embed.gradle

/**
 * Java types for the Quartz types of a module's signatures. Structs become nested records, enums nested enums or
 * sealed interfaces of records, `Result` the bindings' `Result`, handles the host class behind them.
 */
internal class FacadeTypes(
    private val program: ProgramInfo,
    /** Host classes by qualified Quartz name: the handles, structs and enums of the host interface. */
    private val classes: Map<String, String>,
    private val reserved: Set<String>,
) {
    private val declared = linkedMapOf<Int, String>()
    private val names = mutableSetOf<String>()
    private val declarations = StringBuilder()

    /** The nested records, enums and interfaces the mapped types need, in Java. */
    fun declarations(): String = declarations.toString()

    /** The Java type of `id`, boxed when it is a type argument; null when it cannot cross the bindings. */
    fun java(id: Int, boxed: Boolean = false): String? {
        val t = program.type(id)
        fun pick(primitive: String, wrapper: String) = if (boxed) wrapper else primitive
        return when (t.kind) {
            QuartzType.Kind.VOID -> if (boxed) "Void" else "void"
            QuartzType.Kind.BOOL -> pick("boolean", "Boolean")
            QuartzType.Kind.I8 -> pick("byte", "Byte")
            QuartzType.Kind.I16 -> pick("short", "Short")
            QuartzType.Kind.I32, QuartzType.Kind.U8, QuartzType.Kind.U16, QuartzType.Kind.CHAR -> pick("int", "Integer")
            QuartzType.Kind.I64, QuartzType.Kind.U32, QuartzType.Kind.U64 -> pick("long", "Long")
            QuartzType.Kind.F32 -> pick("float", "Float")
            QuartzType.Kind.F64 -> pick("double", "Double")
            QuartzType.Kind.STR -> "String"
            QuartzType.Kind.BYTES -> "byte[]"
            QuartzType.Kind.LIST -> java(t.element, true)?.let { "java.util.List<$it>" }
            QuartzType.Kind.MAP -> {
                val k = java(t.key, true) ?: return null
                java(t.element, true)?.let { "java.util.Map<$k, $it>" }
            }
            QuartzType.Kind.NULLABLE -> java(t.element, true)
            QuartzType.Kind.TUPLE -> if (t.fields.all { java(it.type, true) != null }) "java.util.List<Object>" else null
            QuartzType.Kind.HANDLE -> hostClass(t) ?: "Object"
            QuartzType.Kind.STRUCT -> hostClass(t) ?: declare(id, t)
            QuartzType.Kind.ENUM -> if (t.isResult) {
                val ok = java(t.cases[0].payload[0], true) ?: return null
                java(t.cases[1].payload[0], true)?.let { "org.quartzlang.jvm.Result<$ok, $it>" }
            } else {
                hostClass(t) ?: declare(id, t)
            }
            QuartzType.Kind.FUNCTION, QuartzType.Kind.OTHER -> null
        }
    }

    /** True when the Java type is generic, so a call needs a type token rather than a class literal. */
    fun isGeneric(java: String) = '<' in java

    private fun hostClass(t: QuartzType): String? = classes[t.qualifiedName]?.replace('$', '.')

    private fun declare(id: Int, t: QuartzType): String? {
        declared[id]?.let { return it }
        val name = unique(identifier(t.name))
        declared[id] = name
        val text = if (t.kind == QuartzType.Kind.STRUCT) record(name, t) else enumeration(name, t)
        if (text == null) {
            declared.remove(id)
            return null
        }
        declarations.append(text)
        return name
    }

    private fun record(name: String, t: QuartzType): String? {
        val components = t.fields.map { f -> (java(f.type) ?: return null) + " " + FacadeNames.safe(f.name) }
        return "    /** The Quartz struct {@code ${t.name}}. */\n" +
            "    public record $name(${components.joinToString(", ")}) {}\n\n"
    }

    private fun enumeration(name: String, t: QuartzType): String? {
        if (t.cases.all { it.payload.isEmpty() }) {
            return "    /** The Quartz enum {@code ${t.name}}. */\n" +
                "    public enum $name {\n        ${t.cases.joinToString(",\n        ") { it.name }}\n    }\n\n"
        }
        val variants = t.cases.map { c ->
            val values = c.payload.mapIndexed { i, p ->
                (java(p) ?: return null) + " " + (if (c.payload.size == 1) "value" else "value$i")
            }
            "        record ${c.name}(${values.joinToString(", ")}) implements $name {}\n"
        }
        return "    /** The Quartz enum {@code ${t.name}}: one record per variant, named like it. */\n" +
            "    public sealed interface $name {\n${variants.joinToString("")}    }\n\n"
    }

    private fun unique(base: String): String {
        var name = base
        var n = 2
        while (name in names || name in reserved) name = base + n++
        names += name
        return name
    }

    private fun identifier(quartzName: String): String =
        quartzName.split(Regex("[^A-Za-z0-9_]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { "Value" }
}

internal object FacadeNames {
    private val keywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield", "module",
        "true", "false", "null",
    )

    fun safe(name: String) = if (name in keywords) "${name}_" else name
}
