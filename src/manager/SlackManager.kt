package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.RateLimits
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.slack.SlackInteractionHandler
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.kotlin_extension.block.dsl.LayoutBlockDsl
import com.slack.api.model.kotlin_extension.block.withBlocks
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object SlackManager {

    private val logger = LoggerFactory.getLogger(SlackManager::class.java)

    private const val SLACK_API = "https://slack.com/api"
    private const val RECONNECT_DELAY_MS = 3000L
    private const val MAX_RECONNECT_DELAY_MS = 30000L // 30 seconds

    private val cachedDmId: MutableMap<String, Pair<String, Instant>> = mutableMapOf()

    @Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND") // yeah, scary but completely fine, just look into the withBlocks methode
    @OptIn(ExperimentalContracts::class)
    suspend fun sendMessage(
        channel: String,
        threadTs: String? = null,
        builder: LayoutBlockDsl.() -> Unit
    ): String? {
        contract { callsInPlace(builder, InvocationKind.EXACTLY_ONCE) }
        val blocks = withBlocks(builder)
        return sendMessage(channel, threadTs, blocks)
    }

    suspend fun sendMessage(
        channel: String,
        threadTs: String? = null,
        blocks: List<LayoutBlock>
    ): String? {
        return try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("channel", channel)
                if (threadTs != null) {
                    addProperty("thread_ts", threadTs)
                }
                add("blocks", SharedData.gson.toJsonTree(blocks))
            }

            val response = RateLimits.CHAT_POST_MESSAGE.withLimit {
                SharedData.httpClient.post("$SLACK_API/chat.postMessage") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                json["ts"]?.jsonPrimitive?.contentOrNull
            } else {
                logger.error("Failed to post message to channel $channel: $text")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error sending message to channel $channel", e)
            null
        }
    }

    suspend fun sendTextMessage(
        channel: String,
        text: String,
        threadTs: String? = null
    ): String? {
        return try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("channel", channel)
                addProperty("text", text)
                if (threadTs != null) {
                    addProperty("thread_ts", threadTs)
                }
            }

            val response = RateLimits.CHAT_POST_MESSAGE.withLimit {
                SharedData.httpClient.post("$SLACK_API/chat.postMessage") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val responseText = response.bodyAsText()
            val json = SharedData.json
                .parseToJsonElement(responseText)
                .jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                json["ts"]?.jsonPrimitive?.contentOrNull
            } else {
                logger.error("Failed to post text message to channel ${channel}: $responseText")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error sending text message to channel $channel", e)
            null
        }
    }

    suspend fun sendEphemeral(
        responseUrl: Url,
        builder: LayoutBlockDsl.() -> Unit
    ) {
        require(responseUrl.host == "hooks.slack.com") { "Invalid response URL: $responseUrl" }
        try {
            val blocks = withBlocks(builder)

            val body = com.google.gson.JsonObject().apply {
                add("blocks", SharedData.gson.toJsonTree(blocks))
            }

            val response = RateLimits.CHAT_POST_EPHEMERAL.withLimit {
                SharedData.httpClient.post(responseUrl) {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()

            if (text != "ok") {
                logger.error("Failed to post ephemeral message within interaction ($responseUrl): $text")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error sending ephemeral message within interaction ($responseUrl)", e)
        }
    }

    suspend fun sendEphemeral(
        channel: String,
        userId: String,
        threadTs: String? = null,
        builder: LayoutBlockDsl.() -> Unit
    ) {
        try {
            val blocks = withBlocks(builder)

            val body = com.google.gson.JsonObject().apply {
                addProperty("channel", channel)
                if (threadTs != null) {
                    addProperty("thread_ts", threadTs)
                }
                addProperty("user", userId)
                add("blocks", SharedData.gson.toJsonTree(blocks))
            }

            val response = RateLimits.CHAT_POST_EPHEMERAL.withLimit {
                SharedData.httpClient.post("$SLACK_API/chat.postEphemeral") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                json["ts"]?.jsonPrimitive?.contentOrNull
            } else {
                logger.error("Failed to post ephemeral message to channel $channel: $text")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error sending ephemeral message to channel $channel", e)
        }
    }

    suspend fun updateMessage(
        channel: String,
        ts: String,
        builder: LayoutBlockDsl.() -> Unit
    ): Boolean {
        return try {
            val blocks = withBlocks(builder)

            val body = com.google.gson.JsonObject().apply {
                addProperty("channel", channel)
                addProperty("ts", ts)
                add("blocks", SharedData.gson.toJsonTree(blocks))
            }

            val response = RateLimits.CHAT_UPDATE.withLimit {
                SharedData.httpClient.post("$SLACK_API/chat.update") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true

            if (!ok) {
                logger.error("Failed to update message $ts in channel $channel: $text")
            }

            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error updating message $ts in channel $channel", e)
            false
        }
    }

    suspend fun openDm(userId: String): String? {
        val cache = cachedDmId[userId]
        if (cache != null) {
            val now = Clock.System.now()
            if (now <= cache.second) {
                return cache.first
            } else {
                cachedDmId.remove(userId)
            }
        }

        return try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("users", userId)
            }

            val response = RateLimits.CONVERSATION_OPEN.withLimit {
                SharedData.httpClient.post("$SLACK_API/conversations.open") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                val dmChannelId = json["channel"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.contentOrNull
                if (dmChannelId != null) {
                    cachedDmId[userId] = dmChannelId to Clock.System.now() + 10.minutes
                }
                dmChannelId
            } else {
                logger.error("Failed to open DM with user $userId: $text")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error opening DM with user $userId", e)
            null
        }
    }

    suspend fun sendDm(
        userId: String,
        builder: LayoutBlockDsl.() -> Unit
    ): String? {
        val channelId = openDm(userId) ?: return null
        return sendMessage(channelId, null, builder)
    }

    suspend fun sendDmText(
        userId: String,
        text: String
    ): String? {
        val channelId = openDm(userId) ?: return null
        return sendTextMessage(channelId, text)
    }

    suspend fun openView(
        triggerId: String,
        view: com.google.gson.JsonObject
    ): Boolean {
        return try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("trigger_id", triggerId)
                add("view", view)
            }

            val response = RateLimits.VIEWS_OPEN.withLimit {
                SharedData.httpClient.post("$SLACK_API/views.open") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true

            if (!ok) {
                logger.error("Failed to open view with trigger $triggerId: $text")
            }

            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error opening view with trigger $triggerId", e)
            false
        }
    }

    suspend fun approveApp(
        appId: String,
        teamId: String?,
        enterpriseId: String?,
        requestId: String?
    ): Boolean {
        require((teamId != null) != (enterpriseId != null)) { "Either teamId or enterpriseId must be provided, but not both" }
        return try {
            val body = com.google.gson.JsonObject().apply {
                if (teamId != null) {
                    addProperty("team_id", teamId)
                } else {
                    addProperty("enterprise_id", enterpriseId)
                }
                if (requestId != null) {
                    addProperty("request_id", requestId)
                } else {
                    addProperty("app_id", appId)
                }
            }

            val response = RateLimits.ADMIN_APPS_APPROVE.withLimit {
                SharedData.httpClient.post("$SLACK_API/admin.apps.approve") {
                    bearerAuth(Config.SLACK_USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true

            if (!ok) {
                logger.warn("admin.apps.approve failed for $appId: $text")
            }

            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not approve app $appId", e)
            false
        }
    }

    suspend fun cancelAppRequest(
        appId: String,
        teamId: String?,
        enterpriseId: String?,
        requestId: String?
    ): Boolean {
        require((teamId != null) != (enterpriseId != null)) { "Either teamId or enterpriseId must be provided, but not both" }
        return try {
            val body = com.google.gson.JsonObject().apply {
                if (teamId != null) {
                    addProperty("team_id", teamId)
                } else {
                    addProperty("enterprise_id", enterpriseId)
                }
                if (requestId != null) {
                    addProperty("request_id", requestId)
                } else {
                    addProperty("app_id", appId)
                }
            }

            val response = RateLimits.ADMIN_APPS_REQUESTS_CANCEL.withLimit {
                SharedData.httpClient.post("$SLACK_API/admin.apps.requests.cancel") {
                    bearerAuth(Config.SLACK_USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true

            if (!ok) {
                logger.warn("admin.requests.cancel failed for app $appId: $text")
            }

            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not cancel app request $appId ($requestId)", e)
            false
        }
    }

    suspend fun restrictApp(
        appId: String,
        teamId: String?,
        enterpriseId: String?,
        requestId: String?
    ): Boolean {
        require((teamId != null) != (enterpriseId != null)) { "Either teamId or enterpriseId must be provided, but not both" }
        return try {
            val body = com.google.gson.JsonObject().apply {
                if (requestId != null) {
                    addProperty("request_id", requestId)
                } else {
                    addProperty("app_id", appId)
                }
                if (teamId != null) {
                    addProperty("team_id", teamId)
                } else {
                    addProperty("enterprise_id", enterpriseId)
                }
            }

            val response = RateLimits.ADMIN_APPS_RESTRICT.withLimit {
                SharedData.httpClient.post("$SLACK_API/admin.apps.restrict") {
                    bearerAuth(Config.SLACK_USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true

            if (!ok) {
                if (requestId != null && (json["error"] as? JsonPrimitive)?.contentOrNull == "request_already_resolved") {

                    return restrictApp(appId, teamId, enterpriseId, null)
                }
                logger.warn("admin.apps.restrict failed for app $appId: $text")
            }

            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not restrict app $appId", e)
            false
        }
    }

    suspend fun clearResolution(
        appId: String,
        teamId: String? = null,
        enterpriseId: String? = null
    ): Boolean {
        require((teamId != null) != (enterpriseId != null)) { "Either teamId or enterpriseId must be provided, but not both" }
        return try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("app_id", appId)
                if (teamId != null) {
                    addProperty("team_id", teamId)
                } else {
                    addProperty("enterprise_id", enterpriseId)
                }
            }

            val response = RateLimits.ADMIN_APPS_CLEAR_RESOLUTION.withLimit {
                SharedData.httpClient.post("$SLACK_API/admin.apps.clearResolution") {
                    bearerAuth(Config.SLACK_USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true

            if (!ok) {
                logger.warn("admin.apps.clearResolution failed for app $appId: $text")
            }

            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not clear resolution for app $appId", e)
            false
        }
    }

    suspend fun publishView(
        userId: String,
        view: com.google.gson.JsonObject
    ): Boolean {
        return try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("user_id", userId)
                add("view", view)
            }

            val response = RateLimits.VIEWS_PUBLISH.withLimit {
                SharedData.httpClient.post("$SLACK_API/views.publish") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(SharedData.gson.toJson(body))
                }
            }

            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true

            if (!ok) {
                logger.error("Failed to publish view for user $userId: $text")
            }

            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error publishing view for user $userId", e)
            false
        }
    }

    suspend fun publishHomeView(
        userId: String,
        builder: LayoutBlockDsl.() -> Unit
    ): Boolean {
        val blocks = withBlocks(builder)
        val view = com.google.gson.JsonObject().apply {
            addProperty("type", "home")
            add("blocks", SharedData.gson.toJsonTree(blocks))
        }
        return publishView(userId, view)
    }

    suspend fun connectViaSocket() {
        require(Config.SLACK_APP_TOKEN.isNotBlank()) {
            "SLACK_APP_TOKEN must be set to use Socket Mode"
        }

        var reconnectDelay = RECONNECT_DELAY_MS

        supervisorScope {
            while (isActive) {
                try {
                    val result = connectSocket(this)

                    reconnectDelay = RECONNECT_DELAY_MS

                    if (result == SocketResult.DISCONNECT) {
                        logger.info("Slack requested a Socket Mode reconnect")
                    } else {
                        logger.warn("Slack Socket Mode connection closed")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Slack Socket Mode connection failed", e)
                }

                if (!isActive) break

                logger.info("Reconnecting to Slack Socket Mode in $reconnectDelay ms")

                delay(reconnectDelay.milliseconds)

                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
    }

    suspend fun getWorkspaces(): List<String>? {
        return try {
            val response = SharedData.httpClient.get("$SLACK_API/auth.teams.list") {
                bearerAuth(Config.SLACK_BOT_TOKEN)
                parameter("limit", 1000)
            }
            val text = response.bodyAsText()

            val json = SharedData.json.parseToJsonElement(text).jsonObject

            val teams = json["teams"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }

            teams ?: throw IllegalStateException("Failed to parse Slack workspaces of $text")
        } catch (e: Exception) {
            logger.error("Failed to request Slack workspaces", e)
            null
        }
    }

    private suspend fun connectSocket(
        scope: CoroutineScope
    ): SocketResult {
        val response = try {
            SharedData.httpClient.post("$SLACK_API/apps.connections.open") {
                bearerAuth(Config.SLACK_APP_TOKEN)
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to request Slack Socket Mode URL", e)
        }

        val responseText = response.bodyAsText()

        val json = try {
            SharedData.json
                .parseToJsonElement(responseText)
                .jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("Invalid response from apps.connections.open: $responseText", e)
        }

        if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val error = json["error"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: "unknown_error"

            throw IllegalStateException("apps.connections.open failed: $error")
        }

        val url = json["url"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: throw IllegalStateException("apps.connections.open returned no WebSocket URL")

        logger.info("Connecting to Slack Socket Mode")

        return try {
            SharedData.httpClient.webSocket(url) {
                logger.info("Connected to Slack Socket Mode")

                for (frame in incoming) {
                    try {
                        when (frame) {
                            is Frame.Text -> handleSocketMessage(frame.readText(), this, scope)
                            is Frame.Binary -> handleSocketMessage(frame.data.toString(Charsets.UTF_8), this, scope)
                            is Frame.Close -> {
                                val reason = try {
                                    frame.readReason()
                                } catch (e: Exception) {
                                    logger.warn("Failed to read reason from Slack Socket Mode close frame", e)
                                    null
                                }

                                logger.warn("Slack Socket Mode closed connection: $reason")
                                break
                            }

                            is Frame.Ping, is Frame.Pong -> Unit
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to handle Slack Socket Mode frame of type ${frame::class.java}", e)
                    }
                }
            }

            SocketResult.RECONNECT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Slack WebSocket connection error", e)
            SocketResult.RECONNECT
        }
    }

    private suspend fun handleSocketMessage(
        text: String,
        session: DefaultClientWebSocketSession,
        scope: CoroutineScope
    ) {
        val json = try {
            SharedData.json
                .parseToJsonElement(text)
                .jsonObject
        } catch (e: Exception) {
            logger.error("Failed to parse Slack WebSocket message: $text", e)
            return
        }

        val type = json["type"]?.jsonPrimitive?.contentOrNull

        when (type) {
            "hello" -> {
                logger.info("Slack Socket Mode handshake completed")
                return
            }

            "disconnect" -> {
                val reason = json["reason"]?.jsonPrimitive?.contentOrNull

                logger.warn("Slack requested Socket Mode disconnect: $reason")

                try {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "Slack requested reconnect"))
                } catch (e: Exception) {
                    logger.debug("Failed to close Slack WebSocket cleanly", e)
                }

                return
            }
        }

//        logger.info("Received Slack Socket Mode event: $json")

        val envelopeId = json["envelope_id"]?.jsonPrimitive?.contentOrNull

        if (envelopeId.isNullOrBlank()) {
            logger.debug("Ignoring Slack WebSocket message without envelope_id: $text")
            return
        }

        try {
            val ack = buildJsonObject {
                put("envelope_id", envelopeId)
            }

            session.send(Frame.Text(SharedData.json.encodeToString(ack)))
        } catch (e: Exception) {
            logger.error("Failed to ACK Slack envelope $envelopeId", e)
            return
        }

        scope.launch(Dispatchers.Default) {
            try {
                processSocketEvent(type, json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to process Slack envelope $envelopeId", e)
            }
        }
    }

    private suspend fun processSocketEvent(
        type: String?,
        json: JsonObject
    ) {
//        logger.info("Received Slack Socket Mode type: $type")

        when (type) {
            "events_api" -> {
                val payload = json["payload"]?.jsonObject ?: return
                val event = payload["event"]?.jsonObject ?: return

                when (val eventType = event["type"]?.jsonPrimitive?.contentOrNull) {
                    "app_requested" -> {
                        logger.info("Received app_requested event: {}", json)
                        try {
                            val appRequested = SharedData.json.decodeFromJsonElement(
                                AppRequested.serializer(),
                                event["app_request"]?.jsonObject ?: throw Exception("Missing app_request field")
                            )

                            logger.info("Received scopes for app_requested event: ${appRequested.scopes}")

                            AppManager.incomingAppRequest(appRequested)
                        } catch (e: Exception) {
                            logger.error("Failed to process app_requested event: $json", e)
                        }
                    }

                    "app_home_opened" -> {
                        val userId = event["user"]?.jsonPrimitive?.contentOrNull
                        if (userId != null) {
                            try {
                                SlackWorkflowService.handleAppHomeOpened(userId)
                            } catch (e: Exception) {
                                logger.error("Failed to handle app_home_opened event for user $userId", e)
                            }
                        }
                    }

                    else -> {
                        logger.debug("Unhandled Slack event type: $eventType")
                    }
                }
            }

            "interactive" -> {
                val payload = json["payload"]?.jsonObject ?: return

                try {
                    SlackInteractionHandler.handlePayload(payload)
                } catch (e: Exception) {
                    logger.error("Failed to process Slack interaction", e)
                }
            }

            "slash_commands" -> {
                val payload = json["payload"]?.jsonObject ?: return

//                logger.info("Received Slack slash command payload: $payload")

                try {
                    SlackInteractionHandler.handlePayload(payload, type = "slash_commands")
                } catch (e: Exception) {
                    logger.error("Failed to process Slack slash command", e)
                }
            }

            else -> {
                logger.warn("Unhandled Slack Socket Mode type: $type")
            }
        }
    }

    private enum class SocketResult {
        RECONNECT,
        DISCONNECT
    }
}
