rootProject.name = "embed"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The JVM bindings come from org.quartzlang:quartz-java, or from a checkout given with -Pquartz.jvm=<dir>.
providers.gradleProperty("quartz.jvm").orNull?.let { includeBuild(it) }

include("embed-api", "embed-runtime", "embed-gradle-plugin")
project(":embed-api").projectDir = file("jvm/embed-api")
project(":embed-runtime").projectDir = file("jvm/embed-runtime")
project(":embed-gradle-plugin").projectDir = file("jvm/embed-gradle-plugin")
