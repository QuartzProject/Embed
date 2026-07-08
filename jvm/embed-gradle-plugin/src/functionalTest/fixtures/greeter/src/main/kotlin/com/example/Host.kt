package com.example

import org.quartzlang.embed.api.QuartzFunction
import org.quartzlang.embed.api.QuartzHandle

@QuartzHandle
class Player(private val name: String, private val level: Int) {
    @QuartzFunction
    fun name(): String = name

    @QuartzFunction
    fun level(): Int = level
}

object Server {
    val log = mutableListOf<String>()

    @QuartzFunction
    fun broadcast(text: String) {
        log += text
    }

    @QuartzFunction
    fun nickname(player: Player): String? = if (player.level() > 5) "veteran" else null
}

@QuartzFunction
fun shout(text: String): String = text.uppercase()
