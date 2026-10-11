package org.quartzlang.embed.gradle

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * The parts of libquartz's C interface the build needs and the JVM bindings do not wrap: compiling with warning
 * levels, structured diagnostics, exports with parameter names, type names and function attributes. Runs only in
 * the compiler worker, a JVM with the foreign function API.
 */
internal object NativeQuartz {
    private const val API_LEVEL = 4
    private val linker = Linker.nativeLinker()
    private val lib: SymbolLookup by lazy { load() }
    private val handles = HashMap<String, MethodHandle>()

    private val sourceLayout = MemoryLayout.structLayout(ADDRESS, ADDRESS, JAVA_LONG)
    private val optionsLayout = MemoryLayout.structLayout(JAVA_BOOLEAN, JAVA_BOOLEAN, MemoryLayout.paddingLayout(6),
        ADDRESS)
    private val levelLayout = MemoryLayout.structLayout(ADDRESS, JAVA_INT, MemoryLayout.paddingLayout(4))
    private val argLayout = MemoryLayout.structLayout(ADDRESS, JAVA_INT, MemoryLayout.paddingLayout(4), JAVA_LONG,
        JAVA_DOUBLE, ADDRESS)

    /** Compiles sources with per-rule warning levels; the program is null when compilation failed. */
    fun compile(sources: List<Pair<String, String>>, manifest: String?, keepLines: Boolean,
                levels: List<Pair<String, Int>>): Compiled {
        check((call("qz_api_level", JAVA_INT) as Int) >= API_LEVEL) { "libquartz is older than API level $API_LEVEL" }
        Arena.ofConfined().use { arena ->
            val array = arena.allocate(sourceLayout, maxOf(1, sources.size).toLong())
            sources.forEachIndexed { i, (name, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                val segment = arena.allocate(maxOf(1, bytes.size).toLong())
                MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
                val entry = array.asSlice(i * sourceLayout.byteSize(), sourceLayout)
                entry.set(ADDRESS, 0, arena.allocateFrom(name))
                entry.set(ADDRESS, 8, segment)
                entry.set(JAVA_LONG, 16, bytes.size.toLong())
            }
            val options = arena.allocate(optionsLayout)
            options.set(JAVA_BOOLEAN, 0, true)
            options.set(JAVA_BOOLEAN, 1, keepLines)
            options.set(ADDRESS, 8, manifest?.let { arena.allocateFrom(it) } ?: MemorySegment.NULL)
            val rules = arena.allocate(levelLayout, maxOf(1, levels.size).toLong())
            levels.forEachIndexed { i, (rule, level) ->
                rules.set(ADDRESS, i * levelLayout.byteSize(), arena.allocateFrom(rule))
                rules.set(JAVA_INT, i * levelLayout.byteSize() + 8, level)
            }
            val out = arena.allocate(ADDRESS)
            val program = call("qz_compile_with", ADDRESS, array, sources.size.toLong(), options, rules,
                levels.size.toLong(), out) as MemorySegment
            val diagnostics = readDiagnostics(out.get(ADDRESS, 0))
            if (program.address() == 0L) return Compiled(null, diagnostics)
            try {
                return Compiled(Introspection(program).read(), diagnostics)
            } finally {
                call("qz_program_free", null, program)
            }
        }
    }

    private fun readDiagnostics(d: MemorySegment): List<CompilerDiagnostic> {
        if (d.address() == 0L) return emptyList()
        try {
            val count = call("qz_diagnostics_count", JAVA_LONG, d) as Long
            return (0 until count).map { i ->
                Arena.ofConfined().use { arena ->
                    val cells = arena.allocate(JAVA_INT, 4)
                    call("qz_diagnostic_range", null, d, i, cells, cells.asSlice(4), cells.asSlice(8), cells.asSlice(12))
                    CompilerDiagnostic(
                        severity = when (call("qz_diagnostic_severity", JAVA_INT, d, i) as Int) {
                            0 -> "error"
                            1 -> "warning"
                            else -> "note"
                        },
                        rule = string(call("qz_diagnostic_rule", ADDRESS, d, i)),
                        file = string(call("qz_diagnostic_file", ADDRESS, d, i)),
                        line = cells.getAtIndex(JAVA_INT, 0),
                        column = cells.getAtIndex(JAVA_INT, 1),
                        message = string(call("qz_diagnostic_message", ADDRESS, d, i)),
                    )
                }
            }
        } finally {
            call("qz_diagnostics_free", null, d)
        }
    }

    /** Reads what a compiled program offers and needs, while it is alive. */
    private class Introspection(private val p: MemorySegment) {
        private val types = sortedMapOf<Int, QuartzType>()

        fun read(): ProgramInfo {
            val length = Arena.ofConfined().use { arena ->
                val size = arena.allocate(JAVA_LONG)
                val bytes = call("qz_program_binary", ADDRESS, p, size) as MemorySegment
                val out = bytes.reinterpret(size.get(JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
                call("qz_free", null, bytes)
                out
            }
            val exports = (0 until int("qz_program_export_count", p)).map { function(int("qz_program_export", p, it)) }
            val imports = (0 until int("qz_program_import_count", p)).map { i ->
                ImportInfo(
                    name = string(call("qz_program_import_name", ADDRESS, p, i)),
                    pkg = string(call("qz_program_import_package", ADDRESS, p, i)),
                    required = call("qz_program_import_is_required", JAVA_BOOLEAN, p, i) as Boolean,
                    builtIn = call("qz_program_import_is_native", JAVA_BOOLEAN, p, i) as Boolean,
                )
            }
            return ProgramInfo(length, exports, imports, types.toMap())
        }

        private fun function(index: Int): FunctionInfo {
            val arity = int("qz_program_function_arity", p, index)
            val attributes = (0 until int("qz_program_function_attribute_count", p, index)).map { a ->
                val filter = int("qz_program_function_attribute_filter", p, index, a)
                AttributeInfo(
                    name = string(call("qz_program_function_attribute_name", ADDRESS, p, index, a)),
                    filter = if (filter == -1) null else string(call("qz_program_function_name", ADDRESS, p, filter)),
                    args = (0 until int("qz_program_function_attribute_arg_count", p, index, a)).map { arg(index, a, it) },
                )
            }
            return FunctionInfo(
                name = string(call("qz_program_function_name", ADDRESS, p, index)),
                pkg = string(call("qz_program_function_package", ADDRESS, p, index)),
                params = (0 until arity).map { i ->
                    ParamInfo(string(call("qz_program_function_param_name", ADDRESS, p, index, i)),
                        type(int("qz_program_function_param_type", p, index, i)))
                },
                result = type(int("qz_program_function_result_type", p, index)),
                visibility = int("qz_program_function_visibility", p, index),
                suspend = call("qz_program_function_is_suspend", JAVA_BOOLEAN, p, index) as Boolean,
                attributes = attributes,
            )
        }

        private fun arg(function: Int, attribute: Int, index: Int): AttributeArg = Arena.ofConfined().use { arena ->
            val out = arena.allocate(argLayout)
            call("qz_program_function_attribute_arg", JAVA_BOOLEAN, p, function, attribute, index, out)
            AttributeArg(
                key = string(out.get(ADDRESS, 0)),
                kind = AttributeArg.Kind.entries[out.get(JAVA_INT, 8)],
                intValue = out.get(JAVA_LONG, 16),
                floatValue = out.get(JAVA_DOUBLE, 24),
                text = string(out.get(ADDRESS, 32)),
            )
        }

        /** Registers type `id` and every type it reaches; returns `id`. */
        private fun type(id: Int): Int {
            if (id == 0 || id in types) return id
            val kind = Arena.ofConfined().use { arena ->
                QuartzType.Kind.entries.getOrElse(call("qz_type_kind", JAVA_INT, p, id, arena.allocate(JAVA_INT)) as Int) {
                    QuartzType.Kind.OTHER
                }
            }
            val placeholder = QuartzType(kind, "", "", 0, 0, emptyList(), emptyList(), false)
            types[id] = placeholder
            val fields = mutableListOf<QuartzType.Field>()
            val cases = mutableListOf<QuartzType.Case>()
            when (kind) {
                QuartzType.Kind.STRUCT, QuartzType.Kind.TUPLE -> for (i in 0 until int("qz_type_field_count", p, id)) {
                    val name = if (kind == QuartzType.Kind.STRUCT) string(call("qz_type_field_name", ADDRESS, p, id, i)) else ""
                    fields += QuartzType.Field(name, type(fieldType(id, i)))
                }
                QuartzType.Kind.ENUM -> for (v in 0 until int("qz_type_variant_count", p, id)) {
                    val payload = (0 until int("qz_type_variant_payload_count", p, id, v)).map { i ->
                        type(payloadType(id, v, i))
                    }
                    cases += QuartzType.Case(string(call("qz_type_variant_name", ADDRESS, p, id, v)), payload)
                }
                else -> Unit
            }
            types[id] = placeholder.copy(
                name = string(call("qz_type_name", ADDRESS, p, id)),
                pkg = string(call("qz_type_package", ADDRESS, p, id)),
                element = type(int("qz_type_elem", p, id)),
                key = if (kind == QuartzType.Kind.MAP) type(int("qz_type_key", p, id)) else 0,
                fields = fields,
                cases = cases,
                pointerNullable = kind == QuartzType.Kind.NULLABLE &&
                    call("qz_type_nullable_is_pointer", JAVA_BOOLEAN, p, id) as Boolean,
            )
            return id
        }

        private fun fieldType(id: Int, i: Int) = Arena.ofConfined().use { arena ->
            call("qz_type_field", JAVA_INT, p, id, i, arena.allocate(JAVA_INT)) as Int
        }

        private fun payloadType(id: Int, v: Int, i: Int) = Arena.ofConfined().use { arena ->
            call("qz_type_variant_payload", JAVA_INT, p, id, v, i, arena.allocate(JAVA_INT)) as Int
        }
    }

    private fun int(name: String, vararg args: Any): Int = call(name, JAVA_INT, *args) as Int

    private fun string(segment: Any?): String {
        val s = segment as MemorySegment
        return if (s.address() == 0L) "" else s.reinterpret(Long.MAX_VALUE).getString(0)
    }

    /** Calls a C function, deriving its descriptor from the result layout and the argument types. */
    private fun call(name: String, result: ValueLayout?, vararg args: Any): Any? {
        val layouts = args.map {
            when (it) {
                is MemorySegment -> ADDRESS
                is Int -> JAVA_INT
                is Long -> JAVA_LONG
                else -> error("unsupported argument $it")
            }
        }
        val key = name + layouts.joinToString(",", "(", ")") + result
        val handle = synchronized(handles) {
            handles.getOrPut(key) {
                val symbol = lib.find(name).orElseThrow { UnsatisfiedLinkError("libquartz has no $name") }
                val descriptor = if (result == null) FunctionDescriptor.ofVoid(*layouts.toTypedArray())
                else FunctionDescriptor.of(result, *layouts.toTypedArray())
                linker.downcallHandle(symbol, descriptor)
            }
        }
        return handle.invokeWithArguments(*args)
    }

    /** The same libquartz the JVM bindings load: `quartz.library`, then `java.library.path`, then their jar. */
    private fun load(): SymbolLookup {
        System.getProperty("quartz.library")?.takeIf { it.isNotEmpty() }?.let {
            return SymbolLookup.libraryLookup(Path.of(it), Arena.global())
        }
        val name = System.mapLibraryName("quartz")
        System.getProperty("java.library.path", "").split(File.pathSeparator).filter { it.isNotEmpty() }
            .map { Path.of(it, name) }.firstOrNull { Files.isRegularFile(it) }
            ?.let { return SymbolLookup.libraryLookup(it, Arena.global()) }
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val arch = when (val a = System.getProperty("os.arch", "").lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> a
        }
        val family = if (os.startsWith("windows")) "windows" else if (os.startsWith("mac")) "macos" else "linux"
        val resource = "/natives/$family-$arch/$name"
        val bindings = Class.forName("org.quartzlang.jvm.Quartz")
        val stream = bindings.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError("no libquartz for $family-$arch: set -Pquartz.library=<file>")
        val dir = Files.createTempDirectory("quartz-embed")
        val file = dir.resolve(name)
        stream.use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        return SymbolLookup.libraryLookup(file, Arena.global())
    }
}

/** What qz_compile_with produced: the program's description, or null, and every diagnostic. */
internal class Compiled(val program: ProgramInfo?, val diagnostics: List<CompilerDiagnostic>)
