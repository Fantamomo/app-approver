package com.fantamomo.slack.approver.slack

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.Constants.BULLET
import com.fantamomo.slack.approver.manager.InstallRequestRepository
import com.fantamomo.slack.approver.manager.SlackManager
import com.fantamomo.slack.approver.manager.VerificationManager
import com.fantamomo.slack.approver.model.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.slack.api.model.block.element.RichTextSectionElement
import com.slack.api.model.kotlin_extension.block.ActionsBlockBuilder
import com.slack.api.model.kotlin_extension.block.dsl.LayoutBlockDsl
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.slack.api.model.block.element.RichTextSectionElement.TextStyle.builder as styleBuilder

object SlackWorkflowService {
    private val logger = LoggerFactory.getLogger(SlackWorkflowService::class.java)

    private val appHomeCheckCooldown = ConcurrentHashMap<String, Instant>()

    private val homeAdminMode = ConcurrentHashMap<String, Boolean>()

    private fun statusEmoji(status: RequestStatus): String = when (status) {
        RequestStatus.PENDING_REVIEW -> ":hourglass_flowing_sand:"
        RequestStatus.APPROVED -> ":white_check_mark:"
        RequestStatus.DENIED -> ":no_entry_sign:"
        RequestStatus.WITHDRAWN -> ":outbox_tray:"
    }

    private fun statusLabel(status: RequestStatus, resolution: RequestDecision?): String {
        if (resolution == RequestDecision.RESTRICTED) return "Restricted"
        if (resolution == RequestDecision.UNDONE) return "Undone"
        return when (status) {
            RequestStatus.PENDING_REVIEW -> "Pending Review"
            RequestStatus.APPROVED -> "Approved"
            RequestStatus.DENIED -> "Denied"
            RequestStatus.WITHDRAWN -> "Withdrawn"
        }
    }

    private fun formatReason(reason: RequestDecisionReason?, resolutionMessage: String?, resolvedBy: String?): String {
        if (resolvedBy != null) {
            val msg = if (!resolutionMessage.isNullOrBlank()) " ($resolutionMessage)" else ""
            return "Resolved by <@$resolvedBy>$msg"
        }
        return when (reason) {
            RequestDecisionReason.USER_VERIFIED -> "Verified user with allowed scopes"
            RequestDecisionReason.USER_UNVERIFIED -> "User is not verified"
            RequestDecisionReason.USER_UNVERIFIED_ALLOWED_SCOPES -> "Unverified user with explicitly allowed scopes"
            RequestDecisionReason.ENTERPRISE_INSTALL -> "Enterprise installation"
            RequestDecisionReason.RESTRICTED_SCOPE -> "Restricted scope requested"
            RequestDecisionReason.PREVIOUSLY_DENIED -> "Identical request previously denied"
            RequestDecisionReason.RESTRICTED_APPLICATION -> "Application is restricted"
            RequestDecisionReason.MANUAL_REVIEW -> "Manual review"
            RequestDecisionReason.MANUAL_OVERRIDE -> "Manual override"
            RequestDecisionReason.USER_WITHDRAWN -> "Withdrawn by user"
            RequestDecisionReason.USER_REQUESTED_REVIEW -> "Manual review requested by user"
            RequestDecisionReason.RESOLUTION_UNDONE -> "Resolution undone"
            null -> "None"
        }
    }

    suspend fun postInitialReviewMessage(
        request: AppRequested,
        isUserVerified: Boolean,
        decision: DecisionResult,
        stateId: String
    ): String? {
        val appUrl = request.app.appHomepageUrl.ifBlank { request.app.appDirectoryUrl }
        val appLink = if (appUrl.isNotBlank()) "<$appUrl|${request.app.name}>" else request.app.name
        val statusText = "${statusEmoji(decision.status)} *${statusLabel(decision.status, null)}*"
        val reasonText = formatReason(decision.reason, null, null)
        val buttonValue = "${request.id}:$stateId"

        val messageTs = SlackManager.sendMessage(Config.SLACK_CHANNEL_REVIEW) {
            header {
                text("App Installation Request", true)
            }
            section {
                markdownText("*App:* $appLink (`${request.app.id}`)\n*User:* <@${request.user.id}>\n*Status:* $statusText\n*Reason:* $reasonText")
            }
            actions {
                if (decision.status == RequestStatus.APPROVED) {
                    button {
                        text("Undo", true)
                        actionId("review_undo")
                        value(buttonValue)
                        style("primary")
                    }
                    button {
                        text("Restrict", true)
                        actionId("review_restrict")
                        value(buttonValue)
                        style("danger")
                    }
                } else if (decision.status == RequestStatus.DENIED) {
                    button {
                        text("Override (Approve)", true)
                        actionId("review_approve")
                        value(buttonValue)
                        style("primary")
                    }
//                    button {
//                        text("Undo", true)
//                        actionId("review_undo")
//                        value(request.id)
//                        style("danger")
//                    }
                    button {
                        text("Restrict", true)
                        actionId("review_restrict")
                        value(buttonValue)
                        style("danger")
                    }
                } else {
                    button {
                        text("Approve", true)
                        actionId("review_approve")
                        value(buttonValue)
                        style("primary")
                    }
                    button {
                        text("Deny", true)
                        actionId("review_deny")
                        value(buttonValue)
                        style("danger")
                    }
                    button {
                        text("Restrict", true)
                        actionId("review_restrict")
                        value(buttonValue)
                        style("danger")
                    }
                }
            }
        } ?: return null

        val scopesText = if (request.scopes.isEmpty()) {
            "None"
        } else {
            logger.info("Scopes for ${request.id}: ${request.scopes}")
            request.scopes.joinToString("\n") { s ->
                val sens = if (s.isSensitive) " (sensitive)" else ""
                val opt = if (s.isOptional) " (optional)" else ""
                "$BULLET `${s.name}` [${s.tokenType}]$sens$opt"
            }
        }

        // while we theoretically could have used the rich text builder, it is just easier this way
        val detailsText = buildString {
            append("*Request ID:* `${request.id}`\n")
            append("*User:* <@${request.user.id}> (Verified: $isUserVerified, Collaborator: ${request.isUserAppCollaborator})\n")
            if (request.team != null) append("*Team:* `${request.team.id}` (${request.team.name})\n")
            if (request.enterprise != null) append("*Enterprise:* `${request.enterprise.id}` (${request.enterprise.name})\n")
            if (!request.message.isNullOrBlank()) append("*User Message:* ${request.message}\n")

            append("\n*App Details:*\n")
            append("$BULLET ID: `${request.app.id}`\n")
            append("$BULLET Name: ${request.app.name}\n")
            append("$BULLET Developer Type: ${request.app.developerType}\n")
            if (request.app.description.isNotBlank()) append("$BULLET Description: ${request.app.description}\n")

            append("\n*Requested Scopes:*\n$scopesText\n")

            append("\n*Initial Evaluation:*\n")
            append("$BULLET Status: ${decision.status}\n")
            append("$BULLET Reason: ${decision.reason}\n")
            if (decision.explanation.isNotBlank()) append("$BULLET Explanation: ${decision.explanation}\n")
            if (decision.relevantRestrictedScopes.isNotEmpty()) {
                append("$BULLET Relevant Scopes: `")
                append(decision.relevantRestrictedScopes.joinToString(", "))
                append("`\n")
            }
        }

        SlackManager.sendMessage(Config.SLACK_CHANNEL_REVIEW, messageTs) {
            section {
                markdownText(detailsText)
            }
        }

        return messageTs
    }

    suspend fun updateReviewMessage(
        record: InstallRequestRecord,
        status: RequestStatus,
        resolution: RequestDecision?,
        reason: RequestDecisionReason?,
        resolvedBy: String?,
        resolutionMessage: String?
    ) {
        val reviewTs = record.reviewMessageTs ?: return
        val appUrl = record.appUrl
        val appLink = if (!appUrl.isNullOrBlank()) "<$appUrl|${record.appName}>" else record.appName
        val statusText = "${statusEmoji(status)} *${statusLabel(status, resolution)}*"
        val reasonText = formatReason(reason, resolutionMessage, resolvedBy)

        SlackManager.updateMessage(Config.SLACK_CHANNEL_REVIEW, reviewTs) {
            header {
                text("App Installation Request", true)
            }
            section {
                markdownText("*App:* $appLink (`${record.appId}`)\n*User:* <@${record.userId}>\n*Status:* $statusText\n*Reason:* $reasonText")
            }
            actions {
                reviewButtons(
                    record.stateId?.let { "${record.requestId}:$it" } ?: record.requestId,
                    status,
                    resolution,
                    reason
                )
            }
        }
    }

    suspend fun postReviewThreadUpdate(
        reviewMessageTs: String,
        actorId: String?,
        actionText: String,
        reason: String? = null
    ) {
        val actor = if (actorId != null) "<@$actorId>" else "System"
        val reasonStr = if (!reason.isNullOrBlank()) "\n*Reason / Note:* $reason" else ""
        SlackManager.sendMessage(Config.SLACK_CHANNEL_REVIEW, reviewMessageTs) {
            section {
                markdownText("$actor $actionText$reasonStr")
            }
        }
    }

    suspend fun sendDecisionDm(
        userId: String,
        appName: String,
        requestId: String,
        status: RequestStatus,
        reason: RequestDecisionReason?,
        relevantScopes: List<String> = emptyList(),
        customMessage: String? = null,
        resolvedBy: String? = null
    ) {
        when (status) {
            RequestStatus.APPROVED -> {
                val by = if (resolvedBy != null) "approved by <@$resolvedBy>" else "automatically approved"
                SlackManager.sendDm(userId) {
                    section {
                        markdownText(":white_check_mark: Your installation request for *$appName* has been $by!\n\nThe app is now approved and ready to be installed.")
                    }
                    if (!customMessage.isNullOrBlank()) {
                        section {
                            markdownText("*Note:* $customMessage")
                        }
                    }
                }
            }

            RequestStatus.PENDING_REVIEW -> {
                val scopesInfo = if (relevantScopes.isNotEmpty()) {
                    "\n*Restricted scopes requiring review:* `${relevantScopes.joinToString(", ")}`"
                } else ""
                SlackManager.sendDm(userId) {
                    section {
                        markdownText(":hourglass_flowing_sand: Your installation request for *$appName* has been submitted for manual review by the approval team.$scopesInfo\n\nYou will be notified when a decision is made.")
                    }
                    actions {
                        button {
                            text("Withdraw Request", true)
                            actionId("user_withdraw_request")
                            value(requestId)
                            style("danger")
                        }
                    }
                }
            }

            RequestStatus.DENIED -> {
                if (resolvedBy != null) {
                    val msg = if (!customMessage.isNullOrBlank()) "\n*Reason:* $customMessage" else ""
                    SlackManager.sendDm(userId) {
                        section {
                            markdownText(":no_entry_sign: Your installation request for *$appName* was denied by <@$resolvedBy>.$msg")
                        }
                    }
                    return
                }

                when (reason) {
                    RequestDecisionReason.USER_UNVERIFIED -> {
                        val scopesInfo = if (relevantScopes.isNotEmpty()) {
                            "\n*Requested scopes requiring verification:* `${relevantScopes.joinToString(", ")}`"
                        } else ""
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was declined because your account is not verified.$scopesInfo\n\nTo proceed, please verify your account or request a manual review from the approval team.")
                            }
                            actions {
                                button {
                                    text("Check Verification", true)
                                    actionId("user_check_verification")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.RESTRICTED_SCOPE -> {
                        val scopesInfo = if (relevantScopes.isNotEmpty()) {
                            "\n*Restricted scopes:* `${relevantScopes.joinToString(", ")}`"
                        } else ""
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(
                                    ":no_entry_sign: Your installation request for *$appName* was declined because it requests restricted scopes that cannot be automatically approved.$scopesInfo\n\nWithdraw your request to remove them or if you really need these scopes for your workflow, you can request a manual review."
                                )
                            }
                            actions {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.ENTERPRISE_INSTALL -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was declined because you tried to install it org wide. We typically don't allow this. If you really need to install it org wide, you can request a manual review. If you don't, withdraw your request and disabled org-wide installation")
                            }
                            actions {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.PREVIOUSLY_DENIED -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was automatically declined because an identical request with the same scopes was previously declined.")
                            }
                            actions {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.RESTRICTED_APPLICATION -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":lock: Your installation request for *$appName* was declined because this application has been restricted by administrators from installation.")
                            }
                        }
                    }

                    else -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was declined.")
                            }
                        }
                    }
                }
            }

            RequestStatus.WITHDRAWN -> {
                SlackManager.sendDm(userId) {
                    section {
                        markdownText(":outbox_tray: Your installation request for *$appName* has been withdrawn.")
                    }
                }
            }
        }
    }


    suspend fun sendNotAbleToWithdrawDm(
        userId: String,
        appName: String,
        status: RequestStatus,
    ) {
        require(status != RequestStatus.PENDING_REVIEW) { "Cannot send not able to withdraw DM for pending review" }
        SlackManager.sendDm(userId) {
            section {
                when (status) {
                    RequestStatus.APPROVED -> markdownText(":warning: Could not withdraw your installation request for *$appName* because it has already been approved.")
                    RequestStatus.DENIED -> markdownText(":warning: Could not withdraw your installation request for *$appName* because it has already been denied.")
                    RequestStatus.WITHDRAWN -> markdownText(":warning: Your installation request for *$appName* has already been withdrawn.")
                }
            }
        }
    }

    suspend fun sendNotAbleToRequestReviewDm(
        userId: String,
        appName: String,
        status: RequestStatus
    ) {
        SlackManager.sendDm(userId) {
            section {
                when (status) {
                    RequestStatus.WITHDRAWN -> markdownText(":warning: Your installation request for *$appName* has already been withdrawn.")
                    RequestStatus.PENDING_REVIEW -> markdownText(":warning: Your installation request for *$appName* is already pending review.")
                    RequestStatus.APPROVED -> markdownText(":warning: Your installation request for *$appName* has already been approved.")
                    RequestStatus.DENIED -> markdownText(":warning: Your installation request for *$appName* has already been denied.")
                }
            }
        }
    }

    suspend fun sendUndoDm(
        userId: String,
        appName: String,
        adminId: String
    ) {
        SlackManager.sendDm(userId) {
            section {
                markdownText(":leftwards_arrow_with_hook: The previous resolution for your installation request for *$appName* was undone by <@$adminId>.\n\nIf you still need this application, please submit a new installation request in Slack.")
            }
        }
    }

    suspend fun logInitialTransparencyEvent(
        request: AppRequested,
        decision: DecisionResult
    ) {
        val user = "<@${request.user.id}>"
        val app = "`${request.app.name}`"

        val eventText = when (decision.status) {
            RequestStatus.APPROVED -> {
                ":white_check_mark: $user requested $app, and the request was automatically approved."
            }

            RequestStatus.PENDING_REVIEW -> {
                val scopes = if (decision.relevantRestrictedScopes.isNotEmpty()) {
                    ": `${decision.relevantRestrictedScopes.joinToString(", ")}`"
                } else ""
                ":hourglass_flowing_sand: $user requested $app (submitted for manual review because it requests restricted scopes$scopes)."
            }

            RequestStatus.DENIED -> {
                when (decision.reason) {
                    RequestDecisionReason.USER_UNVERIFIED -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because the user is not verified."
                    }

                    RequestDecisionReason.RESTRICTED_SCOPE -> {
                        val scopes = if (decision.relevantRestrictedScopes.isNotEmpty()) {
                            ": `${decision.relevantRestrictedScopes.joinToString(", ")}`"
                        } else ""
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because it requests restricted scopes$scopes."
                    }

                    RequestDecisionReason.ENTERPRISE_INSTALL -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because org-wide installations are not allowed."
                    }

                    RequestDecisionReason.PREVIOUSLY_DENIED -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because an identical request was previously denied."
                    }

                    RequestDecisionReason.RESTRICTED_APPLICATION -> {
                        ":lock: $user requested $app, but the request was automatically denied because the application is restricted."
                    }

                    else -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied."
                    }
                }
            }

            RequestStatus.WITHDRAWN -> {
                ":outbox_tray: $app request was withdrawn by $user."
            }
        }
        SlackManager.sendTextMessage(Config.SLACK_CHANNEL_LOG, eventText)
    }

    suspend fun openUnauthorizedMessage(triggerId: String) {
        val view = buildInfoModal(
            "Unauthorized",
            ":no_entry_sign: Sorry, you are not authorized to perform this action."
        )
        SlackManager.openView(triggerId, view)
    }

    suspend fun openStateMismatchMessage(triggerId: String) {
        val view = buildInfoModal(
            "State Mismatch",
            ":no_entry_sign: Sorry, there's a conflict\n\nThe request may have been updated by another user. Please refresh and try again.\n(If the error persists, please contact the administrator)"
        )
        SlackManager.openView(triggerId, view)
    }

    suspend fun sendStateMismatch(channel: String, threadId: String?, userId: String) {
        SlackManager.sendEphemeral(
            channel = channel,
            threadTs = threadId,
            userId = userId,
        ) {
            section {
                markdownText(":no_entry_sign: Sorry, state mismatch. Please try again. If this error persists, please contact the administrator.")
            }
        }
    }

    suspend fun sendUnauthorizedMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(
            responseUrl = responseUrl,
        ) {
            section {
                markdownText(":no_entry_sign: Sorry, you are not authorized to perform this action.")
            }
        }
    }

    suspend fun sendHelpMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(responseUrl = responseUrl) {
            section {
                markdownText(buildString {
                    val B = BULLET

                    appendLine("Usage info for `${Config.SLACK_SLASH_COMMAND}`:")
                    appendLine()
                    appendLine("$B `team`: Shows all team members")
                    appendLine("    $B `add <user>`: Adds a user to the team")
                    appendLine("    $B `remove <user>`: Removes a user from the team")
                    appendLine("$B `scopes`: Lists the current restricted/allowed scopes")
                    appendLine("    $B `allow <scope> [type]`: Allows a scope")
                    appendLine("    $B `restrict <scope> [type] [review]`: Restricts a scope")
                    appendLine("    $B `reset <scope> [type]`: Resets the config for a scope")
                    appendLine("    $B `allow-unverified <scope> [type]`: Allows a scope for unverified users")
                    appendLine("$B `help`: Shows this help message")
                    appendLine()
                    appendLine("Args:")
                    appendLine("$B `<scope>`: The scope to match")
                    appendLine("    $B Can be directly the scope, to only match it")
                    appendLine("    $B Or use a `*` for wildcard matching: `admin.*` (every scope starting with `admin.`), `*:read` (every scope ending with `:read`)")
                    appendLine("$B `[type]`: Can be one of the following")
                    appendLine("    $B `USER`: The user scope")
                    appendLine("    $B `BOT`: The bot scope")
                    appendLine("    $B `BOTH`: Both user and bot scope (default)")
                    appendLine("$B `[review]`: bool")
                    appendLine("    $B `true`: App is send to the review team")
                    appendLine("    $B `false`: App is directly declined (default)")
                })
            }
        }
    }

    suspend fun sendUnknownCommandMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(responseUrl = responseUrl) {
            section {
                markdownText(":question: This command is not recognized, please notify an admin. (Maybe wrong command in config)")
            }
        }
    }

    suspend fun sendUnknownArgMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(responseUrl = responseUrl) {
            section {
                markdownText(":question: Unknown argument")
            }
        }
    }

    suspend fun logManualTransparencyEvent(
        appName: String,
        userId: String,
        actorId: String,
        action: RequestDecision
    ) {
        val app = "`$appName`"
        val user = "<@$userId>"
        val actor = "<@$actorId>"

        val text = when (action) {
            RequestDecision.APPROVED -> ":white_check_mark: $app requested by $user was approved by $actor."
            RequestDecision.DENIED -> ":no_entry_sign: $app requested by $user was denied by $actor."
            RequestDecision.RESTRICTED -> ":lock: $app was restricted by $actor."
            RequestDecision.WITHDRAWN -> ":outbox_tray: $app request was withdrawn by $user."
            RequestDecision.UNDONE -> ":leftwards_arrow_with_hook: Resolution for $app requested by $user was undone by $actor."
        }
        SlackManager.sendTextMessage(Config.SLACK_CHANNEL_LOG, text)
    }

    suspend fun logUserActionTransparencyEvent(
        appName: String,
        userId: String,
        action: String
    ) {
        val app = "`$appName`"
        val user = "<@$userId>"
        val text = when (action) {
            "REQUEST_REVIEW" -> ":mag: $user requested a manual review for $app."
            "WITHDRAW" -> ":outbox_tray: $app request was withdrawn by $user."
            else -> "$user performed $action for $app."
        }
        SlackManager.sendTextMessage(Config.SLACK_CHANNEL_LOG, text)
    }

    suspend fun handleAppHomeOpened(userId: String) {
        val now = Clock.System.now()
        if (!VerificationManager.isVerifiedCached(userId)) {
            val lastCheck = appHomeCheckCooldown[userId]
            if (lastCheck == null || (now - lastCheck) >= 30.seconds) {
                appHomeCheckCooldown[userId] = now
                val isVerified = VerificationManager.isVerified(userId, ignoreCache = true)
                if (isVerified) {
                    VerificationManager.handleUserBecameVerified(userId)
                }
            }
        }
        publishAppHome(userId)
    }

    suspend fun publishAppHome(userId: String) {
        val isAdmin = SlackInteractionHandler.isAdmin(userId)
        val adminMode = isAdmin && homeAdminMode[userId] == true
        if (adminMode) {
            publishAdminHome(userId)
            return
        }

        val isTeamMember = InstallRequestRepository.isTeamMemberAuthorized(userId)
        if (isTeamMember) {
            val pendingRequests = InstallRequestRepository.getPendingRequests(limit = 10)
            val autoDenied = InstallRequestRepository.getAutoDeniedRequests(limit = 10)
            val recentResolved = InstallRequestRepository.getRecentResolvedRequests(limit = 8)

            SlackManager.publishHomeView(userId) {
                if (isAdmin) homeSwitch(false)
                header {
                    text("App Approval Dashboard", true)
                }
                section {
                    markdownText(":wave: Welcome, <@$userId>. You have access to review and manage app installation requests.")
                }
                divider()

                header {
                    text("Pending Requests (${pendingRequests.size})", true)
                }
                if (pendingRequests.isEmpty()) {
                    section {
                        markdownText(":tada: No pending installation requests to review!")
                    }
                } else {
                    for (req in pendingRequests) reviewEntry(req)
                }

                if (autoDenied.isNotEmpty()) {
                    header {
                        text("Automatically Denied (${autoDenied.size})", true)
                    }
                    section {
                        markdownText("These requests were denied automatically and need no review. You can still override them.")
                    }
                    for (req in autoDenied) reviewEntry(req)
                }

                if (recentResolved.isNotEmpty()) {
                    header {
                        text("Recent Decisions", true)
                    }
                    for (req in recentResolved) reviewEntry(req, false)
                }
            }
        } else {
            val userRequests = InstallRequestRepository.getUserRequests(userId, limit = 20)
            val isVerified = VerificationManager.isVerified(userId)

            SlackManager.publishHomeView(userId) {
                if (isAdmin) homeSwitch(false)
                header {
                    text("My App Installation Requests", true)
                }
                if (!isVerified) {
                    section {
                        markdownText(":warning: *Your account is not verified.* <https://auth.hackclub.com|Verify here>")
                    }
                }
                divider()

                if (userRequests.isEmpty()) {
                    section {
                        markdownText("You have not requested any apps yet.")
                    }
                } else {
                    for (req in userRequests) {
                        val appUrl = req.appUrl
                        val appLink = if (!appUrl.isNullOrBlank()) "<$appUrl|${req.appName}>" else req.appName
                        val statusText = "${statusEmoji(req.status)} *${statusLabel(req.status, req.resolution)}*"
                        val reason = formatReason(req.automaticDecisionReason, req.resolutionMessage, req.resolvedBy)

                        section {
                            markdownText("*App:* $appLink (`${req.appId}`)\n*Status:* $statusText\n*Details:* $reason")
                        }

                        if (req.status == RequestStatus.PENDING_REVIEW || (req.status == RequestStatus.DENIED && req.resolution == null)) {
                            actions {
                                if (!isVerified) {
                                    button {
                                        text("Check Verification", true)
                                        actionId("user_check_verification")
                                        value(req.requestId)
                                        style("primary")
                                    }
                                }
                                if (req.status != RequestStatus.PENDING_REVIEW) {
                                    button {
                                        text("Request Manual Review", true)
                                        actionId("user_request_review")
                                        value(req.requestId)
                                    }
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(req.requestId)
                                    style("danger")
                                }
                            }
                        }
                        divider()
                    }
                }
            }
        }
    }

    fun buildInfoModal(title: String, message: String): JsonObject {
        val view = JsonObject()
        view.addProperty("type", "modal")

        val titleObj = JsonObject()
        titleObj.addProperty("type", "plain_text")
        titleObj.addProperty("text", title.take(24))
        titleObj.addProperty("emoji", true)
        view.add("title", titleObj)

        val closeObj = JsonObject()
        closeObj.addProperty("type", "plain_text")
        closeObj.addProperty("text", "OK")
        closeObj.addProperty("emoji", true)
        view.add("close", closeObj)

        val blocks = JsonArray()

        val section = JsonObject()
        section.addProperty("type", "section")

        val text = JsonObject()
        text.addProperty("type", "mrkdwn")
        text.addProperty("text", message)
        section.add("text", text)
        blocks.add(section)

        view.add("blocks", blocks)

        return view
    }

    fun buildReasonModal(
        title: String,
        callbackId: String,
        requestId: String,
        submitLabel: String = "Submit"
    ): JsonObject {
        val view = JsonObject()
        view.addProperty("type", "modal")
        view.addProperty("callback_id", callbackId)
        view.addProperty("private_metadata", requestId)

        val titleObj = JsonObject()
        titleObj.addProperty("type", "plain_text")
        titleObj.addProperty("text", title.take(24))
        titleObj.addProperty("emoji", true)
        view.add("title", titleObj)

        val submitObj = JsonObject()
        submitObj.addProperty("type", "plain_text")
        submitObj.addProperty("text", submitLabel.take(24))
        submitObj.addProperty("emoji", true)
        view.add("submit", submitObj)

        val closeObj = JsonObject()
        closeObj.addProperty("type", "plain_text")
        closeObj.addProperty("text", "Cancel")
        closeObj.addProperty("emoji", true)
        view.add("close", closeObj)

        val blocks = JsonArray()
        val inputBlock = JsonObject()
        inputBlock.addProperty("type", "input")
        inputBlock.addProperty("block_id", "reason_block")
        inputBlock.addProperty("optional", true)

        val labelObj = JsonObject()
        labelObj.addProperty("type", "plain_text")
        labelObj.addProperty("text", "Reason / Notes (Optional)")
        labelObj.addProperty("emoji", true)
        inputBlock.add("label", labelObj)

        val elementObj = JsonObject()
        elementObj.addProperty("type", "plain_text_input")
        elementObj.addProperty("action_id", "reason_input")
        elementObj.addProperty("multiline", true)

        val placeholderObj = JsonObject()
        placeholderObj.addProperty("type", "plain_text")
        placeholderObj.addProperty(
            "text",
            "Provide an explanation for this decision..."
        )
        elementObj.add("placeholder", placeholderObj)

        inputBlock.add("element", elementObj)
        blocks.add(inputBlock)
        view.add("blocks", blocks)

        return view
    }

    private fun buildStyle(builder: RichTextSectionElement.TextStyle.TextStyleBuilder.() -> Unit): RichTextSectionElement.TextStyle? {
        val style = styleBuilder()
        style.builder()
        return style.build()
    }

    private fun ActionsBlockBuilder.reviewButtons(
        buttonValue: String,
        status: RequestStatus,
        resolution: RequestDecision?,
        reason: RequestDecisionReason?
    ) {
        when {
            status == RequestStatus.DENIED && resolution == null -> {
                button {
                    text("Override (Approve)", true)
                    actionId("review_approve")
                    value(buttonValue)
                    style("primary")
                }
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            resolution == RequestDecision.UNDONE || reason == RequestDecisionReason.RESOLUTION_UNDONE -> {
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            resolution == RequestDecision.RESTRICTED -> {
                button {
                    text("Undo", true)
                    actionId("review_undo")
                    value(buttonValue)
                    style("primary")
                }
            }

            resolution == RequestDecision.WITHDRAWN -> {
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            resolution == RequestDecision.APPROVED ||
                    status == RequestStatus.APPROVED ||
                    resolution == RequestDecision.DENIED -> {
                button {
                    text("Undo", true)
                    actionId("review_undo")
                    value(buttonValue)
                    style("primary")
                }
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            else -> {
                button {
                    text("Approve", true)
                    actionId("review_approve")
                    value(buttonValue)
                    style("primary")
                }
                button {
                    text("Deny", true)
                    actionId("review_deny")
                    value(buttonValue)
                    style("danger")
                }
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }
        }
    }

    private fun LayoutBlockDsl.reviewEntry(req: InstallRequestRecord, showButtons: Boolean = true) {
        val appUrl = req.appUrl
        val appLink = if (!appUrl.isNullOrBlank()) "<$appUrl|${req.appName}>" else req.appName
        val statusText = "${statusEmoji(req.status)} *${statusLabel(req.status, req.resolution)}*"
        val reviewLink = if (!req.reviewMessageTs.isNullOrBlank()) {
            " - <https://slack.com/archives/${Config.SLACK_CHANNEL_REVIEW}/p${
                req.reviewMessageTs.replace(
                    ".",
                    ""
                )
            }|View Thread>"
        } else ""

        section {
            markdownText(
                "*App:* $appLink (`${req.appId}`)\n*User:* <@${req.userId}>\n" +
                        "*Status:* $statusText$reviewLink\n" +
                        "*Reason:* ${formatReason(req.automaticDecisionReason, req.resolutionMessage, req.resolvedBy)}"
            )
        }
        if (showButtons) {
            actions {
                reviewButtons(req.stateId?.let { "${req.requestId}:$it" } ?: req.requestId,
                    req.status,
                    req.resolution,
                    null)
            }
        }
        divider()
    }

    private fun LayoutBlockDsl.homeSwitch(adminMode: Boolean) {
        actions {
            button {
                text("Team Home", true)
                actionId("home_team")
                value("team")
                if (!adminMode) style("primary")
            }
            button {
                text("Admin Home", true)
                actionId("home_admin")
                value("admin")
                if (adminMode) style("primary")
            }
        }
    }

    suspend fun setHomeMode(userId: String, admin: Boolean, updateHome: Boolean = true) {
        if (admin && !SlackInteractionHandler.isAdmin(userId)) return
        homeAdminMode[userId] = admin
        if (updateHome) {
            publishAppHome(userId)
        }
    }

    private suspend fun publishAdminHome(userId: String) {
        val members = InstallRequestRepository.getTeamMembers()
        val scopes = InstallRequestRepository.getRestrictedScopes()

        val membersText =
            if (members.isEmpty()) "No team members yet." else members.joinToString("\n") { "$BULLET <@$it>" }
        val scopesText = if (scopes.isEmpty()) {
            "No scope configuration exists yet."
        } else {
            buildString {
                for (level in RestrictionLevel.entries) {
                    val group = scopes.filter { it.level == level }.sortedBy { it.pattern }
                    if (group.isEmpty()) continue
                    append(
                        when (level) {
                            RestrictionLevel.RESTRICTED -> ":no_entry_sign: *Restricted*"
                            RestrictionLevel.ALLOWED_FOR_UNVERIFIED -> ":unlock: *Allowed for unverified users*"
                            RestrictionLevel.ALLOWED -> ":white_check_mark: *Allowed*"
                        }
                    )
                    append('\n')
                    for (s in group) {
                        append("$BULLET `${s.pattern}` [${s.scopeType}]")
                        if (level == RestrictionLevel.RESTRICTED) append(if (s.review) " – review" else " – declined")
                        append('\n')
                    }
                    append('\n')
                }
            }.trim()
        }

        SlackManager.publishHomeView(userId) {
            homeSwitch(true)
            header {
                text("Admin Dashboard", true)
            }
            divider()
            header {
                text("Team Members (${members.size})", true)
            }
            section {
                markdownText(membersText.take(3000))
            }
            divider()
            header {
                text("Scope Configuration", true)
            }
            section {
                markdownText(scopesText.take(3000))
            }
        }
    }
}
