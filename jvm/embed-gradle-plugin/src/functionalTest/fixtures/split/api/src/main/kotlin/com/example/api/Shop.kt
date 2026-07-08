package com.example.api

import org.quartzlang.embed.api.QuartzFunction

class Shop(private val prices: Map<String, Long>) {
    @QuartzFunction(name = "price")
    fun priceOf(item: String): Long = prices[item] ?: error("no item called $item")

    @QuartzFunction
    fun items(): List<String> = prices.keys.sorted()
}
