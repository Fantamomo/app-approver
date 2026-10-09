package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.RateLimits
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.decision.DecisionContext
import com.fantamomo.slack.approver.decision.DecisionEngine
import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.model.RequestStatus
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import com.fantamomo.slack.approver.utils.randomString
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

object AppManager {
    private val logger = LoggerFactory.getLogger(AppManager::class.java)

    private val requests = Channel<AppRequested>(Channel.UNLIMITED)
    private val running = CompletableDeferred<Unit>()

    @Serializable
    private class ResponseMetadata(val next_cursor: String)
    @Serializable
    private class Response(val app_requests: List<AppRequested>, val response_metadata: ResponseMetadata?)

    suspend fun start() = coroutineScope {
        launch {
            running.await()
            this@coroutineScope.cancel(null)
        }

        launch {
            while (true) {
                // get all pending requests, maybe we missed one
                var foundRequests = 0
                try {
                    val appRequests = listAppRequests(null)
                    foundRequests += appRequests.size
                    incomingAppRequests(appRequests)
                } catch (e: Exception) {
                    logger.error("Error loading initial requests from Slack API", e)
                }
                for (workspace in SharedData.workspaces) {
                    try {
                        val appRequests = listAppRequests(workspace)
                        foundRequests += appRequests.size
                        incomingAppRequests(appRequests)
                    } catch (e: Exception) {
                        logger.error("Error loading requests from Slack API for workspace $workspace", e)
                    }
                }
                logger.info("Found $foundRequests pending requests")

                delay(1.hours)
            }
        }

        while (isActive && !running.isCompleted) {
            val request = requests.receive()
            launch {
                handleRequest(request)
            }
        }
    }

    suspend fun listAppRequests(workspace: String?): List<AppRequested> {
        var cursor: String? = ""

        val foundRequests = mutableListOf<AppRequested>()

        while (cursor != null) {
            val response = RateLimits.ADMIN_APPS_REQUESTS_LIST.withLimit(low = true) {
                SharedData.httpClient.get("https://slack.com/api/admin.apps.requests.list") {
                    bearerAuth(Config.SLACK_USER_TOKEN)
                    parameter("limit", 100)
                    parameter("certified", true)
                    if (workspace != null) {
                        parameter("team_id", workspace)
                    } else {
                        parameter("enterprise_id", SharedData.enterpriseId)
                    }
                    if (cursor != "") {
                        parameter("cursor", cursor)
                    }
                }
            }
            if (response.status.isSuccess()) {
                val text = try {
                    response.bodyAsText()
                } catch (e: Exception) {
                    logger.error("Failed to get response body", e)
                    break
                }
                val json = try {
                    SharedData.json.decodeFromString<Response>(text)
                } catch (e: SerializationException) {
                    logger.error("Failed to parse response $text", e)
                    break
                }
                val appRequests = json.app_requests
                if (appRequests.isEmpty()) break
                foundRequests.addAll(appRequests)
                if (json.response_metadata != null) {
                    cursor = json.response_metadata.next_cursor.ifBlank { null }
                } else {
                    break
                }
            } else {
                logger.error("Failed to load initial requests: {}", response.status)
            }
        }
        return foundRequests
    }

    suspend fun handleRequest(request: AppRequested) {
        if (InstallRequestRepository.exists(request.id)) {
            logger.info("Ignoring duplicate request: {}", request.id)
            return
        }
        logger.info("Handling request: {}", request.id)

        val isVerified = VerificationManager.isVerified(request.user.id,)
        val restrictedScopes = InstallRequestRepository.getRestrictedScopes()
        val isAppRestricted = InstallRequestRepository.isAppRestricted(request.app.id)
        val previousRequests = InstallRequestRepository.findPreviousRequests(request.app.id, request.user.id, request.id)

        val decision = DecisionEngine.evaluate(
            DecisionContext(
                request = request,
                isUserVerified = isVerified,
                restrictedScopes = restrictedScopes,
                isAppRestricted = isAppRestricted,
                previousRequests = previousRequests
            )
        )

        val stateId = randomString(20)

        val reviewTs = SlackWorkflowService.postInitialReviewMessage(request, isVerified, decision, stateId)
        InstallRequestRepository.saveRequest(request, isVerified, decision, reviewTs, stateId)

        if (decision.status == RequestStatus.APPROVED) {
            SlackManager.approveApp(request.app.id, request.team?.id, request.enterprise?.id, request.id)
        }

        SlackWorkflowService.sendDecisionDm(
            userId = request.user.id,
            appName = request.app.name,
            requestId = request.id,
            status = decision.status,
            reason = decision.reason,
            relevantScopes = decision.relevantRestrictedScopes
        )

        SlackWorkflowService.logInitialTransparencyEvent(request, decision)
    }

    suspend fun incomingAppRequest(request: AppRequested) {
        requests.send(request)
    }

    suspend fun incomingAppRequests(collection: Collection<AppRequested>) {
        for (request in collection) {
            requests.send(request)
        }
    }
}
