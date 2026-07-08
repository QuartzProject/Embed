plugins {
    kotlin("jvm") version "2.4.21"
    application
    id("org.quartzlang.embed")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

quartz {
    addRuntimeDependency = false
    scanOwnClasses = false
    hostPackage = "com.example.shop"
    facadePackage = "com.example.app.quartz"
    kits("terminal")
}

dependencies {
    implementation(project(":api"))
    implementation(files(@RUNTIME@))
}

application {
    mainClass = "com.example.app.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED") + @LIBRARY_ARGS@
}
