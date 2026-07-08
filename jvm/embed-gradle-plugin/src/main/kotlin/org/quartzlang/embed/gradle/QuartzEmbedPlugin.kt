package org.quartzlang.embed.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.quartzlang.embed.api.EmbedLayout


/** Adds Quartz to a JVM project as a source language: `src/<sourceSet>/quartz` is compiled into the jar. */
class QuartzEmbedPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("quartz", QuartzExtension::class.java)
        extension.warnings.convention(QuartzSeverity.WARN)
        extension.keepLines.convention(true)
        extension.hostPackage.convention("")
        extension.scanOwnClasses.convention(true)
        extension.generateFacade.convention(true)
        extension.facadePackage.convention(project.provider {
            project.group.toString().takeIf { it.isNotEmpty() }?.let { "$it.quartz" } ?: "quartz"
        })
        extension.moduleName.convention(project.provider { project.name })
        extension.addRuntimeDependency.convention(true)
        extension.runtimeVersion.convention(EmbedVersion.current)
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        extension.compilerLauncher.convention(toolchains.launcherFor {
            it.languageVersion.set(JavaLanguageVersion.of(maxOf(25, Runtime.version().feature())))
        })

        project.plugins.withType(JavaBasePlugin::class.java) {
            project.extensions.getByType(SourceSetContainer::class.java).configureEach { sourceSet ->
                register(project, extension, sourceSet)
            }
            addRuntimeDependency(project, extension)
        }
    }

    private fun register(project: Project, extension: QuartzExtension, sourceSet: SourceSet) {
        val quartz = project.objects.sourceDirectorySet("quartz", "${sourceSet.name} Quartz sources")
        quartz.srcDir("src/${sourceSet.name}/quartz")
        quartz.filter.include("**/*.qz", "**/*.qzi", "**/package.toml")
        sourceSet.extensions.add(SourceDirectorySet::class.java, "quartz", quartz)
        val generated = project.layout.buildDirectory.dir("generated/quartz/${sourceSet.name}")

        val host = project.tasks.register(sourceSet.getTaskName("generate", "QuartzHostInterface"),
            QuartzHostInterface::class.java) { task ->
            task.group = GROUP
            task.description = "Generates the ${sourceSet.name} host interface from @QuartzFunction methods."
            task.classes.from(extension.scanOwnClasses.map { own ->
                if (own) sourceSet.output.classesDirs + sourceSet.compileClasspath else sourceSet.compileClasspath
            })
            task.handles.set(extension.handles)
            task.hostPackage.set(extension.hostPackage)
            task.destinationDirectory.set(generated.map { it.dir("host") })
        }

        val compile = project.tasks.register(sourceSet.getCompileTaskName("quartz"), QuartzCompile::class.java) { task ->
            task.group = GROUP
            task.description = "Compiles the ${sourceSet.name} Quartz sources into validated modules."
            task.source.from(quartz.sourceDirectories)
            task.hostInterface.set(host.flatMap { it.destinationDirectory })
            task.kits.set(extension.kits)
            task.kitInterfaces.set(project.provider { kitInterfaces() })
            task.warnings.set(extension.warnings)
            task.keepLines.set(extension.keepLines)
            task.defaultModule.set(extension.moduleName.map {
                if (sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) it else "$it-${sourceSet.name}"
            })
            task.generateFacade.set(extension.generateFacade)
            task.facadePackage.set(extension.facadePackage)
            task.launcher.set(extension.compilerLauncher)
            task.library.set(project.providers.gradleProperty("quartz.library"))
            task.destinationDirectory.set(generated.map { it.dir("resources") })
            task.facadeDirectory.set(generated.map { it.dir("facade") })
            task.reportDirectory.set(project.layout.buildDirectory.dir("tmp/${task.name}"))
        }
        sourceSet.resources.srcDir(compile.flatMap { it.destinationDirectory })

        project.afterEvaluate {
            if (!extension.generateFacade.get()) return@afterEvaluate
            if (!extension.scanOwnClasses.get()) {
                sourceSet.java.srcDir(compile.flatMap { it.facadeDirectory })
                return@afterEvaluate
            }
            val facade = project.tasks.register(sourceSet.getCompileTaskName("quartzFacade"), JavaCompile::class.java) {
                it.group = GROUP
                it.description = "Compiles the typed facades of the ${sourceSet.name} Quartz modules."
                it.source(compile.flatMap { c -> c.facadeDirectory })
                it.classpath = sourceSet.compileClasspath + sourceSet.output.classesDirs
                it.destinationDirectory.set(project.layout.buildDirectory.dir("classes/quartz-facade/${sourceSet.name}"))
                val java = project.extensions.getByType(JavaPluginExtension::class.java)
                it.javaCompiler.set(project.extensions.getByType(JavaToolchainService::class.java).compilerFor(
                    java.toolchain))
            }
            sourceSet.output.dir(mapOf("builtBy" to facade), facade.flatMap { it.destinationDirectory })
        }
    }

    private fun kitInterfaces(): Map<String, String> = EmbedLayout.KIT_NAMES.associateWith { name ->
        val path = EmbedLayout.kitInterfacePath(name)
        val stream = EmbedLayout::class.java.classLoader.getResourceAsStream(path) ?: error("the plugin carries no $path")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun addRuntimeDependency(project: Project, extension: QuartzExtension) {
        val dependency = project.provider {
            if (!extension.addRuntimeDependency.get()) {
                emptyList<Dependency>()
            } else {
                listOf(project.dependencies.create("org.quartzlang:embed-runtime:${extension.runtimeVersion.get()}"))
            }
        }
        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
            it.dependencies.addAllLater(dependency)
        }
    }

    private companion object {
        const val GROUP = "build"
    }
}
