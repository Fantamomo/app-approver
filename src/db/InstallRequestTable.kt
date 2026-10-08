package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.AppDevelopmentType
import com.fantamomo.slack.approver.model.RequestDecision
import com.fantamomo.slack.approver.model.RequestDecisionReason
import com.fantamomo.slack.approver.model.RequestStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object InstallRequestTable : Table("install_request") {

    val requestId = varchar("request_id", 20)
    val appId = varchar("app_id", 20)

    val appName = varchar("app_name", 100)
    val appDescription = varchar("app_description", 1000)
    val appUrl = varchar("app_url", 255).nullable()

    val developerType = enumerationByName<AppDevelopmentType>(
        "developer_type",
        AppDevelopmentType.entries.maxOf { it.name.length }
    )

    val userId = varchar("user_id", 20)

    val teamId = varchar("team_id", 20).nullable()
    val enterpriseId = varchar("enterprise_id", 20).nullable()

    val message = varchar("message", 1000).nullable()
    val isUserAppCollaborator = bool("is_user_app_collaborator")

    val requestedAt = long("requested_at")
    val userVerified = bool("user_verified")

    val automaticDecision = enumerationByName<RequestStatus>(
        "automatic_decision",
        RequestStatus.entries.maxOf { it.name.length }
    )

    val automaticDecisionReason = enumerationByName<RequestDecisionReason>(
        "automatic_decision_reason",
        RequestDecisionReason.entries.maxOf { it.name.length }
    ).nullable()

    val status = enumerationByName<RequestStatus>(
        "status",
        RequestStatus.entries.maxOf { it.name.length }
    )

    val resolvedBy = varchar("resolved_by", 20).nullable()
    val resolvedAt = timestamp("resolved_at").nullable()

    val resolutionMessage = varchar("resolution_message", 2000).nullable()

    val resolution = enumerationByName<RequestDecision>(
        "resolution",
        RequestDecision.entries.maxOf { it.name.length }
    ).nullable()

    val reviewMessageTs = varchar("review_message_ts", 30).nullable()

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index(false, appId)
        index(false, userId)
        index(false, status)
    }

    override val primaryKey = PrimaryKey(requestId)
}