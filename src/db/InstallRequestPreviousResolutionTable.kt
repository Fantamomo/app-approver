package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object InstallRequestPreviousResolutionTable : Table(
    "install_request_previous_resolution"
) {

    val requestId = reference("request_id", InstallRequestTable.requestId)

    val status = varchar("status", 30)

    val resolvedByType = varchar("resolved_by_type", 30).nullable()
    val resolvedById = varchar("resolved_by_id", 20).nullable()

    val resolvedAt = timestamp("resolved_at").nullable()

    // scopes in tokenType:name format separated by comma
    val rawScopes = text("raw_scopes").nullable()

    override val primaryKey = PrimaryKey(requestId)
}