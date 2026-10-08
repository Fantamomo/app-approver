package com.fantamomo.slack.approver.model

data class PreviousRequestRecord(
    val requestId: String,
    val status: RequestStatus,
    val resolution: RequestDecision?,
    val isEnterprise: Boolean = false,
    val teamId: String? = null,
    val enterpriseId: String? = null,
    val scopes: List<ScopeIdentity>,
    val resolvedBy: String? = null
)
