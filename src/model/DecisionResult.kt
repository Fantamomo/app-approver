package com.fantamomo.slack.approver.model

data class DecisionResult(
    val status: RequestStatus,
    val reason: RequestDecisionReason,
    val relevantRestrictedScopes: List<String> = emptyList(),
    val requiresManualReview: Boolean = false,
    val isRepeatedRequest: Boolean = false,
    val explanation: String = ""
)
