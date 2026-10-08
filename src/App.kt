package com.fantamomo.slack.approver

import com.fantamomo.slack.approver.app.rootModule
import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.manager.AppManager
import com.fantamomo.slack.approver.manager.DatabaseManager
import com.fantamomo.slack.approver.manager.SlackManager
import com.fantamomo.slack.approver.manager.VerificationManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

object App {
    private val logger = LoggerFactory.getLogger(App::class.java)

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    suspend fun start() {
        // switching to application scope
        val job = scope.launch {
            run()
        }
        job.join()
    }

    private suspend fun run() = coroutineScope {
        try {
            DatabaseManager.init()
        } catch (e: Exception) {
            logger.error("Failed to initialize database", e)
            return@coroutineScope
        }

        if (Config.SOCKET_MODE) {
            launch {
                SlackManager.connectViaSocket()
            }
        }

        launch {
            VerificationManager.start()
        }
        launch {
            AppManager.start()
        }

        val server = embeddedServer(
            factory = Netty,
            port = Config.PORT,
            host = Config.HOST,
            module = Application::rootModule
        )
        server.startSuspend(wait = true)
        logger.info("Server stopped")
    }
}