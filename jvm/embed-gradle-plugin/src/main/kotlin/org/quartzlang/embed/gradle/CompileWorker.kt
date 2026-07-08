package org.quartzlang.embed.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.quartzlang.embed.api.EmbedLayout
import org.quartzlang.embed.api.ModuleMetadata
import org.quartzlang.jvm.CompileException
import org.quartzlang.jvm.CompileOptions
import org.quartzlang.jvm.Quartz
import org.quartzlang.jvm.Source

/** Compiles one module in a JVM that has the foreign function API, through the bindings and their libquartz. */
abstract class CompileWorker : WorkAction<CompileWorker.Parameters> {
    interface Parameters : WorkParameters {
        val module: Property<String>
        val sources: ListProperty<String>
        val sourceNames: ListProperty<String>
        val manifest: RegularFileProperty
        val interfaces: MapProperty<String, String>
        val kits: SetProperty<String>
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
        val report = p.report.get().asFile
        report.parentFile.mkdirs()
        val inputs = p.interfaces.get().map { (name, text) -> Source.of(name, text) } +
            p.sources.get().zip(p.sourceNames.get()).map { (path, name) -> Source.of(name, java.io.File(path).readText()) }
        val manifest = p.manifest.orNull?.asFile?.readText()
        val options = CompileOptions.DEFAULT.withBinary(true).withLines(p.keepLines.get()).withManifest(manifest)
        val program = try {
            Quartz.compile(inputs, options)
        } catch (e: CompileException) {
            report.writeText("failed\n" + e.diagnostics())
            return
        }
        program.use {
            val out = p.destinationDirectory.get().asFile
            val binary = out.resolve(EmbedLayout.binaryPath(id))
            binary.parentFile.mkdirs()
            binary.writeBytes(program.binary().orElseThrow())
            val declared = ModuleMetadata.parse(p.bindings.get().asFile.readText()).bindings()
                .filter { program.findImport(it.importName()).isPresent }
            out.resolve(EmbedLayout.metadataPath(id)).writeText(ModuleMetadata(p.kits.get(), declared).write())
            val notes = StringBuilder()
            if (p.generateFacade.get()) {
                val handles = p.handles.get().asFile.readLines().filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
                val functions = p.sources.get().flatMap { QuartzSources.publicFunctions(java.io.File(it).readText()) }
                val (source, skipped) = FacadeWriter.write(program, id, p.facadePackage.get(), p.facadeClass.get(),
                    functions, handles)
                val dir = p.facadeDirectory.get().asFile.resolve(p.facadePackage.get().replace('.', '/'))
                dir.mkdirs()
                dir.resolve(p.facadeClass.get() + ".java").writeText(source)
                skipped.forEach { notes.append("facade: ").append(it).append('\n') }
            }
            report.writeText("ok\n" + notes + program.diagnostics())
        }
    }
}
