package org.quartzlang.embed.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkerExecutor
import org.quartzlang.embed.api.EmbedLayout
import java.io.File
import javax.inject.Inject

/**
 * Compiles the Quartz sources of a source set into validated `.qzb` modules laid out as resources, with their
 * metadata and typed facades. Each module compiles as a whole; a change recompiles only the modules it touches.
 */
@CacheableTask
abstract class QuartzCompile : DefaultTask() {
    @get:InputFiles
    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: ConfigurableFileCollection

    /** The generated host interface, bindings and handles. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hostInterface: DirectoryProperty

    /** Kits the build makes available, by name. */
    @get:Input
    abstract val kits: SetProperty<String>

    /** The `.qzi` text of every kit the plugin knows. */
    @get:Input
    abstract val kitInterfaces: MapProperty<String, String>

    @get:Input
    abstract val warnings: Property<QuartzSeverity>

    @get:Input
    abstract val keepLines: Property<Boolean>

    /** The id of the module made of the sources outside any `package.toml` directory. */
    @get:Input
    abstract val defaultModule: Property<String>

    @get:Input
    abstract val generateFacade: Property<Boolean>

    @get:Input
    abstract val facadePackage: Property<String>

    /** A JVM of version 22 or newer, which has the foreign function API the bindings need. */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    /** A libquartz to use instead of the one inside the bindings jar. */
    @get:Internal
    abstract val library: Property<String>

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val facadeDirectory: DirectoryProperty

    @get:OutputDirectory
    @get:Optional
    abstract val reportDirectory: DirectoryProperty

    @get:Inject
    protected abstract val workers: WorkerExecutor

    @get:Inject
    protected abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun compile(changes: InputChanges) {
        val out = destinationDirectory.get().asFile
        val facades = facadeDirectory.get().asFile
        val reports = reportDirectory.get().asFile
        val files = collect()
        val modules = QuartzModules.group(files, defaultModule.get())
        val ids = modules.map { it.id }
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.firstOrNull()?.let {
            throw GradleException("Two Quartz modules are called '$it'; give each package.toml its own name")
        }
        val outdated = outdated(changes, modules)
        if (!changes.isIncremental) {
            fileSystem.delete { it.delete(out, facades, reports) }
        }
        removeStale(out, facades, ids.toSet())

        val host = hostInterface.get().asFile
        val interfaces = mutableMapOf<String, String>()
        host.resolve(QuartzHostInterface.INTERFACE).takeIf { it.isFile }?.let { interfaces["<host>.qzi"] = it.readText() }
        val queue = workers.processIsolation { spec ->
            spec.forkOptions { fork ->
                fork.executable = launcher.get().executablePath.asFile.absolutePath
                fork.jvmArgs("--enable-native-access=ALL-UNNAMED")
                library.orNull?.let { fork.systemProperty("quartz.library", it) }
            }
        }
        val paths = mutableMapOf<String, Map<String, String>>()
        for (module in modules.filter { it.id in outdated }) {
            val texts = module.files.associate { it.first to it.second.readText() }
            val used = texts.values.flatMap { QuartzSources.kitsUsed(it) }.toSet()
            val unknown = used - kitInterfaces.get().keys
            if (unknown.isNotEmpty()) {
                throw GradleException("Quartz module ${module.id} names unknown kits $unknown; the kits are " +
                    kitInterfaces.get().keys)
            }
            val granted = used intersect kits.get()
            val moduleInterfaces = interfaces + granted.associate { "<kit $it>.qzi" to kitInterfaces.get().getValue(it) }
            paths[module.id] = module.files.associate { it.first to it.second.path }
            queue.submit(CompileWorker::class.java) { p ->
                p.module.set(module.id)
                p.sources.set(module.files.map { it.second.path })
                p.sourceNames.set(module.files.map { it.first })
                module.manifest?.let { p.manifest.set(it) }
                p.interfaces.set(moduleInterfaces)
                p.kits.set(granted)
                p.bindings.set(host.resolve(QuartzHostInterface.BINDINGS))
                p.handles.set(host.resolve(QuartzHostInterface.HANDLES))
                p.keepLines.set(keepLines)
                p.facadePackage.set(facadePackage)
                p.facadeClass.set(QuartzModules.facadeClass(module.id))
                p.generateFacade.set(generateFacade)
                p.destinationDirectory.set(out)
                p.facadeDirectory.set(facades)
                p.report.set(reports.resolve("${module.id}.txt"))
            }
        }
        queue.await()
        report(paths, reports)
        if (ids.isNotEmpty()) {
            out.resolve(EmbedLayout.INDEX).also { it.parentFile.mkdirs() }.writeText(ids.joinToString("\n", postfix = "\n"))
        }
        logger.info("Quartz compiled {} of {} modules into {}", outdated.size, modules.size, out)
    }

    private fun collect(): List<Pair<String, File>> {
        val found = sortedMapOf<String, File>()
        source.asFileTree.matching { it.include("**/*.qz", "**/*.qzi", "**/package.toml") }.visit { details ->
            if (!details.isDirectory) {
                val previous = found.put(details.relativePath.pathString, details.file)
                if (previous != null) {
                    throw GradleException("Two Quartz sources have the path ${details.relativePath}: $previous and " +
                        "${details.file}")
                }
            }
        }
        return found.map { it.key to it.value }
    }

    /** Modules to compile: all of them, or the ones a changed source belongs to. */
    private fun outdated(changes: InputChanges, modules: List<QuartzModule>): Set<String> {
        val all = modules.map { it.id }.toSet()
        if (!changes.isIncremental) return all
        val changed = changes.getFileChanges(source).filter { it.fileType != org.gradle.api.file.FileType.DIRECTORY }
        if (changed.any { it.normalizedPath.endsWith("package.toml") }) return all
        val byPath = modules.flatMap { m -> m.files.map { it.second.absolutePath to m.id } }.toMap()
        val roots = modules.mapNotNull { m -> m.manifest?.parentFile?.absolutePath?.let { it to m.id } }
        val result = mutableSetOf<String>()
        for (change in changed) {
            val path = change.file.absolutePath
            result += byPath[path] ?: if (change.changeType == ChangeType.REMOVED) {
                roots.filter { path.startsWith(it.first + File.separator) }.maxByOrNull { it.first.length }?.second
                    ?: defaultModule.get()
            } else {
                continue
            }
        }
        return result intersect all
    }

    private fun removeStale(out: File, facades: File, ids: Set<String>) {
        out.resolve(EmbedLayout.MODULES).listFiles()?.forEach { file ->
            if (file.name.substringBeforeLast('.') !in ids) file.delete()
        }
        val live = ids.map { QuartzModules.facadeClass(it) + ".java" }.toSet()
        facades.walkTopDown().filter { it.isFile && it.name !in live }.forEach { it.delete() }
    }

    private fun report(paths: Map<String, Map<String, String>>, reports: File) {
        val failures = mutableListOf<String>()
        val warned = mutableListOf<String>()
        for ((id, names) in paths) {
            val text = reports.resolve("$id.txt").readText()
            val ok = text.startsWith("ok\n")
            val lines = text.substringAfter('\n').lines()
            lines.filter { it.startsWith("facade: ") }.forEach { logger.warn("w: Quartz module {}: {}", id, it) }
            val diagnostics = Diagnostic.parse(lines.filterNot { it.startsWith("facade: ") }.joinToString("\n"))
            val rendered = diagnostics.map { it.render(names) }
            if (!ok) {
                failures += rendered.filter { !it.contains(": warning: ") || warnings.get() == QuartzSeverity.ERROR }
                    .ifEmpty { listOf("module $id: compilation failed") }
            } else {
                warned += diagnostics.filter { it.severity == "warning" }.map { it.render(names) }
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException((listOf("Quartz compilation failed:") + failures).joinToString("\n"))
        }
        when (warnings.get()) {
            QuartzSeverity.ERROR -> if (warned.isNotEmpty()) {
                throw GradleException((listOf("Quartz warnings are errors in this build:") + warned).joinToString("\n"))
            }
            QuartzSeverity.WARN -> warned.forEach { logger.warn("w: {}", it) }
            QuartzSeverity.IGNORE -> Unit
        }
    }
}
