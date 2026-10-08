package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.db.*
import com.fantamomo.slack.approver.model.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.slf4j.LoggerFactory
import kotlin.time.Clock

object InstallRequestRepository {

    private val logger = LoggerFactory.getLogger(InstallRequestRepository::class.java)

    suspend fun isAppRestricted(appId: String): Boolean {
        return try {
            DatabaseManager.transaction {
                AppRestrictionTable.select(AppRestrictionTable.appId)
                    .where { (AppRestrictionTable.appId eq appId) and (AppRestrictionTable.deleted eq false) }
                    .singleOrNull() != null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while checking if app $appId is restricted", e)
            throw e
        }
    }

    suspend fun restrictApp(appId: String, appName: String, restrictedBy: String, reason: String?) {
        try {
            DatabaseManager.transaction {
                AppRestrictionTable.upsert(
                    AppRestrictionTable.appId,
                    where = { AppRestrictionTable.appId eq appId }
                ) {
                    it[AppRestrictionTable.appId] = appId
                    it[AppRestrictionTable.appName] = appName
                    it[AppRestrictionTable.restrictedBy] = restrictedBy
                    it[AppRestrictionTable.restrictedAt] = Clock.System.now()
                    it[AppRestrictionTable.reason] = reason
                    it[AppRestrictionTable.deleted] = false
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while restricting app $appId", e)
            throw e
        }
    }

    suspend fun unrestrictApp(appId: String) {
        try {
            DatabaseManager.transaction {
                AppRestrictionTable.update({ AppRestrictionTable.appId eq appId }) {
                    it[deleted] = true
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while unrestricting app $appId", e)
            throw e
        }
    }

    suspend fun isTeamMemberAuthorized(userId: String): Boolean {
        return try {
            DatabaseManager.transaction {
                ApproveTeamMemberTable.select(ApproveTeamMemberTable.userId)
                    .where { (ApproveTeamMemberTable.userId eq userId) and (ApproveTeamMemberTable.deleted eq false) }
                    .singleOrNull() != null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while checking if member $userId is authorized", e)
            throw e
        }
    }

    suspend fun exists(requestId: String): Boolean {
        return try {
            DatabaseManager.transaction {
                InstallRequestTable.select(InstallRequestTable.requestId)
                    .where { InstallRequestTable.requestId eq requestId }
                    .singleOrNull() != null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while checking if request $requestId exists", e)
            throw e
        }
    }

    suspend fun getRequest(requestId: String): InstallRequestRecord? {
        return try {
            DatabaseManager.transaction {
                val row = InstallRequestTable.selectAll()
                    .where { InstallRequestTable.requestId eq requestId }
                    .singleOrNull() ?: return@transaction null

                val scopes = InstallRequestScopeTable.select(
                    InstallRequestScopeTable.scope,
                    InstallRequestScopeTable.tokenType
                )
                    .where { InstallRequestScopeTable.requestId eq requestId }
                    .map {
                        ScopeIdentity(
                            name = it[InstallRequestScopeTable.scope],
                            tokenType = it[InstallRequestScopeTable.tokenType]
                        )
                    }
                    .toList()

                mapRowToRecord(row, scopes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while finding request $requestId", e)
            throw e
        }
    }

    suspend fun findPreviousRequests(appId: String, userId: String, excludeRequestId: String? = null): List<PreviousRequestRecord> {
        return try {
            DatabaseManager.transaction {
                val query = InstallRequestTable.select(
                    InstallRequestTable.requestId,
                    InstallRequestTable.status,
                    InstallRequestTable.resolution,
                    InstallRequestTable.enterpriseId,
                    InstallRequestTable.teamId,
                    InstallRequestTable.resolvedBy
                ).where {
                    if (excludeRequestId != null) {
                        (InstallRequestTable.appId eq appId) and
                                (InstallRequestTable.userId eq userId) and
                                (InstallRequestTable.requestId neq excludeRequestId)
                    } else {
                        (InstallRequestTable.appId eq appId) and
                                (InstallRequestTable.userId eq userId)
                    }
                }

                val rows = query.toList()
                val result = mutableListOf<PreviousRequestRecord>()

                for (row in rows) {
                    val reqId = row[InstallRequestTable.requestId]
                    val scopes = InstallRequestScopeTable.select(
                        InstallRequestScopeTable.scope,
                        InstallRequestScopeTable.tokenType
                    )
                        .where { InstallRequestScopeTable.requestId eq reqId }
                        .map {
                            ScopeIdentity(
                                name = it[InstallRequestScopeTable.scope],
                                tokenType = it[InstallRequestScopeTable.tokenType]
                            )
                        }
                        .toList()

                    result.add(
                        PreviousRequestRecord(
                            requestId = reqId,
                            status = row[InstallRequestTable.status],
                            resolution = row[InstallRequestTable.resolution],
                            isEnterprise = row[InstallRequestTable.enterpriseId] != null,
                            teamId = row[InstallRequestTable.teamId],
                            enterpriseId = row[InstallRequestTable.enterpriseId],
                            scopes = scopes,
                            resolvedBy = row[InstallRequestTable.resolvedBy]
                        )
                    )
                }
                result
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while finding previous requests of $appId for user $userId", e)
            throw e
        }
    }

    suspend fun getUserRequests(userId: String, limit: Int = 20): List<InstallRequestRecord> {
        return try {
            DatabaseManager.transaction {
                val query = InstallRequestTable.selectAll()
                    .where { InstallRequestTable.userId eq userId }
                    .orderBy(InstallRequestTable.requestedAt, SortOrder.DESC)
                    .limit(limit)

                val rows = query.toList()
                rows.map { row ->
                    val reqId = row[InstallRequestTable.requestId]
                    val scopes = InstallRequestScopeTable.select(
                        InstallRequestScopeTable.scope,
                        InstallRequestScopeTable.tokenType
                    )
                        .where { InstallRequestScopeTable.requestId eq reqId }
                        .map {
                            ScopeIdentity(
                                name = it[InstallRequestScopeTable.scope],
                                tokenType = it[InstallRequestScopeTable.tokenType]
                            )
                        }
                        .toList()

                    mapRowToRecord(row, scopes)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while finding requests of $userId", e)
            throw e
        }
    }

    suspend fun getPendingRequests(limit: Int = 20): List<InstallRequestRecord> {
        return try {
            DatabaseManager.transaction {
                val query = InstallRequestTable.selectAll()
                    .where { (InstallRequestTable.status eq RequestStatus.PENDING_REVIEW) or ((InstallRequestTable.status eq RequestStatus.DENIED) and (InstallRequestTable.resolution.isNull())) }
                    .orderBy(InstallRequestTable.requestedAt, SortOrder.DESC)
                    .limit(limit)

                val rows = query.toList()
                rows.map { row ->
                    val reqId = row[InstallRequestTable.requestId]
                    val scopes = InstallRequestScopeTable.select(
                        InstallRequestScopeTable.scope,
                        InstallRequestScopeTable.tokenType
                    )
                        .where { InstallRequestScopeTable.requestId eq reqId }
                        .map {
                            ScopeIdentity(
                                name = it[InstallRequestScopeTable.scope],
                                tokenType = it[InstallRequestScopeTable.tokenType]
                            )
                        }
                        .toList()

                    mapRowToRecord(row, scopes)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error occurred while finding pending requests", e)
            throw e
        }
    }

    suspend fun getRecentResolvedRequests(limit: Int = 10): List<InstallRequestRecord> {
        return try {
            DatabaseManager.transaction {
                val query = InstallRequestTable.selectAll()
                    .where { InstallRequestTable.resolution.isNotNull() or (InstallRequestTable.status eq RequestStatus.APPROVED) }
                    .orderBy(InstallRequestTable.updatedAt, SortOrder.DESC)
                    .limit(limit)

                val rows = query.toList()
                rows.map { row ->
                    val reqId = row[InstallRequestTable.requestId]
                    val scopes = InstallRequestScopeTable.select(
                        InstallRequestScopeTable.scope,
                        InstallRequestScopeTable.tokenType
                    )
                        .where { InstallRequestScopeTable.requestId eq reqId }
                        .map {
                            ScopeIdentity(
                                name = it[InstallRequestScopeTable.scope],
                                tokenType = it[InstallRequestScopeTable.tokenType]
                            )
                        }
                        .toList()

                    mapRowToRecord(row, scopes)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Failed to find recent resolved requests", e)
            throw e
        }
    }

    suspend fun findPendingOrBlockedRequestsForUser(userId: String): List<InstallRequestRecord> {
        return try {
            DatabaseManager.transaction {
                val query = InstallRequestTable.selectAll()
                    .where {
                        (InstallRequestTable.userId eq userId) and
                                (
                                        (InstallRequestTable.status eq RequestStatus.PENDING_REVIEW) or
                                                ((InstallRequestTable.status eq RequestStatus.DENIED) and (InstallRequestTable.resolution.isNull()))
                                        )
                    }

                val rows = query.toList()
                rows.map { row ->
                    val reqId = row[InstallRequestTable.requestId]
                    val scopes = InstallRequestScopeTable.select(
                        InstallRequestScopeTable.scope,
                        InstallRequestScopeTable.tokenType
                    )
                        .where { InstallRequestScopeTable.requestId eq reqId }
                        .map {
                            ScopeIdentity(
                                name = it[InstallRequestScopeTable.scope],
                                tokenType = it[InstallRequestScopeTable.tokenType]
                            )
                        }
                        .toList()

                    mapRowToRecord(row, scopes)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Failed to find pending or blocked requests for user $userId", e)
            throw e
        }
    }

    private fun mapRowToRecord(row: ResultRow, scopes: List<ScopeIdentity>): InstallRequestRecord {
        return try {
            InstallRequestRecord(
                requestId = row[InstallRequestTable.requestId],
                appId = row[InstallRequestTable.appId],
                appName = row[InstallRequestTable.appName],
                appDescription = row[InstallRequestTable.appDescription],
                appUrl = row[InstallRequestTable.appUrl],
                developerType = row[InstallRequestTable.developerType],
                userId = row[InstallRequestTable.userId],
                teamId = row[InstallRequestTable.teamId],
                enterpriseId = row[InstallRequestTable.enterpriseId],
                message = row[InstallRequestTable.message],
                isUserAppCollaborator = row[InstallRequestTable.isUserAppCollaborator],
                requestedAt = row[InstallRequestTable.requestedAt],
                userVerified = row[InstallRequestTable.userVerified],
                automaticDecision = row[InstallRequestTable.automaticDecision],
                automaticDecisionReason = row[InstallRequestTable.automaticDecisionReason],
                status = row[InstallRequestTable.status],
                resolvedBy = row[InstallRequestTable.resolvedBy],
                resolvedAt = row[InstallRequestTable.resolvedAt],
                resolutionMessage = row[InstallRequestTable.resolutionMessage],
                resolution = row[InstallRequestTable.resolution],
                reviewMessageTs = row[InstallRequestTable.reviewMessageTs],
                scopes = scopes
            )
        } catch (e: Exception) {
            logger.error("Error mapping row to InstallRequestRecord", e)
            throw e
        }
    }

    suspend fun saveRequest(
        request: AppRequested,
        isUserVerified: Boolean,
        decision: DecisionResult,
        reviewMessageTs: String? = null
    ) {
        val now = Clock.System.now()
        val devType = AppDevelopmentType.entries.find { it.value.equals(request.app.developerType, ignoreCase = true) }
            ?: AppDevelopmentType.INTERNAL

        try {
            DatabaseManager.transaction {
                InstallRequestTable.insert {
                    it[requestId] = request.id
                    it[appId] = request.app.id
                    it[appName] = request.app.name.take(100)
                    it[appDescription] = request.app.description.take(1000)
                    it[appUrl] = request.app.appHomepageUrl.ifBlank { request.app.appDirectoryUrl }.take(255)
                    it[developerType] = devType
                    it[userId] = request.user.id
                    it[teamId] = request.team?.id
                    it[enterpriseId] = request.enterprise?.id
                    it[message] = request.message?.take(1000)
                    it[isUserAppCollaborator] = request.isUserAppCollaborator
                    it[requestedAt] = request.dateCreated
                    it[userVerified] = isUserVerified
                    it[automaticDecision] = decision.status
                    it[automaticDecisionReason] = decision.reason
                    it[status] = decision.status
                    it[this.reviewMessageTs] = reviewMessageTs
                    it[createdAt] = now
                    it[updatedAt] = now
                }

                if (request.scopes.isNotEmpty()) {
                    InstallRequestScopeTable.batchInsert(request.scopes) { scope ->
                        this[InstallRequestScopeTable.requestId] = request.id
                        this[InstallRequestScopeTable.scope] = scope.name.take(50)
                        this[InstallRequestScopeTable.description] = scope.description.take(500)
                        this[InstallRequestScopeTable.sensitive] = scope.isSensitive
                        this[InstallRequestScopeTable.tokenType] = scope.tokenType
                        this[InstallRequestScopeTable.optional] = scope.isOptional
                        this[InstallRequestScopeTable.previousApproved] = scope.isApproved
                    }
                }

                request.previousResolution?.let { prev ->
                    InstallRequestPreviousResolutionTable.insert {
                        it[requestId] = request.id
                        it[status] = prev.status
                        it[resolvedByType] = prev.lastResolvedBy.actorType
                        it[resolvedById] = prev.lastResolvedBy.actorId
                        it[resolvedAt] = null
                        it[rawScopes] = prev.scopes.joinToString(",") { s -> "${s.tokenType}:${s.name}" }
                    }
                }

                InstallRequestHistoryTable.insert {
                    it[InstallRequestHistoryTable.requestId] = request.id
                    it[InstallRequestHistoryTable.status] = decision.status.name
                    it[InstallRequestHistoryTable.actorId] = null
                    it[InstallRequestHistoryTable.actorType] = "BOT"
                    it[InstallRequestHistoryTable.action] = "AUTOMATIC_DECISION"
                    it[InstallRequestHistoryTable.reason] = decision.reason.name
                    it[InstallRequestHistoryTable.message] = decision.explanation
                    it[InstallRequestHistoryTable.createdAt] = now
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error saving requests $request", e)
            throw e
        }
    }

    suspend fun updateReviewMessageTs(requestId: String, ts: String) {
        try {
            DatabaseManager.transaction {
                InstallRequestTable.update({ InstallRequestTable.requestId eq requestId }) {
                    it[reviewMessageTs] = ts
                    it[updatedAt] = Clock.System.now()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error updating review message timestamp for request $requestId", e)
            throw e
        }
    }

    suspend fun updateResolution(
        requestId: String,
        newStatus: RequestStatus,
        resolution: RequestDecision,
        resolvedBy: String,
        resolutionMessage: String?,
        actorType: String = "ADMIN",
        action: String = resolution.name,
        reason: RequestDecisionReason = RequestDecisionReason.MANUAL_OVERRIDE
    ) {
        val now = Clock.System.now()
        try {
            DatabaseManager.transaction {
                InstallRequestTable.update({ InstallRequestTable.requestId eq requestId }) {
                    it[status] = newStatus
                    it[this.resolution] = resolution
                    it[this.resolvedBy] = resolvedBy
                    it[resolvedAt] = now
                    it[this.resolutionMessage] = resolutionMessage
                    it[updatedAt] = now
                }

                InstallRequestHistoryTable.insert {
                    it[InstallRequestHistoryTable.requestId] = requestId
                    it[InstallRequestHistoryTable.status] = newStatus.name
                    it[InstallRequestHistoryTable.actorId] = resolvedBy
                    it[InstallRequestHistoryTable.actorType] = actorType
                    it[InstallRequestHistoryTable.action] = action
                    it[InstallRequestHistoryTable.reason] = reason.name
                    it[InstallRequestHistoryTable.message] = resolutionMessage
                    it[InstallRequestHistoryTable.createdAt] = now
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error updating resolution for request $requestId", e)
            throw e
        }
    }

    suspend fun updateStatus(
        requestId: String,
        newStatus: RequestStatus,
        actorId: String?,
        actorType: String,
        action: String,
        reason: RequestDecisionReason?,
        message: String?
    ) {
        val now = Clock.System.now()
        try {
            DatabaseManager.transaction {
                InstallRequestTable.update({ InstallRequestTable.requestId eq requestId }) {
                    it[status] = newStatus
                    it[updatedAt] = now
                }

                InstallRequestHistoryTable.insert {
                    it[InstallRequestHistoryTable.requestId] = requestId
                    it[InstallRequestHistoryTable.status] = newStatus.name
                    it[InstallRequestHistoryTable.actorId] = actorId
                    it[InstallRequestHistoryTable.actorType] = actorType
                    it[InstallRequestHistoryTable.action] = action
                    it[InstallRequestHistoryTable.reason] = reason?.name
                    it[InstallRequestHistoryTable.message] = message
                    it[InstallRequestHistoryTable.createdAt] = now
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error updating status for request $requestId", e)
            throw e
        }
    }

    suspend fun getRestrictedScopes(): List<RestrictedScope> {
        return try {
            DatabaseManager.transaction {
                RestrictedScopesTable.selectAll().map {
                    RestrictedScope(
                        it[RestrictedScopesTable.scope],
                        it[RestrictedScopesTable.restricted],
                        it[RestrictedScopesTable.scopeType],
                        it[RestrictedScopesTable.review]
                    )
                }.toList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Error getting restricted scopes", e)
            throw e
        }
    }
}
