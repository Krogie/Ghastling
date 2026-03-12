package com.foenichs.ghastling

import com.foenichs.ghastling.config.AppConfig
import com.foenichs.ghastling.db.DatabaseFactory
import com.foenichs.ghastling.discord.App
import com.foenichs.ghastling.tags.TagService
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.foenichs.ghastling.Main")

fun main() {
    try {
        val config = AppConfig.load()
        TagService.init(config)
        DatabaseFactory.init(config)

        val app = App(config)
        Runtime.getRuntime().addShutdownHook(Thread { app.shutdown() })
        app.start()
    } catch (e: Exception) {
        logger.error("Failed to start Ghastling", e)
        System.exit(1)
    }
}