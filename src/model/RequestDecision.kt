package com.fantamomo.slack.approver.model

enum class RequestDecision {
    APPROVED,
    DENIED,
    RESTRICTED,
    WITHDRAWN,
    UNDONE;

    fun toRequestStatus(): RequestStatus {
        return when (this) {
            APPROVED -> RequestStatus.APPROVED
            DENIED, RESTRICTED, UNDONE -> RequestStatus.DENIED
            WITHDRAWN -> RequestStatus.WITHDRAWN
        }
    }
}