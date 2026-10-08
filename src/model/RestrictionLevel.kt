package com.fantamomo.slack.approver.model

enum class RestrictionLevel {
    // the scope is allowed for all verified users (the normal value)
    ALLOWED,
    // the scope is restricted for all users (excluding admins)
    RESTRICTED,
    // the scope is allowed for unverified users
    ALLOWED_FOR_UNVERIFIED
}