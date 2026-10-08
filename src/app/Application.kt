package com.fantamomo.slack.approver.app

import io.ktor.server.application.*

fun Application.rootModule() {
    configureRateLimiting()
    configureSerialization()
    configureRouting()
}
