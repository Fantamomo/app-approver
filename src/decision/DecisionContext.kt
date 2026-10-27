package com.fantamomo.slack.approver.decision

import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.model.PreviousRequestRecord
import com.fantamomo.slack.approver.model.RestrictedScope
import com.fantamomo.slack.approver.model.SlashCommand

data class DecisionContext(
    val request: AppRequested,
    val isUserVerified: Boolean,
    val restrictedScopes: List<RestrictedScope>,
    val isAppRestricted: Boolean,
    val previousRequests: List<PreviousRequestRecord>,
    val commands: List<SlashCommand>,
    val commandsAllReadyInUse: Map<String, String>,
    val unfurlDomains: Set<String>
)