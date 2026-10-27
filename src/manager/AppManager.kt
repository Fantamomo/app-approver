package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.Constants.MARKETPLACE_URL
import com.fantamomo.slack.approver.data.RateLimits
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.decision.DecisionContext
import com.fantamomo.slack.approver.decision.DecisionEngine
import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.model.RequestDecision
import com.fantamomo.slack.approver.model.RequestDecisionReason
import com.fantamomo.slack.approver.model.RequestStatus
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import com.fantamomo.slack.approver.utils.randomString
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

object AppManager {
    private val logger = LoggerFactory.getLogger(AppManager::class.java)

    private val requests = Channel<AppRequested>(Channel.UNLIMITED)
    private val running = CompletableDeferred<Unit>()

    private var firstListRequestRun: Boolean = true

    private val interval = 5.minutes

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
                val requests: MutableList<AppRequested> = mutableListOf()
                var foundRequests = 0
                try {
                    val appRequests = listAppRequests(null)
                    foundRequests += appRequests.size
                    requests.addAll(appRequests)
                } catch (e: Exception) {
                    logger.error("Error loading initial requests from Slack API", e)
                }
                for (workspace in SharedData.workspaces) {
                    try {
                        val appRequests = listAppRequests(workspace)
                        foundRequests += appRequests.size
                        requests.addAll(appRequests)
                    } catch (e: Exception) {
                        logger.error("Error loading requests from Slack API for workspace $workspace", e)
                    }
                }
                logger.info("Found $foundRequests pending requests")

                checkCurrentPendingRequests(requests)

                firstListRequestRun = false

                delay(interval)
            }
        }

        while (isActive && !running.isCompleted) {
            val request = requests.receive()
            launch {
                handleRequest(request)
            }
        }
    }

    private suspend fun checkCurrentPendingRequests(requests: List<AppRequested>) {
        val requestIds = requests.map { it.id }
        val pendingRequests = InstallRequestRepository.allPendingRequests()

        val newRequests = requests.filter { it.id !in pendingRequests }
        val noLongerRequests = pendingRequests.filter { it !in requestIds }

        logger.info("Found ${newRequests.size} new requests and ${noLongerRequests.size} which are no longer pending")
        incomingAppRequests(newRequests)

        // the problem is noLongerRequests, all those requests are no longer pending, and that means a third party has approved or rejected the request
        // we have NO good way to figure out what happened to those requests (if are now approved or rejected)
        // so we set them in our system as withdrawn, and close the threads for those requests

        for (request in noLongerRequests) {
            try {
                handleNoLongerPendingRequest(request)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.warn("Error processing a no longer pending request: $request", e)
            }
        }
    }

    private suspend fun handleNoLongerPendingRequest(requestId: String) {
        val request = InstallRequestRepository.getRequest(requestId)

        if (request == null) {
            // this should be impossible to happen, but just in case
            logger.warn("No request found for requestId: $requestId")
            return
        }
        if (request.resolution != null) {
            // also impossible to happen, but just in case
            logger.warn("Request (no longer pending) already resolved for requestId: $requestId")
            return
        }

        val stateId = InstallRequestRepository.updateResolution(
            requestId = requestId,
            newStatus = RequestStatus.WITHDRAWN,
            resolution = RequestDecision.WITHDRAWN,
            resolvedBy = "THIRD_PARTY",
            resolutionMessage = "The request was resolved by a third party ${if (firstListRequestRun) "while the program was offline" else "in the last $interval"}",
            actorType = "SLACK",
            action = "WITHDRAW",
            reason = RequestDecisionReason.THIRD_PARTY
        )

        SlackWorkflowService.updateReviewMessage(
            record = request.copy(stateId = stateId),
            status = RequestStatus.WITHDRAWN,
            resolution = RequestDecision.WITHDRAWN,
            reason = RequestDecisionReason.THIRD_PARTY,
            resolvedBy = null,
            resolutionMessage = null
        )

        request.reviewMessageTs?.let { ts ->
            SlackWorkflowService.postReviewThreadUpdate(
                reviewMessageTs = ts,
                actorId = null,
                actionText = "resolved the request ${if (firstListRequestRun) "while the program was offline" else "in the last $interval"}",
                reason = null,
                actorName = "Someone"
            )
        }

        // we don't send the decision dm because it wasn't actually resolved by us
//        SlackWorkflowService.sendDecisionDm(
//            userId = request.userId,
//            appName = request.appName,
//            requestId = requestId,
//            appId = request.appId,
//            status = RequestStatus.APPROVED,
//            reason = null,
//            resolvedBy = "Someone"
//        )

        val appText = if (request.appId.startsWith('A')) "<$MARKETPLACE_URL${request.appId}|${request.appName}>" else "`${request.appName}`"


        SlackManager.sendTextMessage(
            Config.SLACK_CHANNEL_LOG,
            ":question: The request of <@${request.userId}> for $appText was resolved by someone"
        )
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

        val isVerified = VerificationManager.isVerified(request.user.id)
        val restrictedScopes = InstallRequestRepository.getRestrictedScopes()
        val isAppRestricted = InstallRequestRepository.isAppRestricted(request.app.id)
        val previousRequests =
            InstallRequestRepository.findPreviousRequests(request.app.id, request.user.id, request.id)
        val appCommands = if (AppCommandManager.isValid()) AppCommandManager.indexCommands(request.app.id) else emptyList()
        val commandsAllReadyInUse =
            if (appCommands.isEmpty()) emptyMap()
            else AppCommandManager.returnCommandOwners(appCommands.map { it.name })
                .filter { it.value != request.app.id }

        val decisionContext = DecisionContext(
            request = request,
            isUserVerified = isVerified,
            restrictedScopes = restrictedScopes,
            isAppRestricted = isAppRestricted,
            previousRequests = previousRequests,
            commandsAllReadyInUse = commandsAllReadyInUse,
            commands = appCommands,
        )
        val decision = DecisionEngine.evaluate(decisionContext)

        val stateId = randomString(20)

        val reviewTs = SlackWorkflowService.postInitialReviewMessage(request, isVerified, decision, decisionContext, stateId)
        InstallRequestRepository.saveRequest(request, isVerified, decision, reviewTs, stateId)

        if (decision.status == RequestStatus.APPROVED && decision.reason != RequestDecisionReason.SLACK_AUTOMATION) {
            SlackManager.approveApp(request.app.id, request.team?.id, request.enterprise?.id, request.id)
        }

        SlackWorkflowService.sendDecisionDm(
            userId = request.user.id,
            appName = request.app.name,
            requestId = request.id,
            appId = request.app.id,
            status = decision.status,
            reason = decision.reason,
            relevantScopes = decision.relevantRestrictedScopes,
            relevantCommands = decision.relevantCommands,
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
