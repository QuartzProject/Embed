package com.example

import com.example.quartz.GreeterQuartz
import org.quartzlang.embed.Kits
import org.quartzlang.embed.QuartzEmbed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GreeterTest {
    @Test
    fun callsQuartzThroughTheFacade() {
        val embed = QuartzEmbed.builder().grant(Kits.terminal()).build()
        GreeterQuartz.open(embed).use { greeter ->
            val ada = Player("ada", 9)
            val bob = Player("bob", 2)
            assertEquals("HELLO ADA THE VETERAN X2", greeter.greet(ada, 2))
            assertEquals("HELLO BOB THE NEWCOMER X1", greeter.greet(bob, 1))
            assertEquals(listOf("greeting ada", "greeting bob"), Server.log)
            assertEquals(listOf(9, 2), greeter.levels(listOf(ada, bob)))
            assertEquals(1, greeter.visit(ada))
            assertEquals(2, greeter.visit(ada))
            assertSame(bob, greeter.find(listOf(ada, bob), "bob"))
            assertNull(greeter.find(listOf(ada), "carl"))
            assertEquals(GreeterQuartz.Spot("w", 1.5, ada), greeter.spot("w", ada))
            assertNull(greeter.spot("w", null).owner())
            assertEquals(GreeterQuartz.Mood.Loud(9L), greeter.mood(9))
            assertEquals(Badge("star", 9, ada), greeter.award(ada))
            assertEquals(Rank.VETERAN, greeter.ranked(ada))
            assertEquals(8L, greeter.later(4).also { assertTrue(it.done()) }.value().asLong())

            val command = greeter.module().functions("command").single()
            assertEquals("greet", command.attribute().value())
            assertEquals("example.greet", command.attribute().get("perm", ""))
            assertEquals(listOf("player", "args"), command.parameters())
            assertEquals("ada 3 2", greeter.module().invoke(command.function(), ada, listOf("a", "b")))
            assertEquals(0, greeter.runMain())
        }
    }
}
