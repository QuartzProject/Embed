import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// The daemon side only touches embed-api (Java 17); the bindings (Java 22) are loaded in the forked compiler.
java {
    disableAutoTargetJvm()
}

val functionalTest: SourceSet = sourceSets.create("functionalTest")

dependencies {
    implementation(project(":embed-api"))
    implementation(project(":embed-runtime"))
    implementation("org.ow2.asm:asm:9.9")
    "functionalTestImplementation"(kotlin("test-junit5"))
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"(project(":embed-runtime"))
    "functionalTestImplementation"(platform("org.junit:junit-bom:5.14.3"))
    "functionalTestImplementation"("org.junit.jupiter:junit-jupiter")
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    testSourceSets(functionalTest)
    plugins {
        create("embed") {
            id = "org.quartzlang.embed"
            implementationClass = "org.quartzlang.embed.gradle.QuartzEmbedPlugin"
            displayName = "Quartz Embed"
            description = "Compiles Quartz sources into validated modules packaged in the jar, with typed host bindings."
        }
    }
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("org/quartzlang/embed/gradle/embed.properties") {
        expand("version" to version)
    }
}

evaluationDependsOn(":embed-runtime")

val runtimeJar = project(":embed-runtime").tasks.named<Jar>("jar")
val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the plugin against real builds with Gradle TestKit."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    dependsOn(runtimeJar)
    val runtimeClasspath = project(":embed-runtime").configurations.named("runtimeClasspath")
    inputs.files(runtimeJar, runtimeClasspath).withPropertyName("embedRuntime").withNormalizer(ClasspathNormalizer::class)
    inputs.dir("src/functionalTest/fixtures").withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("java.io.tmpdir", temporaryDir.path)
    val work = layout.buildDirectory.dir("functionalTest")
    doFirst {
        work.get().asFile.listFiles { f -> f.name != "testkit" }?.forEach { it.deleteRecursively() }
    }
    systemProperty("embed.fixtures", file("src/functionalTest/fixtures").path)
    systemProperty("embed.work", work.get().asFile.path)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dembed.runtimeClasspath=" + (runtimeJar.get().outputs.files + runtimeClasspath.get()).joinToString(
            File.pathSeparator))
    })
    providers.gradleProperty("quartz.library").orNull?.let { systemProperty("quartz.library", it) }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.check {
    dependsOn(functionalTestTask)
}
