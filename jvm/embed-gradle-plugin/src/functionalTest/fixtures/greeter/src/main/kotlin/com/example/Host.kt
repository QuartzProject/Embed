package com.example

import org.quartzlang.embed.api.QuartzFunction
import org.quartzlang.embed.api.QuartzHandle

/** Somebody playing. */
@QuartzHandle
class Player(private val name: String, private val level: Int) {
    /** The name shown in chat. */
    @QuartzFunction(property = true)
    fun name(): String = name

    @QuartzFunction
    fun level(): Int = level
}

data class Badge(val title: String, val level: Int, val owner: Player)

enum class Rank { ROOKIE, VETERAN }

/** How far the player has come. */
@QuartzFunction
fun Player.rank(): Rank = if (level() > 5) Rank.VETERAN else Rank.ROOKIE

object Server {
    val log = mutableListOf<String>()

    @QuartzFunction
    fun broadcast(text: String) {
        log += text
    }

    @QuartzFunction
    fun nickname(player: Player?): String? = if (player != null && player.level() > 5) "veteran" else null

    @QuartzFunction
    fun badge(player: Player): Badge = Badge("star", player.level(), player)
}

@QuartzFunction
fun shout(text: String): String = text.uppercase()
