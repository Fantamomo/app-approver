package com.fantamomo.slack.approver.app

import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.manager.AppManager
import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.slack.SlackInteractionHandler
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private object Routing

private val logger = LoggerFactory.getLogger(Routing::class.java)

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("OK", status = HttpStatusCode.OK)
        }

        post("/slack/events") {
            val contentType = call.request.contentType()
            if (contentType.match(ContentType.Application.FormUrlEncoded)) {
                val parameters = call.receiveParameters()
                val payload = parameters["payload"]
                if (payload != null) {
                    launch {
                        SlackInteractionHandler.handlePayload(payload)
                    }
                    call.respond(HttpStatusCode.OK)
                    return@post
                } else {
                    logger.warn("Invalid payload received, was null")
                }
            }

            val text = call.receiveText()
            val jsonElement = try {
                SharedData.json.parseToJsonElement(text)
            } catch (e: Exception) {
                logger.warn("Could not parse JSON from request body", e)
                null
            }

            val body = jsonElement as? JsonObject
            if (body == null) {
                logger.warn("Invalid request body received, was not a JSON object")
                call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
                return@post
            }

            val type = body["type"]?.jsonPrimitive?.content
            when (type) {
                "url_verification" -> {
                    logger.info("Received URL verification request")
                    call.respondText(body["challenge"]?.jsonPrimitive?.content ?: "")
                }
                "event_callback" -> {
                    val eventPayload = body["event"]?.jsonObject
                    if (eventPayload == null) {
                        call.respondText("Invalid event payload", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    val eventType = eventPayload["type"]?.jsonPrimitive?.content
                    when (eventType) {
                        "app_requested" -> {
                            val requestElement = eventPayload["app_request"] ?: eventPayload
                            val appRequested = SharedData.json.decodeFromJsonElement(AppRequested.serializer(), requestElement)
                            AppManager.incomingAppRequest(appRequested)
                        }
                        "app_home_opened" -> {
                            val userId = eventPayload["user"]?.jsonPrimitive?.content
                            if (userId != null) {
                                launch {
                                    com.fantamomo.slack.approver.slack.SlackWorkflowService.handleAppHomeOpened(userId)
                                }
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK)
                }
                "block_actions", "view_submission" -> {
                    launch {
                        SlackInteractionHandler.handlePayload(text)
                    }
                    call.respond(HttpStatusCode.OK)
                }
                else -> {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        post("/slack/actions") {
            val payload = getPayloadFromCall(call)
            if (payload != null) {
                launch {
                    SlackInteractionHandler.handlePayload(payload)
                }
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        post("/slack/interactive") {
            val payload = getPayloadFromCall(call)
            if (payload != null) {
                launch {
                    SlackInteractionHandler.handlePayload(payload)
                }
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}

private suspend fun getPayloadFromCall(call: ApplicationCall): String? {
    val contentType = call.request.contentType()
    return if (contentType.match(ContentType.Application.FormUrlEncoded)) {
        val params = call.receiveParameters()
        params["payload"]
    } else {
        call.receiveText()
    }
}
