package org.quartzlang.embed.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.quartzlang.embed.api.EmbedLayout
import org.quartzlang.embed.api.ModuleMetadata
import org.quartzlang.embed.api.QuartzAttribute

/** Compiles one module in a JVM that has the foreign function API, through the JVM bindings. */
abstract class CompileWorker : WorkAction<CompileWorker.Parameters> {
    interface Parameters : WorkParameters {
        val module: Property<String>
        val sources: ListProperty<String>
        val sourceNames: ListProperty<String>
        val manifest: RegularFileProperty
        val interfaces: MapProperty<String, String>
        /** `rule=level`, in order; later entries win, `*` is every rule. */
        val levels: ListProperty<String>
        val bindings: RegularFileProperty
        val handles: RegularFileProperty
        val keepLines: Property<Boolean>
        val facadePackage: Property<String>
        val facadeClass: Property<String>
        val generateFacade: Property<Boolean>
        val destinationDirectory: DirectoryProperty
        val facadeDirectory: DirectoryProperty
        val report: RegularFileProperty
    }

    override fun execute() {
        val p = parameters
        val id = p.module.get()
        val inputs = p.interfaces.get().map { (name, text) -> name to text } +
            p.sources.get().zip(p.sourceNames.get()).map { (path, name) -> name to java.io.File(path).readText() }
        val levels = p.levels.get().map { it.substringBefore('=') to level(it.substringAfter('=')) }
        val compiled = BindingsCompiler.compile(inputs, p.manifest.orNull?.asFile?.readText(), p.keepLines.get(), levels)
        val program = compiled.program
        if (program == null) {
            CompileReport(false, compiled.diagnostics, emptyList()).write(p.report.get().asFile)
            return
        }
        val out = p.destinationDirectory.get().asFile
        val binary = out.resolve(EmbedLayout.binaryPath(id))
        binary.parentFile.mkdirs()
        binary.writeBytes(program.binary)
        val required = program.imports.filter { it.required }
        val kits = EmbedLayout.KIT_NAMES.filter { kit ->
            val pkg = EmbedLayout.kitPackage(kit)
            required.any { it.pkg == pkg || it.name.startsWith("$pkg.") }
        }.toSet()
        val requiredNames = required.map { it.name }.toSet()
        val bindings = ModuleMetadata.parse(p.bindings.get().asFile.readText()).bindings()
            .filter { it.importName() in requiredNames }
        out.resolve(EmbedLayout.metadataPath(id)).writeText(ModuleMetadata(kits, bindings, attributed(program)).write())
        val notes = mutableListOf<String>()
        if (p.generateFacade.get()) {
            val handles = p.handles.get().asFile.readLines().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val (source, skipped) = FacadeWriter.write(program, id, p.facadePackage.get(), p.facadeClass.get(), handles)
            val dir = p.facadeDirectory.get().asFile.resolve(p.facadePackage.get().replace('.', '/'))
            dir.mkdirs()
            dir.resolve(p.facadeClass.get() + ".java").writeText(source)
            skipped.forEach { notes += "facade: $it" }
        }
        CompileReport(true, compiled.diagnostics, notes).write(p.report.get().asFile)
    }

    private fun attributed(program: ProgramInfo): List<ModuleMetadata.AttributedFunction> =
        program.exports.flatMap { f ->
            f.attributes.map { a ->
                val args = a.args.map { QuartzAttribute.Arg(it.key, QuartzAttribute.Kind.valueOf(it.kind.name), it.encoded()) }
                ModuleMetadata.AttributedFunction(f.name, f.params.map { it.name }, QuartzAttribute(a.name, args),
                    a.filter ?: "")
            }
        }

    private fun level(text: String) = when (text) {
        "ignore" -> 0
        "warning" -> 1
        "error" -> 2
        else -> error("unknown warning level $text")
    }
}
