package com.fantamomo.slack.approver.model

class RestrictedScope(
    val pattern: String,
    val level: RestrictionLevel,
    val scopeType: ScopeType,
    val review: Boolean,
) {
    val scope = ScopeMatcher.fromString(pattern)
}