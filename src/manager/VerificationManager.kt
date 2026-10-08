package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.RateLimits
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.db.CachedVerificationStatusTable
import com.fantamomo.slack.approver.decision.DecisionContext
import com.fantamomo.slack.approver.decision.DecisionEngine
import com.fantamomo.slack.approver.model.*
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

object VerificationManager {
    private val logger = LoggerFactory.getLogger(VerificationManager::class.java)

    private val cache: MutableMap<String, Pair<VerificationStatus, Instant>> = mutableMapOf()

    private const val AUTH_EXTERNAL_CHECK = "https://auth.hackclub.com/api/external/check"
    private const val ALT_CHECK = "http://linkbot.kavyansh.org/check"

    suspend fun start() {
        while (true) {
            // periodically check verification status of users that have not been verified yet

            val usersToCheck = DatabaseManager.transaction {
                CachedVerificationStatusTable.select(CachedVerificationStatusTable.userId)
                    .where { CachedVerificationStatusTable.verified eq false }
                    .orderBy(CachedVerificationStatusTable.lastChecked, SortOrder.ASC)
                    .limit(50)
                    .map { it[CachedVerificationStatusTable.userId] }
                    .toList()
            }
            for (user in usersToCheck) {
                val status = checkVerificationStatus(user)
                if (status.verified) {
                    handleUserBecameVerified(user)
                }
                // apparently https://auth.hackclub.com/api/external/check does not have RATE-LIMITING???
                // so we still delay
                delay(1.seconds)
            }
            delay(5.minutes)
        }
    }

    suspend fun handleUserBecameVerified(userId: String) {
        val pendingRequests = InstallRequestRepository.findPendingOrBlockedRequestsForUser(userId)
        for (record in pendingRequests) {
            val dummyRequest = AppRequested(
                id = record.requestId,
                app = SlackApp(
                    id = record.appId,
                    name = record.appName,
                    description = record.appDescription,
                    helpUrl = "",
                    privacyPolicyUrl = "",
                    appHomepageUrl = record.appUrl ?: "",
                    appDirectoryUrl = record.appUrl ?: "",
                    isAppDirectoryApproved = false,
                    isInternal = false,
                    developerType = record.developerType.value,
                    socketModeEnabled = false,
                    icons = SlackAppIcons.EMPTY,
                    additionalInfo = ""
                ),
                user = SlackUser(id = record.userId, name = "", email = null),
                team = record.teamId?.let { SlackTeam(id = it, name = "", domain = "") },
                enterprise = record.enterpriseId?.let { SlackEnterprise(id = it, name = "", domain = "") },
                scopes = record.scopes.map {
                    SlackScope(
                        name = it.name,
                        description = "",
                        isSensitive = false,
                        tokenType = it.tokenType,
                        isOptional = false,
                        isApproved = false
                    )
                },
                dateCreated = record.requestedAt,
                message = record.message,
                isUserAppCollaborator = record.isUserAppCollaborator,
                previousResolution = null
            )

            val restrictedScopes = InstallRequestRepository.getRestrictedScopes()
            val isAppRestricted = InstallRequestRepository.isAppRestricted(record.appId)
            val previousRequests =
                InstallRequestRepository.findPreviousRequests(record.appId, record.userId, record.requestId)

            val decision = DecisionEngine.evaluate(
                DecisionContext(
                    request = dummyRequest,
                    isUserVerified = true,
                    restrictedScopes = restrictedScopes,
                    isAppRestricted = isAppRestricted,
                    previousRequests = previousRequests
                )
            )

            if (decision.status == RequestStatus.APPROVED) {
                InstallRequestRepository.updateResolution(
                    requestId = record.requestId,
                    newStatus = RequestStatus.APPROVED,
                    resolution = RequestDecision.APPROVED,
                    resolvedBy = "BOT",
                    resolutionMessage = "Automatically approved after user verification",
                    actorType = "BOT",
                    action = "AUTO_APPROVE_VERIFIED",
                    reason = RequestDecisionReason.USER_VERIFIED
                )

                SlackManager.approveApp(record.appId, record.teamId, record.requestId)

                SlackWorkflowService.updateReviewMessage(
                    record = record,
                    status = RequestStatus.APPROVED,
                    resolution = RequestDecision.APPROVED,
                    reason = RequestDecisionReason.USER_VERIFIED,
                    resolvedBy = null,
                    resolutionMessage = null
                )

                record.reviewMessageTs?.let { ts ->
                    SlackWorkflowService.postReviewThreadUpdate(
                        ts,
                        null,
                        "automatically approved the request after user account verification."
                    )
                }

                SlackWorkflowService.sendDecisionDm(
                    userId = userId,
                    appName = record.appName,
                    requestId = record.requestId,
                    status = RequestStatus.APPROVED,
                    reason = RequestDecisionReason.USER_VERIFIED
                )

                SlackManager.sendTextMessage(
                    Config.SLACK_CHANNEL_LOG,
                    ":white_check_mark: `${record.appName}` requested by <@$userId> was automatically approved after account verification."
                )
            } else if (decision.status == RequestStatus.PENDING_REVIEW && record.status != RequestStatus.PENDING_REVIEW) {
                InstallRequestRepository.updateStatus(
                    requestId = record.requestId,
                    newStatus = RequestStatus.PENDING_REVIEW,
                    actorId = null,
                    actorType = "BOT",
                    action = "STATUS_UPDATE_VERIFIED",
                    reason = decision.reason,
                    message = "Moved to pending review after user verification"
                )

                SlackWorkflowService.updateReviewMessage(
                    record = record,
                    status = RequestStatus.PENDING_REVIEW,
                    resolution = null,
                    reason = decision.reason,
                    resolvedBy = null,
                    resolutionMessage = null
                )

                record.reviewMessageTs?.let { ts ->
                    SlackWorkflowService.postReviewThreadUpdate(
                        ts,
                        null,
                        "moved to manual review after user account verification."
                    )
                }
            }
        }
    }

    suspend fun isVerified(userId: String, ignoreCache: Boolean = false): Boolean {
        val cached = cache[userId]
        if (!ignoreCache && cached != null) {
            if (Clock.System.now() < cached.second) {
                return cached.first.verified
            } else {
                cache.remove(userId)
            }
        }
        val status = checkVerificationStatus(userId, ignoreCache = ignoreCache)
        cache[userId] = status to (Clock.System.now() + cacheDuration(status))
        return status.verified
    }

    private suspend fun checkVerificationStatus(userId: String, ignoreCache: Boolean = false, users: MutableSet<String> = mutableSetOf()): VerificationStatus {
        // used to prevent circular dependencies in the alt detection progress
        if (!users.add(userId)) {
            logger.warn("Detected circular dependency for user $userId in ${users.joinToString(", ")}")
            return VerificationStatus.NOT_FOUND
        }

        try {
            val databaseValue = DatabaseManager.transaction {
                CachedVerificationStatusTable.select(
                    CachedVerificationStatusTable.verificationStatus,
                    CachedVerificationStatusTable.lastChecked
                )
                    .where { CachedVerificationStatusTable.userId eq userId }
                    .singleOrNull()
                    ?.let { it[CachedVerificationStatusTable.verificationStatus] to it[CachedVerificationStatusTable.lastChecked] }
            }
            if (databaseValue != null) {
                val (status, lastChecked) = databaseValue
                if (status.verified) return status
                if (!ignoreCache) {
                    val expiration = lastChecked + cacheDuration(status)
                    if (Clock.System.now() < expiration) return status
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get verification status for user $userId from database: ${e.message}")
        }

        val provider = checkProvider(userId)
        // if the auth provider tells us that the account is not verified, we check if the account is an alt account
        if (!provider.verified) {
            // that is pretty crazy

            // so first check if the account is an alt account
            val alt = checkAlt(userId)
            // is it an alt account?
            if (alt.first) {
                // then check if the parent account is verified
                val parentAccount = checkVerificationStatus(alt.second, ignoreCache, users)
                if (parentAccount.verified) {
                    // only write to the database if the parent account is verified.
                    // It would be too complex to check the parent account always when the alt account is checked,
                    // so instead of rewriting the whole system, we set ALT as a verified status
                    // and only write to the database if the parent account is verified
                    DatabaseManager.transaction {
                        CachedVerificationStatusTable.upsert(
                            CachedVerificationStatusTable.userId,
                            where = { CachedVerificationStatusTable.userId eq userId }
                        ) {
                            it[CachedVerificationStatusTable.userId] = userId
                            it[CachedVerificationStatusTable.verificationStatus] = VerificationStatus.ALT
                            it[CachedVerificationStatusTable.verified] = VerificationStatus.ALT.verified
                            it[CachedVerificationStatusTable.mainAccount] = alt.second
                            it[CachedVerificationStatusTable.lastChecked] = Clock.System.now()
                        }
                    }
                    return VerificationStatus.ALT
                }
            }
        }
        // not an alt account or any other issue, so we just return the status from hca
        return provider
    }

    // maybe i overreacted with the try-cache's in the following function
    private suspend fun checkProvider(userId: String): VerificationStatus {
        val response = try {
            RateLimits.AUTH_CHECK.withLimit {
                SharedData.httpClient.get(AUTH_EXTERNAL_CHECK) {
                    parameter("slack_id", userId)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Failed to check verification status for user $userId", e)
            return VerificationStatus.NOT_FOUND
        }
        val text = try {
            response.bodyAsText()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Failed to receive body for user $userId", e)
            return VerificationStatus.NOT_FOUND
        }
        val body = try {
            SharedData.json.parseToJsonElement(text)
        } catch (e: Exception) {
            logger.error("Failed to parse JSON for user $userId: $text", e)
            return VerificationStatus.NOT_FOUND
        }
        val result = ((body as? JsonObject)?.get("result") as? JsonPrimitive)?.contentOrNull
        if (result == null) {
            logger.error("Failed to get result for user $userId from $text")
            return VerificationStatus.NOT_FOUND
        }
        val parsedResult = VerificationStatus.entries.find { it.value.equals(result, true) }
        if (parsedResult == null) {
            logger.error("Failed to parse result for user $userId: $result")
            return VerificationStatus.NOT_FOUND
        }
        DatabaseManager.transaction {
            CachedVerificationStatusTable.upsert(
                CachedVerificationStatusTable.userId,
                where = { CachedVerificationStatusTable.userId eq userId }
            ) {
                it[CachedVerificationStatusTable.userId] = userId
                it[CachedVerificationStatusTable.verificationStatus] = parsedResult
                it[CachedVerificationStatusTable.verified] = parsedResult.verified
                it[CachedVerificationStatusTable.lastChecked] = Clock.System.now()
            }
        }
        return parsedResult
    }

    private suspend fun checkAlt(userId: String): Pair<Boolean, String> {
        val response = try {
            SharedData.httpClient.get(ALT_CHECK) {
                parameter("slack_id", userId)
            }
        } catch (e: Exception) {
            logger.error("Failed to check alt for user $userId: $e")
            return false to ""
        }
        if (!response.status.isSuccess()) {
//            logger.warn("Failed to check alt for user $userId: ${response.status.description}")
            return false to ""
        }
        val body = try {
            SharedData.json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            logger.error("Failed to parse alt check response for user $userId: $e")
            return false to ""
        }
        if ((body["is_alt"] as? JsonPrimitive)?.booleanOrNull != true) {
            return false to ""
        }
        val mainAccount = ((body["object"] as? JsonObject)
            ?.get("parent_slack_id") as? JsonPrimitive)
            ?.contentOrNull
            ?: return false to ""

        return true to mainAccount
    }

    private fun cacheDuration(status: VerificationStatus): Duration = when (status) {
        VerificationStatus.NEEDS_SUBMISSION -> 10.minutes
        VerificationStatus.PENDING -> 5.minutes
        VerificationStatus.VERIFIED_ELIGIBLE -> 1.days
        VerificationStatus.VERIFIED_BUT_OVER_18 -> 1.days
        VerificationStatus.REJECTED -> 10.minutes
        VerificationStatus.NOT_FOUND -> 10.minutes
        VerificationStatus.OVERRIDDEN -> 1.days
        VerificationStatus.ALT -> 1.days
    }
}