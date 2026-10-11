package org.quartzlang.embed.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.quartzlang.embed.api.EmbedLayout
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drives real builds: what lands in the jar, what the facade calls, what a broken source prints. */
class QuartzEmbedPluginFunctionalTest {
    private val work = File(System.getProperty("embed.work"))
    private val library: String? = System.getProperty("quartz.library")

    @Test
    fun `compiles against annotated kotlin and is called back through the facade`() {
        val project = fixture("greeter")

        val result = build(project, "build")

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileQuartz")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val qzi = project.resolve("build/generated/quartz/main/host/host.qzi").readText()
        assertContains(qzi, "fn nickname(player: Player?) -> str?")
        assertContains(qzi, "struct Badge {\n    title: str\n    level: i32\n    owner: Player\n}")
        assertContains(qzi, "enum Rank {\n    ROOKIE\n    VETERAN\n}")
        assertContains(qzi, "/// Somebody playing.\n@handle\nstruct Player {}")
        assertContains(qzi, "    /// The name shown in chat.\n    @property\n    fn name(self) -> str\n")
        assertContains(qzi, "    /// How far the player has come.\n    fn rank(self) -> Rank\n")
        ZipFile(project.resolve("build/libs/greeter.jar")).use { jar ->
            assertNotNull(jar.getEntry(EmbedLayout.binaryPath("greeter")))
            assertNotNull(jar.getEntry("com/example/quartz/GreeterQuartz.class"))
            val metadata = jar.getInputStream(jar.getEntry(EmbedLayout.metadataPath("greeter"))).readAllBytes()
                .toString(Charsets.UTF_8)
            assertContains(metadata, "kits=terminal\n")
            assertContains(metadata, "bind.Player.level=HANDLE com.example.Player level ()I")
            assertContains(metadata, "bind.Player.rank=STATIC com.example.HostKt rank")
            assertContains(metadata, "attribute.0.name=command")
        }
    }

    @Test
    fun `stays up to date and recompiles only the module that changed`() {
        val project = fixture("split")
        build(project, ":app:compileQuartz")

        assertEquals(TaskOutcome.UP_TO_DATE, build(project, ":app:compileQuartz").task(":app:compileQuartz")?.outcome)

        val tools = project.resolve("app/src/main/quartz/tools/tools.qz")
        tools.writeText(tools.readText().replace("==", "**"))
        val result = build(project, ":app:compileQuartz", "--info")
        assertContains(result.output, "Quartz compiled 1 of 2 modules")
    }

    @Test
    fun `calls the facade from the same source set when the host api is another project`() {
        val project = fixture("split")

        val result = build(project, ":app:run")

        assertContains(result.output, "total=13 catalog=[apple, pear]")
        assertContains(result.output, "== tools ==")
        val qzi = project.resolve("app/build/generated/quartz/main/host/host.qzi").readText()
        assertContains(qzi, "package com.example.shop\n\nfn items() -> [str]\nfn price(item: str) -> int\n")
    }

    @Test
    fun `fails at the file, line and column of an error`() {
        val project = fixture("greeter")
        val broken = project.resolve("src/main/quartz/broken.qz")
        broken.writeText("pub fn twice(x: int) -> int {\n    return x * \"two\"\n}\n")

        val result = runner(project, "compileQuartz").buildAndFail()

        assertContains(result.output, "Quartz compilation failed:")
        assertContains(result.output, "${broken.path}:2:")
        assertContains(result.output, ": error: ")
    }

    @Test
    fun `sets warning levels for every rule and for one`() {
        val project = fixture("greeter")
        project.resolve("src/main/quartz/dead.qz").writeText("fn dead() -> int {\n    return 1\n    let x = 2\n}\n")
        val warned = build(project, "compileQuartz")
        assertContains(warned.output, "dead.qz:3:5: warning: unreachable code")
        assertContains(warned.output, "[unreachable-code]")

        val script = project.resolve("build.gradle.kts")
        script.appendText("\nquartz.rule(\"unreachable-code\", org.quartzlang.embed.gradle.QuartzSeverity.IGNORE)\n")
        assertTrue("unreachable" !in build(project, "compileQuartz").output)

        script.appendText("\nquartz.warnings = org.quartzlang.embed.gradle.QuartzSeverity.ERROR\n")
        val result = runner(project, "compileQuartz").buildAndFail()
        assertContains(result.output, "Quartz compilation failed:")
        assertContains(result.output, ": error: ")
    }

    @Test
    fun `rejects a host method whose types cannot cross`() {
        val project = fixture("greeter")
        project.resolve("src/main/kotlin/com/example/Bad.kt").writeText(
            "package com.example\n\nimport org.quartzlang.embed.api.QuartzFunction\n\n" +
                "@QuartzFunction\nfun lookup(file: java.io.File): Int = 0\n"
        )

        val result = runner(project, "compileQuartz").buildAndFail()

        assertContains(result.output, "com.example.BadKt.lookup: parameter 'file' is a java.io.File")
    }

    @Test
    fun `reuses the configuration cache`() {
        val project = fixture("greeter")
        build(project, "compileQuartz", "--configuration-cache")

        val result = build(project, "compileQuartz", "--configuration-cache")

        assertContains(result.output, "Reusing configuration cache")
        assertTrue(result.task(":compileQuartz")?.outcome in setOf(TaskOutcome.UP_TO_DATE, TaskOutcome.FROM_CACHE))
    }

    private fun fixture(name: String): File {
        val target = work.resolve("$name-${System.nanoTime()}")
        File(System.getProperty("embed.fixtures"), name).copyRecursively(target)
        val runtime = System.getProperty("embed.runtimeClasspath").split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }
        val testLibrary = library?.let { "systemProperty(\"quartz.library\", \"$it\")" } ?: ""
        val libraryArgs = library?.let { "listOf(\"-Dquartz.library=$it\")" } ?: "emptyList()"
        target.walkTopDown().filter { it.name == "build.gradle.kts" }.forEach {
            it.writeText(it.readText().replace("@RUNTIME@", runtime).replace("@LIBRARY@", testLibrary)
                .replace("@LIBRARY_ARGS@", libraryArgs))
        }
        return target
    }

    private fun build(project: File, vararg arguments: String): BuildResult = runner(project, *arguments).build()

    private fun runner(project: File, vararg arguments: String): GradleRunner {
        val args = arguments.toMutableList()
        library?.let { args += "-Pquartz.library=$it" }
        return GradleRunner.create()
            .withProjectDir(project)
            .withTestKitDir(work.resolve("testkit"))
            .withPluginClasspath()
            .withArguments(args + "--stacktrace")
            .forwardOutput()
    }
}
