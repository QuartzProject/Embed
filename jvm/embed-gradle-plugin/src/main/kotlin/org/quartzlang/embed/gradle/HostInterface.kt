package org.quartzlang.embed.gradle

import org.quartzlang.embed.api.ModuleMetadata

/** The generated host interface: the `.qzi` text, the method behind each import and the handle classes. */
internal class HostInterface(
    val qzi: String,
    val bindings: List<ModuleMetadata.HostBinding>,
    val handles: Map<String, String>,
) {
    companion object {
        private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Builds the interface, or throws with one line per method Quartz cannot take, naming class and method. */
        fun of(scan: ScanResult, extraHandles: Map<String, String>, hostPackage: String): HostInterface {
            val handles = sortedMapOf<String, String>().apply { putAll(scan.handles); putAll(extraHandles) }
            val handleByClass = handles.entries.associate { (name, cls) -> cls to name }
            val errors = mutableListOf<String>()
            val free = sortedMapOf<String, String>()
            val methods = sortedMapOf<String, java.util.SortedMap<String, String>>()
            val bindings = mutableListOf<ModuleMetadata.HostBinding>()
            for (m in scan.methods.sortedWith(compareBy({ it.owner }, { it.name }, { it.descriptor }))) {
                val where = "${m.owner}.${m.name}"
                if (!identifier.matches(m.quartzName)) {
                    errors += "$where: '${m.quartzName}' is not a Quartz identifier"
                    continue
                }
                val (params, result) = JvmSignature(m.signature ?: m.descriptor).method().let { (p, r) ->
                    if (p.size == m.parameterNames.size) p to r else JvmSignature(m.descriptor).method()
                }
                val handleName = if (m.isStatic) null else handleByClass[m.owner]
                val kind = when {
                    m.isStatic -> ModuleMetadata.CallKind.STATIC
                    handleName != null -> ModuleMetadata.CallKind.HANDLE
                    else -> ModuleMetadata.CallKind.INSTANCE
                }
                val rendered = mutableListOf<String>()
                if (kind == ModuleMetadata.CallKind.HANDLE) rendered += "self"
                params.forEachIndexed { i, type ->
                    val name = m.parameterNames[i].takeIf { identifier.matches(it) && it != "self" } ?: "arg$i"
                    when (val t = quartzType(type, i in m.nullableParameters, handleByClass)) {
                        null -> errors += "$where: parameter '${m.parameterNames[i]}' is a ${describe(type)}, which " +
                            "cannot cross into Quartz"
                        else -> rendered += "$name: $t"
                    }
                }
                val resultText = if (result == JvmType.Primitive('V') || result == JvmType.Ref("kotlin.Unit")) {
                    ""
                } else {
                    when (val t = quartzType(result, m.nullableResult, handleByClass)) {
                        null -> {
                            errors += "$where: returns a ${describe(result)}, which cannot cross into Quartz"
                            ""
                        }
                        else -> " -> $t"
                    }
                }
                val declaration = "fn ${m.quartzName}(${rendered.joinToString(", ")})$resultText"
                val importName = when {
                    handleName != null -> "$handleName.${m.quartzName}"
                    hostPackage.isEmpty() -> m.quartzName
                    else -> "$hostPackage.${m.quartzName}"
                }
                val taken = if (handleName != null) methods.getOrPut(handleName) { sortedMapOf() } else free
                if (taken.put(m.quartzName, declaration) != null) {
                    errors += "$where: another @QuartzFunction is already called '$importName'"
                    continue
                }
                bindings += ModuleMetadata.HostBinding(importName, kind, m.owner, m.name, m.descriptor)
            }
            if (errors.isNotEmpty()) {
                throw IllegalArgumentException(errors.joinToString("\n"))
            }
            val qzi = buildString {
                if (hostPackage.isNotEmpty()) append("package $hostPackage\n\n")
                for (decl in free.values) append(decl).append('\n')
                for (name in handles.keys) {
                    append("\n@handle\nstruct $name {}\n")
                    methods[name]?.let { declared ->
                        append("\nimpl $name {\n")
                        for (decl in declared.values) append("    ").append(decl).append('\n')
                        append("}\n")
                    }
                }
            }
            return HostInterface(qzi, bindings, handles)
        }

        private fun quartzType(type: JvmType, nullable: Boolean, handles: Map<String, String>): String? {
            val base = when (type) {
                is JvmType.Primitive -> when (type.code) {
                    'Z' -> "bool"
                    'B' -> "i8"
                    'S' -> "i16"
                    'I' -> "i32"
                    'J' -> "int"
                    'F' -> "f32"
                    'D' -> "float"
                    'C' -> "char"
                    else -> null
                }
                is JvmType.Array -> if (type.element == JvmType.Primitive('B')) "Bytes" else null
                is JvmType.Ref -> when (type.binaryName) {
                    "java.lang.String" -> "str"
                    "java.util.List", "java.util.Collection", "java.lang.Iterable" ->
                        type.arguments.singleOrNull()?.let { quartzType(it, false, handles) }?.let { "[$it]" }
                    "java.lang.Boolean" -> "bool".takeUnless { nullable }
                    "java.lang.Integer" -> "i32".takeUnless { nullable }
                    "java.lang.Long" -> "int".takeUnless { nullable }
                    "java.lang.Double" -> "float".takeUnless { nullable }
                    else -> handles[type.binaryName]?.takeUnless { nullable }
                }
                JvmType.Unknown -> null
            } ?: return null
            val isReference = base == "str" || base == "Bytes" || base.startsWith("[")
            return if (nullable && isReference) "$base?" else base
        }

        private fun describe(type: JvmType): String = when (type) {
            is JvmType.Primitive -> type.code.toString()
            is JvmType.Array -> describe(type.element) + "[]"
            is JvmType.Ref -> type.binaryName + if (type.arguments.isEmpty()) "" else
                type.arguments.joinToString(", ", "<", ">") { describe(it) }
            JvmType.Unknown -> "type variable"
        }
    }
}
