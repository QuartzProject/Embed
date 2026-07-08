package org.quartzlang.embed.gradle

import java.util.Properties

internal object EmbedVersion {
    val current: String by lazy {
        val resource = EmbedVersion::class.java.getResourceAsStream("embed.properties")
            ?: error("the Embed plugin jar carries no embed.properties")
        resource.use { Properties().apply { load(it) } }.getProperty("version")
    }
}
