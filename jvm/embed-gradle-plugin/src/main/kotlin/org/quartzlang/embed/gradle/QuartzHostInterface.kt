package org.quartzlang.embed.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.quartzlang.embed.api.ModuleMetadata

/**
 * Generates the host interface from `@QuartzFunction` methods and `@QuartzHandle` classes, so Quartz sources are
 * type-checked against the real host API.
 */
@CacheableTask
abstract class QuartzHostInterface : DefaultTask() {
    /** Classes to scan: the source set's own output and its compile classpath, or only the classpath. */
    @get:CompileClasspath
    abstract val classes: ConfigurableFileCollection

    /** Handle types for classes the project cannot annotate, Quartz name to class name. */
    @get:Input
    abstract val handles: MapProperty<String, String>

    /** The package of the generated interface; free functions are imported as `<package>.<name>`. */
    @get:Input
    abstract val hostPackage: Property<String>

    /** Sources of the host API, for the documentation the interface copies. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val scan = HostScanner.scan(classes.files)
        val host = try {
            val docs = HostDocs.read(sources.asFileTree.matching { it.include("**/*.kt", "**/*.java") }.files)
            HostInterface.of(scan, handles.get(), hostPackage.get(), docs)
        } catch (e: IllegalArgumentException) {
            throw GradleException("Quartz host interface:\n${e.message}", e)
        }
        val out = destinationDirectory.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        if (host.bindings.isNotEmpty() || host.handles.isNotEmpty()) {
            out.resolve(INTERFACE).writeText(host.qzi)
        }
        out.resolve(BINDINGS).writeText(ModuleMetadata(emptySet(), host.bindings).write())
        out.resolve(HANDLES).writeText(host.classes.entries.joinToString("") { (k, v) -> "$k=$v\n" })
        logger.info("Quartz host interface: {} functions, {} handles", host.bindings.size, host.handles.size)
    }

    internal companion object {
        const val INTERFACE = "host.qzi"
        const val BINDINGS = "bindings.properties"
        const val HANDLES = "handles.properties"
    }
}
