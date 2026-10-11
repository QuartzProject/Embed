plugins {
    kotlin("jvm") version "2.4.21"
    id("org.quartzlang.embed")
}

group = "com.example"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

quartz {
    addRuntimeDependency = false
}

dependencies {
    implementation(files(@RUNTIME@))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    @LIBRARY@
}
