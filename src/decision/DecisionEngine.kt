package com.fantamomo.slack.approver.decision

import com.fantamomo.slack.approver.model.*

object DecisionEngine {

    fun evaluate(context: DecisionContext): DecisionResult {
        // 1. if the app is restricted, deny the request
        if (context.isAppRestricted) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.RESTRICTED_APPLICATION,
                explanation = "The application is restricted from being installed."
            )
        }

        // 2. if the request is for an enterprise installation, deny the request
        if (context.request.enterprise != null) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.ENTERPRISE_INSTALL,
                explanation = "Enterprise installations require manual approval."
            )
        }

        val currentScopes = context.request.scopes.map {
            ScopeIdentity(it.name.trim().lowercase(), it.tokenType.trim().lowercase())
        }.toSet()

        val isRepeatedDenied = context.previousRequests.any { prev ->
            if (prev.isEnterprise) return@any false
            val wasDenied = (prev.status == RequestStatus.DENIED ||
                    prev.resolution == RequestDecision.DENIED ||
                    prev.resolution == RequestDecision.RESTRICTED) &&
                    prev.resolution != RequestDecision.UNDONE
            if (!wasDenied) return@any false
            val prevScopes = prev.scopes.map {
                ScopeIdentity(it.name.trim().lowercase(), it.tokenType.trim().lowercase())
            }.toSet()
            prevScopes == currentScopes
        }

        // 3. if the request has been previously denied with the same scopes, deny the request
        if (isRepeatedDenied) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.PREVIOUSLY_DENIED,
                isRepeatedRequest = true,
                explanation = "An identical request with the same scopes was previously denied."
            )
        }

        // 4. check all the scopes
        val nonReviewableRestrictedScopes = mutableListOf<String>()
        val reviewableRestrictedScopes = mutableListOf<String>()
        val unallowedForUnverifiedScopes = mutableListOf<String>()

        for (requestedScope in context.request.scopes) {
            val scopeName = requestedScope.name.trim()
            val tokenType = requestedScope.tokenType.trim()

            val matches = context.restrictedScopes.filter { rule ->
                val typeMatches = when (rule.scopeType) {
                    ScopeType.BOTH -> true
                    ScopeType.USER -> tokenType.equals("user", ignoreCase = true)
                    ScopeType.BOT -> tokenType.equals("bot", ignoreCase = true)
                }
                typeMatches && rule.scope.match(scopeName)
            }

            if (matches.isEmpty()) {
                unallowedForUnverifiedScopes.add(scopeName)
                continue
            }

            val highestPriority = matches.minOf { it.scope.priority }
            val bestMatches = matches.filter { it.scope.priority == highestPriority }

            val isRestricted = bestMatches.any { it.level == RestrictionLevel.RESTRICTED }
            val isAllowedForUnverified = bestMatches.any { it.level == RestrictionLevel.ALLOWED_FOR_UNVERIFIED }

            if (isRestricted) {
                val requiresReviewOnly = bestMatches.filter { it.level == RestrictionLevel.RESTRICTED }.all { it.review }
                if (requiresReviewOnly) {
                    reviewableRestrictedScopes.add(scopeName)
                } else {
                    nonReviewableRestrictedScopes.add(scopeName)
                }
            }

            if (!isAllowedForUnverified) {
                unallowedForUnverifiedScopes.add(scopeName)
            }
        }

        val allRestrictedScopes = (nonReviewableRestrictedScopes + reviewableRestrictedScopes).distinct()

        val previousApprovedRequests = context.previousRequests.filter { prev ->
            !prev.isEnterprise && (prev.status == RequestStatus.APPROVED || prev.resolution == RequestDecision.APPROVED)
        }
        val previouslyApprovedScopeNames = previousApprovedRequests.flatMap { it.scopes.map { s -> s.name.trim().lowercase() } }.toSet()

        if (previousApprovedRequests.isNotEmpty()) {
            val newlyRestrictedScopes = allRestrictedScopes.filter { scope -> !previouslyApprovedScopeNames.contains(scope.lowercase()) }
            // 5. if the request has been previously approved with some restricted scopes, but no new restricted scopes, approve the request
            if (newlyRestrictedScopes.isEmpty()) {
                if (context.isUserVerified) {
                    return DecisionResult(
                        status = RequestStatus.APPROVED,
                        reason = RequestDecisionReason.USER_VERIFIED,
                        explanation = "Request automatically approved: all requested restricted scopes were previously approved for this user."
                    )
                } else {
                    val newlyUnallowedScopes = unallowedForUnverifiedScopes.filter { scope -> !previouslyApprovedScopeNames.contains(scope.lowercase()) }
                    if (newlyUnallowedScopes.isEmpty()) {
                        return DecisionResult(
                            status = RequestStatus.APPROVED,
                            reason = RequestDecisionReason.USER_UNVERIFIED_ALLOWED_SCOPES,
                            explanation = "Request automatically approved: all requested scopes were previously approved."
                        )
                    }
                }
            }
        }

        // 6. if the request contains at least one non-reviewable restricted scope, deny the request
        if (nonReviewableRestrictedScopes.isNotEmpty()) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.RESTRICTED_SCOPE,
                relevantRestrictedScopes = allRestrictedScopes,
                requiresManualReview = false,
                explanation = "The request contains restricted scopes that cannot be approved."
            )
        }

        if (context.isUserVerified) {
            // 7. if the request contains at least one reviewable restricted scope, send to manual review
            if (reviewableRestrictedScopes.isNotEmpty()) {
                return DecisionResult(
                    status = RequestStatus.PENDING_REVIEW,
                    reason = RequestDecisionReason.RESTRICTED_SCOPE,
                    relevantRestrictedScopes = reviewableRestrictedScopes.distinct(),
                    requiresManualReview = true,
                    explanation = "The request contains restricted scopes requiring manual review."
                )
            }
            // 8. if the request contains no restricted scopes, approve the request
            return DecisionResult(
                status = RequestStatus.APPROVED,
                reason = RequestDecisionReason.USER_VERIFIED,
                explanation = "Request automatically approved for verified user."
            )
        }

        // 9. if we get here and there are still restricted scopes, deny the request
        if (allRestrictedScopes.isNotEmpty()) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.RESTRICTED_SCOPE,
                relevantRestrictedScopes = allRestrictedScopes,
                requiresManualReview = false,
                explanation = "The request contains restricted scopes."
            )
        }

        // 10. if the user is not verified and there are restricted scopes, deny the request
        if (unallowedForUnverifiedScopes.isNotEmpty()) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.USER_UNVERIFIED,
                relevantRestrictedScopes = unallowedForUnverifiedScopes.distinct(),
                requiresManualReview = false,
                explanation = "User is not verified and some requested scopes are not explicitly allowed for unverified users."
            )
        }

        // 11. if we get here, approve the request
        return DecisionResult(
            status = RequestStatus.APPROVED,
            reason = RequestDecisionReason.USER_UNVERIFIED_ALLOWED_SCOPES,
            explanation = "Request automatically approved because all requested scopes are allowed for unverified users."
        )
    }
}
