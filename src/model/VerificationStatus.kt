package com.fantamomo.slack.approver.model

enum class VerificationStatus(val value: String) {
    NEEDS_SUBMISSION("needs_submission"),
    PENDING("pending"),
    VERIFIED_ELIGIBLE("verified_eligible"),
    VERIFIED_BUT_OVER_18("verified_but_over_18"),
    REJECTED("rejected"),
    NOT_FOUND("not_found"),
    // special case for when a user is not verified is considered ok
    OVERRIDDEN("overridden"),
    // not an auth scope, used if the account is verified alt account from https://github.com/KavyanshKhaitan2/slack-linkbot/
    // also this is a special case, the main account could be verified but also not, so only alt accounts get in the database if they main account is verified
    ALT("alt");

    val verified: Boolean
        get() = this == VERIFIED_ELIGIBLE || this == VERIFIED_BUT_OVER_18 || this == OVERRIDDEN || this == ALT
}