package org.quartzlang.embed.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.jvm.toolchain.JavaLauncher

/** Settings of the `quartz { }` block. */
abstract class QuartzExtension {
    /**
     * Kits whose interfaces the modules compile against (`fs`, `terminal`, `process`, `http`, `archives`); all of them
     * by default. A module only asks the application for the kits its code calls.
     */
    abstract val kits: SetProperty<String>

    /** What a compiler warning does to the build; [QuartzSeverity.WARN] by default. */
    abstract val warnings: Property<QuartzSeverity>

    /** Levels of single warning rules (`unused-variable`, `discarded-result`...), applied after [warnings]. */
    abstract val rules: MapProperty<String, QuartzSeverity>

    /** Keep source lines in the modules for trap messages; on by default. */
    abstract val keepLines: Property<Boolean>

    /** Package of the generated host interface; empty puts the annotated functions at the top level. */
    abstract val hostPackage: Property<String>

    /** Handle types for classes the project cannot annotate, such as a library's `Player`: Quartz name to class. */
    abstract val handles: MapProperty<String, String>

    /**
     * Java and Kotlin sources of the host API, read for the KDoc and Javadoc the generated interface copies as `///`
     * comments; the source set's own sources by default.
     */
    abstract val hostSources: ConfigurableFileCollection

    /**
     * Scan the source set's own classes for `@QuartzFunction`, besides its classpath. Then the facade compiles after
     * the source set and the source set's own code cannot call it; turn this off when the host API lives elsewhere
     * and the facade becomes a generated source of the source set.
     */
    abstract val scanOwnClasses: Property<Boolean>

    /** Generate the typed Java facade of each module; on by default. */
    abstract val generateFacade: Property<Boolean>

    /** Package of the generated facades. */
    abstract val facadePackage: Property<String>

    /** Module id of the main source set's loose sources; the project name by default. */
    abstract val moduleName: Property<String>

    /** Put `embed-runtime` on the implementation classpath. */
    abstract val addRuntimeDependency: Property<Boolean>

    abstract val runtimeVersion: Property<String>

    /** The JVM that runs the compiler, 22 or newer; the toolchain's Java 25 by default. */
    abstract val compilerLauncher: Property<JavaLauncher>

    /** Compiles against only these kits. */
    fun kits(vararg names: String) {
        kits.set(names.toSet())
    }

    /** Sets the level of one warning rule. */
    fun rule(name: String, severity: QuartzSeverity) {
        rules.put(name, severity)
    }

    fun handle(quartzName: String, className: String) {
        handles.put(quartzName, className)
    }
}
