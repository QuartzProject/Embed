package org.quartzlang.embed.gradle

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/** One `@QuartzFunction` method as the class file declares it. */
internal data class ScannedMethod(
    val owner: String,
    val name: String,
    val quartzName: String,
    val descriptor: String,
    val signature: String?,
    val isStatic: Boolean,
    val parameterNames: List<String>,
    val nullableParameters: Set<Int>,
    val nullableResult: Boolean,
    val property: Boolean,
    /** `@QuartzFunction(method = true)`, or a Kotlin extension function: the first parameter is the receiver. */
    val receiver: Boolean,
)

/** What the classes hold: annotated methods, `@QuartzHandle` classes by Quartz name, and every class by name. */
internal class ScanResult(val classes: ClassIndex) {
    val methods = mutableListOf<ScannedMethod>()
    val handles = sortedMapOf<String, String>()
    /** `@QuartzType` classes, declared whether or not a method names them. */
    val types = sortedSetOf<String>()
}

/** Reads class files with ASM, without loading them, so a class whose dependencies are missing is still read. */
internal object HostScanner {
    private const val FUNCTION = "Lorg/quartzlang/embed/api/QuartzFunction;"
    private const val HANDLE = "Lorg/quartzlang/embed/api/QuartzHandle;"
    private const val TYPE = "Lorg/quartzlang/embed/api/QuartzType;"
    private val marker = "Lorg/quartzlang/embed/api/Quartz".toByteArray()

    fun scan(files: Iterable<File>): ScanResult {
        val index = ClassIndex()
        val result = ScanResult(index)
        for (file in files) {
            when {
                file.isDirectory -> file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sorted()
                    .forEach { f ->
                        val bytes = f.readBytes()
                        index.add(bytes) { bytes }
                        read(bytes, result)
                    }

                file.isFile && (file.name.endsWith(".jar") || file.name.endsWith(".zip")) -> ZipFile(file).use { zip ->
                    zip.stream().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                        .forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            index.add(entry.name.removeSuffix(".class").replace('/', '.'), bytes)
                            read(bytes, result)
                        }
                }
            }
        }
        return result
    }

    private fun read(bytes: ByteArray, result: ScanResult) {
        if (!contains(bytes, marker)) {
            return
        }
        ClassReader(bytes).accept(Visitor(result), ClassReader.SKIP_FRAMES)
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    internal fun isNullable(descriptor: String) = descriptor.endsWith("/Nullable;")

    private class Visitor(private val result: ScanResult) : ClassVisitor(Opcodes.ASM9) {
        private lateinit var owner: String

        override fun visit(v: Int, access: Int, name: String, sig: String?, superName: String?, ifs: Array<String>?) {
            owner = name.replace('/', '.')
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor == TYPE) result.types += owner
            if (descriptor != HANDLE) return null
            return ValuesVisitor { values ->
                val given = (values["name"] as? String)?.takeIf { it.isNotEmpty() }
                result.handles[given ?: owner.substringAfterLast('.').substringAfterLast('$')] = owner
            }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
            val isStatic = access and Opcodes.ACC_STATIC != 0
            return MethodReader(isStatic, descriptor) { values, names, nullable, nullableResult ->
                val quartzName = (values["name"] as? String)?.takeIf { it.isNotEmpty() } ?: name
                val extension = names.firstOrNull()?.let { it.startsWith("\$this\$") || it == "\$receiver" } == true
                result.methods += ScannedMethod(owner, name, quartzName, descriptor, signature, isStatic, names,
                    nullable, nullableResult, values["property"] == true, values["method"] == true || extension)
            }
        }
    }

    private class ValuesVisitor(private val done: (Map<String, Any>) -> Unit) : AnnotationVisitor(Opcodes.ASM9) {
        private val values = mutableMapOf<String, Any>()

        override fun visit(key: String, value: Any) {
            values[key] = value
        }

        override fun visitEnd() = done(values)
    }

    private class MethodReader(
        isStatic: Boolean,
        descriptor: String,
        private val done: (Map<String, Any>, List<String>, Set<Int>, Boolean) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val types = org.objectweb.asm.Type.getArgumentTypes(descriptor)
        private var values: Map<String, Any>? = null
        private var nullableResult = false
        private val nullable = mutableSetOf<Int>()
        private val parameterNames = arrayOfNulls<String>(types.size)
        private val slots = IntArray(types.size).also {
            var slot = if (isStatic) 0 else 1
            for (i in types.indices) {
                it[i] = slot
                slot += types[i].size
            }
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (isNullable(descriptor)) nullableResult = true
            if (descriptor != FUNCTION) return null
            return ValuesVisitor { values = it }
        }

        override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (isNullable(descriptor)) nullable += parameter
            return null
        }

        override fun visitParameter(name: String?, access: Int) {
            val index = parameterNames.indexOfFirst { it == null }
            if (index >= 0 && name != null) parameterNames[index] = name
        }

        override fun visitLocalVariable(name: String, d: String, s: String?, start: Label, end: Label, index: Int) {
            val parameter = slots.indexOf(index)
            if (parameter >= 0 && parameterNames[parameter] == null) parameterNames[parameter] = name
        }

        override fun visitEnd() {
            values?.let { done(it, parameterNames.mapIndexed { i, n -> n ?: "arg$i" }, nullable, nullableResult) }
        }
    }
}

/** A class the host API names as a value type: a record, a Kotlin data class, an enum or a sealed type. */
internal data class ClassShape(
    val name: String,
    val kind: Kind,
    /** Components of a record or data class, in order, with their nullability. */
    val components: List<Component>,
    /** Constants of an enum, or permitted subclasses of a sealed type. */
    val cases: List<String>,
) {
    enum class Kind { RECORD, DATA, ENUM, SEALED, OBJECT, OTHER }

    data class Component(val name: String, val type: JvmType, val nullable: Boolean)
}

/** Class files by binary name, read only when the host interface needs one. */
internal class ClassIndex {
    private val bytes = HashMap<String, () -> ByteArray>()
    private val shapes = HashMap<String, ClassShape?>()

    fun add(bytes: ByteArray, supplier: () -> ByteArray) {
        this.bytes[ClassReader(bytes).className.replace('/', '.')] = supplier
    }

    fun add(name: String, data: ByteArray) {
        bytes[name] = { data }
    }

    fun shape(name: String): ClassShape? = shapes.getOrPut(name) { bytes[name]?.let { read(name, it()) } }

    private fun read(name: String, data: ByteArray): ClassShape {
        val reader = ShapeReader()
        ClassReader(data).accept(reader, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        return reader.shape(name)
    }

    private class ShapeReader : ClassVisitor(Opcodes.ASM9) {
        private var access = 0
        private var superName: String? = null
        private val recordComponents = mutableListOf<ClassShape.Component>()
        private val fields = mutableListOf<ClassShape.Component>()
        private val enumConstants = mutableListOf<String>()
        private val permitted = mutableListOf<String>()
        private val componentMethods = mutableSetOf<String>()
        private var hasInstanceField = false

        override fun visit(v: Int, access: Int, name: String, sig: String?, superName: String?, ifs: Array<String>?) {
            this.access = access
            this.superName = superName
        }

        override fun visitRecordComponent(name: String, descriptor: String, signature: String?) =
            null.also { recordComponents += ClassShape.Component(name, type(signature ?: descriptor), false) }

        override fun visitPermittedSubclass(permittedSubclass: String) {
            permitted += permittedSubclass.replace('/', '.')
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            if (access and Opcodes.ACC_ENUM != 0) enumConstants += name
            if (access and Opcodes.ACC_STATIC != 0) {
                if (name == "INSTANCE") hasInstanceField = true
                return null
            }
            val index = fields.size
            fields += ClassShape.Component(name, type(signature ?: descriptor), false)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(d: String, visible: Boolean): AnnotationVisitor? {
                    if (HostScanner.isNullable(d)) fields[index] = fields[index].copy(nullable = true)
                    return null
                }
            }
        }

        override fun visitMethod(access: Int, name: String, d: String, s: String?, e: Array<String>?): MethodVisitor? {
            if (name.startsWith("component") && d.startsWith("()")) componentMethods += name
            return null
        }

        fun shape(name: String): ClassShape {
            val simple = name.substringAfterLast('.').substringAfterLast('$')
            val withNullability = recordComponents.map { c -> fields.firstOrNull { it.name == c.name }?.let { c.copy(nullable = it.nullable) } ?: c }
            return when {
                access and Opcodes.ACC_ENUM != 0 -> ClassShape(simple, ClassShape.Kind.ENUM, emptyList(), enumConstants)
                superName == "java/lang/Record" -> ClassShape(simple, ClassShape.Kind.RECORD, withNullability, emptyList())
                permitted.isNotEmpty() -> ClassShape(simple, ClassShape.Kind.SEALED, emptyList(), permitted)
                componentMethods.isNotEmpty() && fields.size >= componentMethods.size &&
                    (1..componentMethods.size).all { "component$it" in componentMethods } ->
                    ClassShape(simple, ClassShape.Kind.DATA, fields.take(componentMethods.size), emptyList())
                hasInstanceField && fields.isEmpty() -> ClassShape(simple, ClassShape.Kind.OBJECT, emptyList(), emptyList())
                else -> ClassShape(simple, ClassShape.Kind.OTHER, emptyList(), emptyList())
            }
        }

        private fun type(text: String): JvmType = JvmSignature(text).field()
    }
}
