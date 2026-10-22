package com.fantamomo.slack.approver.model

enum class SlackResolution(val value: String) {
    PENDING("pending"),
    RESTRICTED("restricted"),
    APPROVED("approved"),
    CANCELLED("cancelled"),
}