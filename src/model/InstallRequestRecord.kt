package com.fantamomo.slack.approver.model

import kotlin.time.Instant

data class InstallRequestRecord(
    val requestId: String,
    val appId: String,
    val appName: String,
    val appDescription: String,
    val appUrl: String?,
    val developerType: AppDevelopmentType,
    val userId: String,
    val teamId: String?,
    val enterpriseId: String?,
    val message: String?,
    val isUserAppCollaborator: Boolean,
    val requestedAt: Long,
    val userVerified: Boolean,
    val automaticDecision: RequestStatus,
    val automaticDecisionReason: RequestDecisionReason?,
    val status: RequestStatus,
    val resolvedBy: String?,
    val resolvedAt: Instant?,
    val resolutionMessage: String?,
    val resolution: RequestDecision?,
    val reviewMessageTs: String?,
    val scopes: List<ScopeIdentity>
)