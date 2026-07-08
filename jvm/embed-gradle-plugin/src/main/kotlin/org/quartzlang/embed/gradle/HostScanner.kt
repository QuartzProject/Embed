package org.quartzlang.embed.gradle

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
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
)

/** What the classes hold: annotated methods and `@QuartzHandle` classes by Quartz name. */
internal class ScanResult {
    val methods = mutableListOf<ScannedMethod>()
    val handles = sortedMapOf<String, String>()
}

/** Reads class files with ASM, without loading them, so a class whose dependencies are missing is still read. */
internal object HostScanner {
    private const val FUNCTION = "Lorg/quartzlang/embed/api/QuartzFunction;"
    private const val HANDLE = "Lorg/quartzlang/embed/api/QuartzHandle;"
    private val marker = "Lorg/quartzlang/embed/api/Quartz".toByteArray()

    fun scan(files: Iterable<File>): ScanResult {
        val result = ScanResult()
        for (file in files) {
            when {
                file.isDirectory -> file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sorted()
                    .forEach { read(it.readBytes(), result) }

                file.isFile && (file.name.endsWith(".jar") || file.name.endsWith(".zip")) -> ZipFile(file).use { zip ->
                    zip.stream().filter { it.name.endsWith(".class") }.forEach { entry ->
                        read(zip.getInputStream(entry).use { it.readBytes() }, result)
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

    private fun isNullable(descriptor: String) = descriptor.endsWith("/Nullable;")

    private class Visitor(private val result: ScanResult) : ClassVisitor(Opcodes.ASM9) {
        private lateinit var owner: String

        override fun visit(v: Int, access: Int, name: String, sig: String?, superName: String?, ifs: Array<String>?) {
            owner = name.replace('/', '.')
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor != HANDLE) return null
            return NameVisitor { given -> result.handles[given ?: owner.substringAfterLast('.').substringAfterLast('$')] = owner }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
            val isStatic = access and Opcodes.ACC_STATIC != 0
            return MethodReader(isStatic, descriptor) { quartzName, names, nullable, nullableResult ->
                result.methods += ScannedMethod(owner, name, quartzName ?: name, descriptor, signature, isStatic, names,
                    nullable, nullableResult)
            }
        }
    }

    private class NameVisitor(private val done: (String?) -> Unit) : AnnotationVisitor(Opcodes.ASM9) {
        private var name: String? = null

        override fun visit(key: String, value: Any) {
            if (key == "name" && value is String && value.isNotEmpty()) name = value
        }

        override fun visitEnd() = done(name)
    }

    private class MethodReader(
        private val isStatic: Boolean,
        descriptor: String,
        private val done: (String?, List<String>, Set<Int>, Boolean) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val types = org.objectweb.asm.Type.getArgumentTypes(descriptor)
        private var annotated = false
        private var quartzName: String? = null
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
            annotated = true
            return NameVisitor { quartzName = it }
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
            if (annotated) {
                done(quartzName, parameterNames.mapIndexed { i, n -> n ?: "arg$i" }, nullable, nullableResult)
            }
        }
    }
}
