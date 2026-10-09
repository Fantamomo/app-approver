package com.fantamomo.slack.approver.app

import com.fantamomo.slack.approver.App
import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.manager.AppManager
import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.slack.SlackInteractionHandler
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import com.fantamomo.slack.approver.utils.SlackRequestVerifier
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private object Routing

private val logger = LoggerFactory.getLogger(Routing::class.java)

fun Application.configureRouting() {
    // there is only one possible way that SLACK_SIGNING_SECRET throws and that in tests
    val slackSigningSecret = runCatching { Config.SLACK_SIGNING_SECRET }.getOrDefault("")

    routing {
        get("/") {
            call.respondText("OK", status = HttpStatusCode.OK)
        }

        post("/slack/events") {
            val rawBody = call.receiveText()

            if (!SlackRequestVerifier.verify(call.request, rawBody, slackSigningSecret)) {
                logger.warn("Invalid Slack request signature")
                call.respond(HttpStatusCode.Unauthorized, "Invalid Slack signature")
                return@post
            }

            handleSlackRequest(call, rawBody, App.scope)
        }

        post("/slack/actions") {
            val rawBody = call.receiveText()

            if (!SlackRequestVerifier.verify(call.request, rawBody, slackSigningSecret)) {
                logger.warn("Invalid Slack request signature")
                call.respond(HttpStatusCode.Unauthorized, "Invalid Slack signature")
                return@post
            }

            val payload = extractSlackPayload(call.request.contentType(), rawBody)

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
            val rawBody = call.receiveText()

            if (!SlackRequestVerifier.verify(call.request, rawBody, slackSigningSecret)) {
                logger.warn("Invalid Slack request signature")
                call.respond(HttpStatusCode.Unauthorized, "Invalid Slack signature")
                return@post
            }

            val payload = extractSlackPayload(call.request.contentType(), rawBody)

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

private suspend fun handleSlackRequest(
    call: ApplicationCall,
    rawBody: String,
    scope: CoroutineScope
) {
    val contentType = call.request.contentType()

    if (contentType.match(ContentType.Application.FormUrlEncoded)) {
        val payload = extractSlackPayload(contentType, rawBody)

        if (payload != null) {
            scope.launch {
                SlackInteractionHandler.handlePayload(payload)
            }

            call.respond(HttpStatusCode.OK)
            return
        }

        logger.warn("Invalid Slack payload received")
    }

    val jsonElement = try {
        SharedData.json.parseToJsonElement(rawBody)
    } catch (e: Exception) {
        logger.warn("Could not parse JSON from request body", e)
        null
    }

    val body = jsonElement as? JsonObject
    if (body == null) {
        logger.warn("Invalid request body received, was not a JSON object")
        call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)

        return
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
                return
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
                        scope.launch {
                            SlackWorkflowService.handleAppHomeOpened(userId)
                        }
                    }
                }
            }

            call.respond(HttpStatusCode.OK)
        }

        "block_actions", "view_submission" -> {
            scope.launch {
                SlackInteractionHandler.handlePayload(rawBody)
            }
            call.respond(HttpStatusCode.OK)
        }

        else -> call.respond(HttpStatusCode.OK)
    }
}

private fun extractSlackPayload(
    contentType: ContentType,
    rawBody: String
): String? {
    return if (contentType.match(ContentType.Application.FormUrlEncoded)) {
        rawBody.parseUrlEncodedParameters()["payload"]
    } else rawBody
}