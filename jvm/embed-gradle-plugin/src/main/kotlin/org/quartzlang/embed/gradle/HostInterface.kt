package org.quartzlang.embed.gradle

import org.quartzlang.embed.api.ModuleMetadata

/** The generated host interface: the `.qzi` text, the method behind each import and the handle classes. */
internal class HostInterface(
    val qzi: String,
    val bindings: List<ModuleMetadata.HostBinding>,
    /** Handle classes by Quartz name, and the classes behind the structs and enums the interface declares. */
    val handles: Map<String, String>,
    val classes: Map<String, String>,
) {
    companion object {
        private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Builds the interface, or throws with one line per method Quartz cannot take, naming class and method. */
        fun of(scan: ScanResult, extraHandles: Map<String, String>, hostPackage: String, docs: HostDocs): HostInterface {
            val handles = sortedMapOf<String, String>().apply { putAll(scan.handles); putAll(extraHandles) }
            val types = HostTypes(scan.classes, handles.entries.associate { (name, cls) -> cls to name })
            val errors = mutableListOf<String>()
            val free = sortedMapOf<String, String>()
            val methods = sortedMapOf<String, java.util.SortedMap<String, String>>()
            val bindings = mutableListOf<ModuleMetadata.HostBinding>()
            val prefix = if (hostPackage.isEmpty()) "" else "$hostPackage."
            for (m in scan.methods.sortedWith(compareBy({ it.owner }, { it.name }, { it.descriptor }))) {
                val where = "${m.owner}.${m.name}"
                if (!identifier.matches(m.quartzName)) {
                    errors += "$where: '${m.quartzName}' is not a Quartz identifier"
                    continue
                }
                val (params, result) = JvmSignature(m.signature ?: m.descriptor).method().let { (p, r) ->
                    if (p.size == m.parameterNames.size) p to r else JvmSignature(m.descriptor).method()
                }
                val receiverHandle = (params.firstOrNull() as? JvmType.Ref)?.let { types.handleOf(it.binaryName) }
                if (m.receiver && receiverHandle == null) {
                    errors += "$where: its first parameter is not a handle class, so it cannot be a handle method"
                    continue
                }
                val handleName = when {
                    m.receiver -> receiverHandle
                    m.isStatic -> null
                    else -> types.handleOf(m.owner)
                }
                val kind = when {
                    m.isStatic -> ModuleMetadata.CallKind.STATIC
                    handleName != null && !m.receiver -> ModuleMetadata.CallKind.HANDLE
                    else -> ModuleMetadata.CallKind.INSTANCE
                }
                val rendered = mutableListOf<String>()
                if (handleName != null) rendered += "self"
                val first = if (m.receiver) 1 else 0
                for (i in first until params.size) {
                    val name = m.parameterNames[i].takeIf { identifier.matches(it) && it != "self" } ?: "arg$i"
                    types.quartz(params[i], i in m.nullableParameters, errors, "$where: parameter '$name'")
                        ?.let { rendered += "$name: $it" }
                }
                val void = result == JvmType.Primitive('V') || result == JvmType.Ref("kotlin.Unit")
                val resultText = if (void) "" else types.quartz(result, m.nullableResult, errors, "$where: its result")
                    ?.let { " -> $it" } ?: ""
                if (m.property && (handleName == null || rendered.size != 1 || void)) {
                    errors += "$where: a property is a handle method without parameters that returns a value"
                    continue
                }
                val doc = docs.method(m.owner, m.name).joinToString("") { "/// $it\n" }
                val attribute = if (m.property) "@property\n" else ""
                val declaration = "$doc${attribute}fn ${m.quartzName}(${rendered.joinToString(", ")})$resultText"
                val importName = if (handleName != null) "$prefix$handleName.${m.quartzName}" else "$prefix${m.quartzName}"
                val taken = if (handleName != null) methods.getOrPut(handleName) { sortedMapOf() } else free
                if (taken.put(m.quartzName, declaration) != null) {
                    errors += "$where: another @QuartzFunction is already called '$importName'"
                    continue
                }
                bindings += ModuleMetadata.HostBinding(importName, kind, m.owner, m.name, m.descriptor)
            }
            for (cls in scan.types) {
                types.quartz(JvmType.Ref(cls), false, errors, "@QuartzType $cls")
            }
            if (errors.isNotEmpty()) {
                throw IllegalArgumentException(errors.joinToString("\n"))
            }
            val qzi = buildString {
                if (hostPackage.isNotEmpty()) append("package $hostPackage\n\n")
                for (decl in free.values) append(decl).append('\n')
                for (decl in types.declarations()) append('\n').append(decl)
                for ((name, cls) in handles) {
                    append('\n')
                    docs.type(cls).forEach { append("/// ").append(it).append('\n') }
                    append("@handle\nstruct $name {}\n")
                    methods[name]?.let { declared ->
                        append("\nimpl $name {\n")
                        for (decl in declared.values) append(decl.prependIndent("    ")).append('\n')
                        append("}\n")
                    }
                }
            }
            val classes = (handles.entries.map { (name, cls) -> name to cls } + types.valueClasses())
                .associate { (name, cls) -> "$prefix$name" to cls }
            return HostInterface(qzi, bindings, handles, classes)
        }
    }
}

/** Quartz types for Java types, declaring a struct or enum for each record, data class, enum or sealed type. */
private class HostTypes(private val classes: ClassIndex, private val handleByClass: Map<String, String>) {
    private val declared = linkedMapOf<String, String>()
    private val quartzNames = mutableMapOf<String, String>()

    fun handleOf(className: String): String? = handleByClass[className]

    fun declarations(): Collection<String> = declared.values

    /** Quartz name and class of every struct and enum declared. */
    fun valueClasses(): List<Pair<String, String>> = declared.keys.map { quartzNames.getValue(it) to it }

    fun quartz(type: JvmType, nullable: Boolean, errors: MutableList<String>, where: String): String? {
        val problems = mutableListOf<String>()
        val text = map(type, nullable, problems)
        if (text == null) errors += "$where is a ${describe(type)}, which cannot cross into Quartz" +
            problems.joinToString("") { " ($it)" }
        return text
    }

    private fun map(type: JvmType, nullable: Boolean, problems: MutableList<String>): String? {
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
                    type.arguments.singleOrNull()?.let { map(it, false, problems) }?.let { "[$it]" }
                "java.util.Map", "java.util.LinkedHashMap", "java.util.HashMap" -> if (type.arguments.size == 2) {
                    val k = map(type.arguments[0], false, problems)
                    val v = map(type.arguments[1], false, problems)
                    if (k != null && v != null) "Map<$k, $v>" else null
                } else null
                "org.quartzlang.jvm.Result" -> if (type.arguments.size == 2) {
                    val ok = map(type.arguments[0], false, problems)
                    val err = map(type.arguments[1], false, problems)
                    if (ok != null && err != null) "Result<$ok, $err>" else null
                } else null
                "java.lang.Boolean" -> "bool"
                "java.lang.Byte" -> "i8"
                "java.lang.Short" -> "i16"
                "java.lang.Integer" -> "i32"
                "java.lang.Long" -> "int"
                "java.lang.Float" -> "f32"
                "java.lang.Double" -> "float"
                else -> handleByClass[type.binaryName] ?: valueType(type, problems)
            }
            JvmType.Unknown -> null
        } ?: return null
        return if (nullable) "$base?" else base
    }

    /** The Quartz name of a record, data class, enum or sealed type, declaring it the first time. */
    private fun valueType(type: JvmType.Ref, problems: MutableList<String>): String? {
        quartzNames[type.binaryName]?.let { return it }
        val shape = classes.shape(type.binaryName) ?: return null.also { problems += "not on the classpath" }
        if (type.arguments.isNotEmpty()) return null.also { problems += "generic classes cannot cross" }
        val clash = quartzNames.entries.firstOrNull { it.value == shape.name }
        if (clash != null) return null.also { problems += "${clash.key} is already declared as ${shape.name}" }
        quartzNames[type.binaryName] = shape.name
        val body = when (shape.kind) {
            ClassShape.Kind.RECORD, ClassShape.Kind.DATA -> shape.components.map { c ->
                (map(c.type, c.nullable, problems) ?: return forget(type)).let { "    ${c.name}: $it" }
            }.let { "struct ${shape.name} {\n${it.joinToString("\n")}\n}\n" }
            ClassShape.Kind.ENUM -> "enum ${shape.name} {\n${shape.cases.joinToString("\n") { "    $it" }}\n}\n"
            ClassShape.Kind.SEALED -> shape.cases.map { sub ->
                val s = classes.shape(sub) ?: return forget(type).also { problems += "$sub is not on the classpath" }
                when (s.kind) {
                    ClassShape.Kind.OBJECT -> "    ${s.name}"
                    ClassShape.Kind.RECORD, ClassShape.Kind.DATA -> s.components.map { c ->
                        val t = map(c.type, c.nullable, problems) ?: return forget(type)
                        if (c.type is JvmType.Ref && handleByClass.containsKey(c.type.binaryName)) {
                            return forget(type).also { problems += "a variant cannot carry a handle" }
                        }
                        t
                    }.let { "    ${s.name}(${it.joinToString(", ")})" }
                    else -> return forget(type).also { problems += "$sub is neither a record, a data class nor an object" }
                }
            }.let { "enum ${shape.name} {\n${it.joinToString("\n")}\n}\n" }
            else -> return forget(type).also {
                problems += "only records, data classes, enums, sealed types and @QuartzHandle classes cross"
            }
        }
        declared[type.binaryName] = body
        return shape.name
    }

    private fun forget(type: JvmType.Ref): String? {
        quartzNames.remove(type.binaryName)
        return null
    }

    private fun describe(type: JvmType): String = when (type) {
        is JvmType.Primitive -> type.code.toString()
        is JvmType.Array -> describe(type.element) + "[]"
        is JvmType.Ref -> type.binaryName + if (type.arguments.isEmpty()) "" else
            type.arguments.joinToString(", ", "<", ">") { describe(it) }
        JvmType.Unknown -> "type variable"
    }
}
