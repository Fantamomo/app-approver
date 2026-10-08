package com.fantamomo.slack.approver.decision

import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.model.PreviousRequestRecord
import com.fantamomo.slack.approver.model.RestrictedScope

data class DecisionContext(
    val request: AppRequested,
    val isUserVerified: Boolean,
    val restrictedScopes: List<RestrictedScope>,
    val isAppRestricted: Boolean,
    val previousRequests: List<PreviousRequestRecord>
)