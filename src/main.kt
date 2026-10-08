package com.fantamomo.slack.approver

import com.fantamomo.slack.approver.data.Config
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("main")

    try {
        Config.init()
    } catch (e: Exception) {
        logger.error("Error loading config", e)
        return
    }

    runBlocking {
        App.start()
    }
}
