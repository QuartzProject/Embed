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
    options.release = 22
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-restricted", "-Werror"))
}

dependencies {
    api("org.quartzlang:quartz-java:${providers.gradleProperty("quartzJvmVersion").get()}")
    testImplementation(platform("org.junit:junit-bom:5.14.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    from(rootProject.file("kits")) {
        include("*.qzi")
        into("org/quartzlang/embed/kits")
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.quartzlang.embed", "Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.io.tmpdir", temporaryDir.path)
    providers.gradleProperty("quartz.library").orNull?.let { systemProperty("quartz.library", it) }
}
