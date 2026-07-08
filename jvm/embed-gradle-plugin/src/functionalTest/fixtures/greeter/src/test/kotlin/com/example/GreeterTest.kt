package com.example

import com.example.quartz.GreeterQuartz
import org.quartzlang.embed.Kits
import org.quartzlang.embed.QuartzEmbed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
            assertSame(ada, greeter.best(listOf(bob, ada)))
            assertEquals(0, greeter.runMain())
        }
    }
}
