@file:JvmName("QuartzSourceSets")

package org.quartzlang.embed.gradle

import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSet

/** The Quartz sources of this source set; the Groovy DSL finds them as `quartz { }`. */
val SourceSet.quartz: SourceDirectorySet
    get() = extensions.getByName("quartz") as SourceDirectorySet

fun SourceSet.quartz(configure: SourceDirectorySet.() -> Unit) {
    quartz.configure()
}
