package org.quartzlang.embed.gradle

import java.io.File

/** The sources of one module: a directory with a `package.toml`, or the loose files of a source set. */
internal data class QuartzModule(val id: String, val manifest: File?, val files: List<Pair<String, File>>)

internal object QuartzModules {
    private val nameLine = Regex("""(?m)^\s*name\s*=\s*"([^"]+)"""")

    /**
     * Groups sources by the nearest directory holding a `package.toml`; the rest form the default module.
     * Each entry is the path relative to its source directory and the file.
     */
    fun group(files: List<Pair<String, File>>, defaultId: String): List<QuartzModule> {
        val roots = files.filter { it.first.substringAfterLast('/') == "package.toml" }
            .associate { it.first.substringBeforeLast('/', "") to it.second }
        val byRoot = sortedMapOf<String, MutableList<Pair<String, File>>>()
        for ((path, file) in files) {
            if (path.endsWith("package.toml")) continue
            val root = rootOf(path, roots.keys)
            val relative = if (root == null || root.isEmpty()) path else path.removePrefix("$root/")
            byRoot.getOrPut(root ?: "\u0000") { mutableListOf() } += relative to file
        }
        return byRoot.map { (root, sources) ->
            val manifest = roots[root]
            val id = manifest?.let { nameLine.find(it.readText())?.groupValues?.get(1) } ?: if (root == "\u0000") {
                defaultId
            } else {
                root.replace('/', '.')
            }
            QuartzModule(id, manifest, sources.sortedBy { it.first })
        }
    }

    /** The module directory a source path belongs to, or null for the default module. */
    fun rootOf(path: String, roots: Set<String>): String? {
        var dir = path.substringBeforeLast('/', "")
        while (true) {
            if (dir in roots) return dir
            if (dir.isEmpty()) return null
            dir = dir.substringBeforeLast('/', "")
        }
    }

    /** `greeter` and `org.example.greeter-tools` become `GreeterQuartz` and `GreeterToolsQuartz`. */
    fun facadeClass(id: String): String =
        id.substringAfterLast('.').split('-', '_').filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { "Module" } + "Quartz"
}
