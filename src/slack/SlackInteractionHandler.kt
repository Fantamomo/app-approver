package com.fantamomo.slack.approver.slack

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.manager.CommandManager
import com.fantamomo.slack.approver.manager.InstallRequestRepository
import com.fantamomo.slack.approver.manager.SlackManager
import com.fantamomo.slack.approver.manager.VerificationManager
import com.fantamomo.slack.approver.model.RequestDecision
import com.fantamomo.slack.approver.model.RequestDecisionReason
import com.fantamomo.slack.approver.model.RequestStatus
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object SlackInteractionHandler {
    private val logger = LoggerFactory.getLogger(SlackInteractionHandler::class.java)

    private val adminUsers = Config.ADMIN_USERS
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() && (it[0] == 'W' || it[0] == 'U') }

    fun isAdmin(userId: String): Boolean = userId in adminUsers

    suspend fun handlePayload(payloadJsonString: String, type: String? = null) {
        val element = Json.parseToJsonElement(payloadJsonString)
        val jsonObject = element.jsonObject
        handlePayload(jsonObject, type)
    }

    suspend fun handlePayload(jsonObject: JsonObject, type: String? = null) {
        try {
            val type = type ?: jsonObject["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "block_actions" -> handleBlockActions(jsonObject)
                "view_submission" -> handleViewSubmission(jsonObject)
                "slash_commands" -> handleSlashCommands(jsonObject)
                else -> logger.info("Unhandled Slack interaction type: $type")
            }
        } catch (e: Exception) {
            logger.error("Error processing Slack interaction payload", e)
        }
    }

    private suspend fun handleSlashCommands(jsonObject: JsonObject) {
        val command = jsonObject["command"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("command is missing")
        val responseUrl = jsonObject["response_url"]?.jsonPrimitive?.contentOrNull
            ?.let { Url(it) }
            ?: throw IllegalArgumentException("response_url is missing")
        if (command != Config.SLACK_SLASH_COMMAND) {
            logger.warn("Received slash command with unexpected command '$command'. Command must be '${Config.SLACK_SLASH_COMMAND}', maybe check the config or the app's slash command configuration.")
            SlackWorkflowService.sendUnknownCommandMessage(responseUrl)
            return
        }
        val userId = jsonObject["user_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("user_id is missing")
        if (userId !in adminUsers) {
            logger.warn("Unauthorized user attempted to use slash command: $userId")
            sendUnauthorizedError(responseUrl)
            return
        }
        val text = jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("text is missing")
        if (text.isBlank()) {
            SlackWorkflowService.sendHelpMessage(responseUrl)
            return
        }
        CommandManager.execute(responseUrl, text.trim().split(Regex("\\s+")))
    }

    private suspend fun handleBlockActions(json: JsonObject) {
        val userObj = json["user"]?.jsonObject
        val userId = userObj?.get("id")?.jsonPrimitive?.content ?: return
        val triggerId = json["trigger_id"]?.jsonPrimitive?.content
        val actions = json["actions"]?.jsonArray ?: return

        for (actionElement in actions) {
            val action = actionElement.jsonObject
            val actionId = action["action_id"]?.jsonPrimitive?.content ?: continue
            val value = action["value"]?.jsonPrimitive?.content ?: ""

            when (actionId) {
                "review_approve" -> handleReviewApprove(userId, value)
                "review_deny" -> handleReviewDeny(userId, value, triggerId)
                "review_restrict" -> handleReviewRestrict(userId, value, triggerId)
                "review_undo" -> handleReviewUndo(userId, value)
                "user_check_verification" -> handleUserCheckVerification(userId, value)
                "user_request_review" -> handleUserRequestReview(userId, value)
                "user_withdraw_request" -> handleUserWithdrawRequest(userId, value)
                "home_team" -> SlackWorkflowService.setHomeMode(userId, false, updateHome = false)
                "home_admin" -> SlackWorkflowService.setHomeMode(userId, true, updateHome = false)
            }
        }

        // if the action came from the app home, we need to republish the home, because the state may have changed
        if ((json["view"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "home") {
            SlackWorkflowService.publishAppHome(userId)
        }
    }

    private suspend fun handleReviewApprove(userId: String, requestId: String) {
        if (!InstallRequestRepository.isTeamMemberAuthorized(userId)) {
            logger.warn("User $userId is not authorized to approve request $requestId")
            sendUnauthorizedError(userId, requestId)
            return
        }

        val record = InstallRequestRepository.getRequest(requestId) ?: return
        InstallRequestRepository.updateResolution(
            requestId = requestId,
            newStatus = RequestStatus.APPROVED,
            resolution = RequestDecision.APPROVED,
            resolvedBy = userId,
            resolutionMessage = null,
            actorType = "ADMIN",
            action = "APPROVE",
            reason = RequestDecisionReason.MANUAL_OVERRIDE
        )

        SlackManager.approveApp(record.appId, record.teamId, record.enterpriseId.takeIf { record.teamId == null }, record.requestId)

        SlackWorkflowService.updateReviewMessage(
            record = record,
            status = RequestStatus.APPROVED,
            resolution = RequestDecision.APPROVED,
            reason = RequestDecisionReason.MANUAL_OVERRIDE,
            resolvedBy = userId,
            resolutionMessage = null
        )

        record.reviewMessageTs?.let { ts ->
            SlackWorkflowService.postReviewThreadUpdate(ts, userId, "approved the request.")
        }

        SlackWorkflowService.sendDecisionDm(
            userId = record.userId,
            appName = record.appName,
            requestId = requestId,
            status = RequestStatus.APPROVED,
            reason = null,
            resolvedBy = userId
        )

        SlackWorkflowService.logManualTransparencyEvent(
            appName = record.appName,
            userId = record.userId,
            actorId = userId,
            action = RequestDecision.APPROVED
        )
    }

    private suspend fun handleReviewDeny(userId: String, requestId: String, triggerId: String?) {
        if (!InstallRequestRepository.isTeamMemberAuthorized(userId)) {
            logger.warn("User $userId is not authorized to deny request $requestId")
            sendUnauthorizedError(userId, requestId)
            return
        }

        if (triggerId != null) {
            val modal = SlackWorkflowService.buildReasonModal("Deny Request", "modal_deny_submit", requestId, "Deny")
            val opened = SlackManager.openView(triggerId, modal)
            if (opened) return
        }

        executeDeny(userId, requestId, null)
    }

    private suspend fun handleReviewRestrict(userId: String, requestId: String, triggerId: String?) {
        if (!InstallRequestRepository.isTeamMemberAuthorized(userId)) {
            logger.warn("User $userId is not authorized to restrict request $requestId")
            sendUnauthorizedError(userId, requestId)
            return
        }

        if (triggerId != null) {
            val modal =
                SlackWorkflowService.buildReasonModal("Restrict App", "modal_restrict_submit", requestId, "Restrict")
            val opened = SlackManager.openView(triggerId, modal)
            if (opened) return
        }

        executeRestrict(userId, requestId, null)
    }

    private suspend fun handleReviewUndo(userId: String, requestId: String) {
        if (!InstallRequestRepository.isTeamMemberAuthorized(userId)) {
            logger.warn("User $userId is not authorized to undo resolution for request $requestId")
            sendUnauthorizedError(userId, requestId)
            return
        }

        val record = InstallRequestRepository.getRequest(requestId) ?: return

        if (record.resolution == RequestDecision.WITHDRAWN) {
            return
        }

        SlackManager.clearResolution(record.appId, record.teamId, record.enterpriseId)
        if (InstallRequestRepository.isAppRestricted(record.appId)) {
            InstallRequestRepository.unrestrictApp(record.appId)
        }

        InstallRequestRepository.updateResolution(
            requestId = requestId,
            newStatus = RequestStatus.DENIED,
            resolution = RequestDecision.UNDONE,
            resolvedBy = userId,
            resolutionMessage = "Resolution undone by <@$userId>",
            actorType = "ADMIN",
            action = "UNDO",
            reason = RequestDecisionReason.RESOLUTION_UNDONE
        )

        SlackWorkflowService.updateReviewMessage(
            record = record,
            status = RequestStatus.DENIED,
            resolution = RequestDecision.UNDONE,
            reason = RequestDecisionReason.RESOLUTION_UNDONE,
            resolvedBy = userId,
            resolutionMessage = "Resolution undone. User must submit a new request."
        )

        record.reviewMessageTs?.let { ts ->
            SlackWorkflowService.postReviewThreadUpdate(
                ts,
                userId,
                "undone the Slack resolution. The previous resolution has been cleared and this request is closed. A new request must be submitted if needed."
            )
        }

        SlackWorkflowService.sendUndoDm(
            userId = record.userId,
            appName = record.appName,
            adminId = userId
        )

        SlackWorkflowService.logManualTransparencyEvent(
            appName = record.appName,
            userId = record.userId,
            actorId = userId,
            action = RequestDecision.UNDONE
        )
    }

    private suspend fun handleViewSubmission(json: JsonObject) {
        val userObj = json["user"]?.jsonObject
        val userId = userObj?.get("id")?.jsonPrimitive?.content ?: return
        val view = json["view"]?.jsonObject ?: return
        val callbackId = view["callback_id"]?.jsonPrimitive?.content ?: return
        val requestId = view["private_metadata"]?.jsonPrimitive?.content ?: return

        val values = view["state"]?.jsonObject?.get("values")?.jsonObject
        val reasonInput = values?.get("reason_block")?.jsonObject?.get("reason_input")?.jsonObject
        val reason = reasonInput?.get("value")?.jsonPrimitive?.contentOrNull

        when (callbackId) {
            "modal_deny_submit" -> executeDeny(userId, requestId, reason)
            "modal_restrict_submit" -> executeRestrict(userId, requestId, reason)
        }
    }

    private suspend fun executeDeny(userId: String, requestId: String, reason: String?) {
        val record = InstallRequestRepository.getRequest(requestId) ?: return
        InstallRequestRepository.updateResolution(
            requestId = requestId,
            newStatus = RequestStatus.DENIED,
            resolution = RequestDecision.DENIED,
            resolvedBy = userId,
            resolutionMessage = reason,
            actorType = "ADMIN",
            action = "DENY",
            reason = RequestDecisionReason.MANUAL_OVERRIDE
        )

        SlackManager.cancelAppRequest(record.appId, record.teamId, record.enterpriseId.takeIf { record.teamId == null }, record.requestId)

        SlackWorkflowService.updateReviewMessage(
            record = record,
            status = RequestStatus.DENIED,
            resolution = RequestDecision.DENIED,
            reason = RequestDecisionReason.MANUAL_OVERRIDE,
            resolvedBy = userId,
            resolutionMessage = reason
        )

        record.reviewMessageTs?.let { ts ->
            SlackWorkflowService.postReviewThreadUpdate(ts, userId, "denied the request.", reason)
        }

        SlackWorkflowService.sendDecisionDm(
            userId = record.userId,
            appName = record.appName,
            requestId = requestId,
            status = RequestStatus.DENIED,
            reason = null,
            customMessage = reason,
            resolvedBy = userId
        )

        SlackWorkflowService.logManualTransparencyEvent(
            appName = record.appName,
            userId = record.userId,
            actorId = userId,
            action = RequestDecision.DENIED
        )
    }

    private suspend fun executeRestrict(userId: String, requestId: String, reason: String?) {
        val record = InstallRequestRepository.getRequest(requestId) ?: return

        InstallRequestRepository.restrictApp(
            appId = record.appId,
            appName = record.appName,
            restrictedBy = userId,
            reason = reason
        )

        InstallRequestRepository.updateResolution(
            requestId = requestId,
            newStatus = RequestStatus.DENIED,
            resolution = RequestDecision.RESTRICTED,
            resolvedBy = userId,
            resolutionMessage = reason,
            actorType = "ADMIN",
            action = "RESTRICT",
            reason = RequestDecisionReason.RESTRICTED_APPLICATION
        )

        SlackManager.restrictApp(record.appId, record.teamId, record.enterpriseId.takeIf { record.teamId == null }, record.requestId)

        SlackWorkflowService.updateReviewMessage(
            record = record,
            status = RequestStatus.DENIED,
            resolution = RequestDecision.RESTRICTED,
            reason = RequestDecisionReason.RESTRICTED_APPLICATION,
            resolvedBy = userId,
            resolutionMessage = reason
        )

        record.reviewMessageTs?.let { ts ->
            SlackWorkflowService.postReviewThreadUpdate(ts, userId, "restricted the application.", reason)
        }

        SlackWorkflowService.sendDecisionDm(
            userId = record.userId,
            appName = record.appName,
            requestId = requestId,
            status = RequestStatus.DENIED,
            reason = RequestDecisionReason.RESTRICTED_APPLICATION,
            customMessage = reason,
            resolvedBy = userId
        )

        SlackWorkflowService.logManualTransparencyEvent(
            appName = record.appName,
            userId = record.userId,
            actorId = userId,
            action = RequestDecision.RESTRICTED
        )
    }

    private suspend fun handleUserCheckVerification(userId: String, requestId: String) {
        val record = InstallRequestRepository.getRequest(requestId) ?: return
        if (record.userId != userId) return

        val isVerified = VerificationManager.isVerified(userId, ignoreCache = true)
        if (!isVerified) {
            SlackManager.sendDm(userId) {
                section {
                    markdownText(":no_entry_sign: Your account is still not verified. Please complete verification at <https://auth.hackclub.com|Hack Club Auth> and try again, or click *Request Manual Review*.")
                }
            }
            return
        }

        VerificationManager.handleUserBecameVerified(userId)
    }

    private suspend fun handleUserRequestReview(userId: String, requestId: String) {
        val record = InstallRequestRepository.getRequest(requestId) ?: return
        if (record.userId != userId) return

        if (record.status != RequestStatus.DENIED) {
            SlackWorkflowService.sendNotAbleToRequestReviewDm(
                userId = userId,
                appName = record.appName,
                status = record.status,
            )
            return
        } else {
            // it is denied, but we need to figure out if it is completely denied or still review able, yeah dont ask
            if (record.resolution != null) {
                SlackWorkflowService.sendNotAbleToRequestReviewDm(
                    userId = userId,
                    appName = record.appName,
                    status = RequestStatus.DENIED,
                )
                return
            }
        }

        InstallRequestRepository.updateStatus(
            requestId = requestId,
            newStatus = RequestStatus.PENDING_REVIEW,
            actorId = userId,
            actorType = "USER",
            action = "REQUEST_REVIEW",
            reason = RequestDecisionReason.USER_REQUESTED_REVIEW,
            message = "User requested manual review"
        )

        SlackWorkflowService.updateReviewMessage(
            record = record,
            status = RequestStatus.PENDING_REVIEW,
            resolution = null,
            reason = RequestDecisionReason.USER_REQUESTED_REVIEW,
            resolvedBy = null,
            resolutionMessage = null
        )

        record.reviewMessageTs?.let { ts ->
            SlackWorkflowService.postReviewThreadUpdate(ts, userId, "requested a manual review for this request.")
        }

        SlackWorkflowService.sendDecisionDm(
            userId = userId,
            appName = record.appName,
            requestId = requestId,
            status = RequestStatus.PENDING_REVIEW,
            reason = RequestDecisionReason.USER_REQUESTED_REVIEW
        )

        SlackWorkflowService.logUserActionTransparencyEvent(
            appName = record.appName,
            userId = userId,
            action = "REQUEST_REVIEW"
        )
    }

    private suspend fun handleUserWithdrawRequest(userId: String, requestId: String) {
        val record = InstallRequestRepository.getRequest(requestId) ?: return
        if (record.userId != userId) return

        if (record.status != RequestStatus.PENDING_REVIEW) {
            if (record.status != RequestStatus.DENIED || record.resolution != null) {
                SlackWorkflowService.sendNotAbleToWithdrawDm(
                    userId = userId,
                    appName = record.appName,
                    status = record.status,
                )
                return
            }
        }

        InstallRequestRepository.updateStatus(
            requestId = requestId,
            newStatus = RequestStatus.WITHDRAWN,
            actorId = userId,
            actorType = "USER",
            action = "WITHDRAW",
            reason = RequestDecisionReason.USER_WITHDRAWN,
            message = "User withdrew request"
        )

        SlackWorkflowService.updateReviewMessage(
            record = record,
            status = RequestStatus.WITHDRAWN,
            resolution = RequestDecision.WITHDRAWN,
            reason = RequestDecisionReason.USER_WITHDRAWN,
            resolvedBy = null,
            resolutionMessage = null
        )

        SlackManager.cancelAppRequest(record.appId, record.teamId, record.enterpriseId.takeIf { record.teamId == null }, record.requestId)

        record.reviewMessageTs?.let { ts ->
            SlackWorkflowService.postReviewThreadUpdate(ts, userId, "withdrew the installation request.")
        }

        SlackWorkflowService.sendDecisionDm(
            userId = userId,
            appName = record.appName,
            requestId = requestId,
            status = RequestStatus.WITHDRAWN,
            reason = RequestDecisionReason.USER_WITHDRAWN
        )

        SlackWorkflowService.logUserActionTransparencyEvent(
            appName = record.appName,
            userId = userId,
            action = "WITHDRAW"
        )
    }

    private suspend fun sendUnauthorizedError(userId: String, requestId: String) {
        val record = InstallRequestRepository.getRequest(requestId) ?: return
        SlackWorkflowService.sendUnauthorizedMessage(Config.SLACK_CHANNEL_REVIEW, record.reviewMessageTs, userId)
    }

    private suspend fun sendUnauthorizedError(responseUrl: String) = SlackWorkflowService.sendUnauthorizedMessage(Url(responseUrl))

    private suspend fun sendUnauthorizedError(responseUrl: Url) {
        require(responseUrl.host == "hooks.slack.com") { "Invalid response URL: $responseUrl" }
        SlackWorkflowService.sendUnauthorizedMessage(responseUrl)
    }
}
