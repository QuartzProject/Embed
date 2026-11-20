<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/QuartzProject/.github/main/.github/assets/quartz-logo-rose.svg">
    <img src="https://raw.githubusercontent.com/QuartzProject/.github/main/.github/assets/quartz-logo-ink-rose.svg" alt="Quartz" width="360">
  </picture>
</p>

<h2 align="center">Quartz Embed</h2>

<p align="center">
  <strong>Quartz as a source language of your Gradle or CMake project, checked against your host at build time.</strong>
</p>

<p align="center">
  <img alt="Gradle" src="https://img.shields.io/badge/Gradle-9-02303A?logo=gradle&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <img alt="CMake" src="https://img.shields.io/badge/build-CMake-064F8C?logo=cmake&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green">
</p>

## About

Embed puts [Quartz](https://quartzlang.org) inside other projects the way their own languages are.

- **Gradle.** `src/main/quartz` sits next to `src/main/java` and `src/main/kotlin` and compiles into validated modules
  inside the jar.
- **A typed boundary.** Methods marked `@QuartzFunction` become the interface Quartz is checked against, and each
  module gets a generated facade the host calls directly.
- **Kits.** Files, processes, the terminal, HTTP and archives, each a capability the application grants.
- **CMake.** `quartz_add_sources` embeds modules in a C++ target, and `quartz_add_executable` turns a Quartz program
  into a single native executable.

## Getting started

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.quartzlang.embed")
}
```

```cmake
include(QuartzEmbed)
quartz_add_executable(qwc SOURCES wc.qz GRANT_ARGUMENT_PATHS)
```

Settings, the runtime API and every kit are in the [Embed chapter](https://quartzlang.org/docs/embed.html) of the
Quartz book. [Examples](https://github.com/QuartzProject/Examples) has a Paper plugin and a command line tool made with
Embed.

## Building

```bash
./gradlew build -Pquartz.jvm=<Quartz sources>/bindings/jvm -Pquartz.library=<libquartz>
```

Needs a JDK 25, the sources of [Quartz](https://github.com/QuartzProject/Quartz) and `libquartz` from a
[Quartz release](https://github.com/QuartzProject/Quartz/releases). The Gradle plugin is not in a public repository
yet: use it with `includeBuild` from these sources. The CMake module finds an installed Quartz, such as an unpacked
release, through `CMAKE_PREFIX_PATH`.

## License

[MIT](LICENSE).
