plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    from(rootProject.file("kits")) {
        include("*.qzi")
        into("org/quartzlang/embed/kits")
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.quartzlang.embed.api")
    }
}
