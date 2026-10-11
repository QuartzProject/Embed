package org.quartzlang.embed.gradle

/** A Java type as a method descriptor or generic signature spells it. */
internal sealed interface JvmType {
    data class Primitive(val code: Char) : JvmType
    data class Ref(val binaryName: String, val arguments: List<JvmType> = emptyList()) : JvmType
    data class Array(val element: JvmType) : JvmType
    data object Unknown : JvmType
}

/** Reads the types of a method, field or record component from its generic signature, or its descriptor. */
internal class JvmSignature(private val text: String) {
    private var at = 0

    fun method(): Pair<List<JvmType>, JvmType> {
        if (peek() == '<') {
            skipTypeParameters()
        }
        expect('(')
        val params = mutableListOf<JvmType>()
        while (peek() != ')') {
            params += type()
        }
        expect(')')
        return params to type()
    }

    /** The type of a field or record component. */
    fun field(): JvmType = type()

    private fun type(): JvmType = when (val c = next()) {
        'L' -> reference()
        '[' -> JvmType.Array(type())
        'T' -> {
            at = text.indexOf(';', at) + 1
            JvmType.Unknown
        }
        '+', '-' -> type()
        '*' -> JvmType.Unknown
        else -> JvmType.Primitive(c)
    }

    private fun reference(): JvmType {
        val name = StringBuilder()
        var arguments = emptyList<JvmType>()
        while (true) {
            when (val c = next()) {
                ';' -> return JvmType.Ref(name.toString().replace('/', '.'), arguments)
                '<' -> {
                    val list = mutableListOf<JvmType>()
                    while (peek() != '>') {
                        list += type()
                    }
                    expect('>')
                    arguments = list
                }
                '.' -> {
                    name.append('$')
                    arguments = emptyList()
                }
                else -> name.append(c)
            }
        }
    }

    private fun skipTypeParameters() {
        var depth = 0
        do {
            when (next()) {
                '<' -> ++depth
                '>' -> --depth
            }
        } while (depth > 0)
    }

    private fun peek(): Char = text[at]

    private fun next(): Char = text[at++]

    private fun expect(c: Char) {
        check(next() == c) { "malformed signature $text" }
    }
}
