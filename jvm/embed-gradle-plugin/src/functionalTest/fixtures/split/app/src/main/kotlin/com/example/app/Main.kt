package com.example.app

import com.example.api.Shop
import com.example.app.quartz.AppQuartz
import com.example.app.quartz.ToolsQuartz
import org.quartzlang.embed.Kits
import org.quartzlang.embed.QuartzEmbed

fun main() {
    val embed = QuartzEmbed.builder()
        .host(Shop(mapOf("apple" to 3L, "pear" to 5L)))
        .grant(Kits.terminal())
        .build()
    AppQuartz.open(embed).use { app ->
        println("total=${app.total(listOf("apple", "pear", "pear"))} catalog=${app.catalog()}")
    }
    ToolsQuartz.open(embed).use { it.runMain() }
}
