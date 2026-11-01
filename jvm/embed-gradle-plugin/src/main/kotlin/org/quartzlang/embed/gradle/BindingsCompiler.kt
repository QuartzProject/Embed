package org.quartzlang.embed.gradle

import org.quartzlang.jvm.Attribute
import org.quartzlang.jvm.CompileException
import org.quartzlang.jvm.CompileOptions
import org.quartzlang.jvm.Diagnostic
import org.quartzlang.jvm.Export
import org.quartzlang.jvm.Program
import org.quartzlang.jvm.Quartz
import org.quartzlang.jvm.Source
import org.quartzlang.jvm.Type
import org.quartzlang.jvm.WarningLevel

/**
 * Compiles a module through the JVM bindings and reads what the build needs from the program: its module bytes,
 * exports with parameter names and attributes, imports and every type they reach. Runs only in the compiler worker,
 * a JVM with the foreign function API.
 */
internal object BindingsCompiler {
    /** Compiles sources with per-rule warning levels (0 ignore, 1 warning, 2 error); the program is null on failure. */
    fun compile(sources: List<Pair<String, String>>, manifest: String?, keepLines: Boolean,
                levels: List<Pair<String, Int>>): Compiled {
        var options = CompileOptions(true, keepLines, manifest)
        levels.forEach { (rule, level) -> options = options.withRule(rule, WarningLevel.entries[level]) }
        val program = try {
            Quartz.compile(sources.map { Source.of(it.first, it.second) }, options)
        } catch (e: CompileException) {
            return Compiled(null, e.diagnostics().map(::diagnostic))
        }
        return program.use { Compiled(Reader(it).read(), it.diagnostics().map(::diagnostic)) }
    }

    private fun diagnostic(d: Diagnostic) = CompilerDiagnostic(
        severity = d.severity().name.lowercase(),
        rule = d.rule(),
        file = d.file(),
        line = d.line(),
        column = d.column(),
        message = d.message(),
    )

    private class Reader(private val program: Program) {
        private val types = sortedMapOf<Int, QuartzType>()

        fun read(): ProgramInfo {
            val binary = program.binary().orElseThrow { IllegalStateException("the compiler produced no module") }
            val exports = program.exports().map(::function)
            val imports = program.imports().map { ImportInfo(it.name(), it.packageName(), it.required(), it.builtIn()) }
            return ProgramInfo(binary, exports, imports, types.toMap())
        }

        private fun function(e: Export) = FunctionInfo(
            name = e.name(),
            pkg = e.packageName(),
            params = e.parameterNames().zip(e.signature().params()).map { (name, type) -> ParamInfo(name, type(type)) },
            result = type(e.signature().result()),
            visibility = e.visibility().ordinal,
            suspend = e.isSuspend,
            attributes = e.attributes().map { a -> AttributeInfo(a.name(), a.filter()?.name(), a.arguments().map(::arg)) },
            doc = e.doc(),
        )

        private fun arg(a: Attribute.Argument): AttributeArg {
            val kind = AttributeArg.Kind.valueOf(a.kind().name)
            val value = a.value()
            return AttributeArg(
                key = a.key(),
                kind = kind,
                intValue = when (value) {
                    is Long -> value
                    is Boolean -> if (value) 1L else 0L
                    else -> 0L
                },
                floatValue = value as? Double ?: 0.0,
                text = value as? String ?: "",
            )
        }

        /** Registers [t] and every type it reaches; returns its id, 0 for none. */
        private fun type(t: Type?): Int {
            if (t == null || t.id() == 0) return 0
            val id = t.id()
            if (id in types) return id
            val kind = QuartzType.Kind.entries.getOrElse(t.kind().ordinal) { QuartzType.Kind.OTHER }
            types[id] = QuartzType(kind, "", "", 0, 0, emptyList(), emptyList(), false)
            types[id] = QuartzType(
                kind = kind,
                name = t.name(),
                pkg = t.packageName(),
                element = type(t.element()),
                key = if (kind == QuartzType.Kind.MAP) type(t.key()) else 0,
                fields = t.fields().map { QuartzType.Field(it.name(), type(it.type()), it.doc()) },
                cases = t.cases().map { c -> QuartzType.Case(c.name(), c.payload().map { type(it.type()) }, c.doc()) },
                pointerNullable = t.isPointerNullable,
                doc = t.doc(),
            )
            return id
        }
    }
}

/** What the compiler produced: the program's description, or null, and every diagnostic. */
internal class Compiled(val program: ProgramInfo?, val diagnostics: List<CompilerDiagnostic>)
