package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object InstallRequestHistoryTable : Table("install_request_history") {
    val id = long("id").autoIncrement()
    val requestId = reference("request_id", InstallRequestTable.requestId)
    val status = varchar("status", 30)
    val actorId = varchar("actor_id", 20).nullable()
    val actorType = varchar("actor_type", 20)
    val action = varchar("action", 30)
    val reason = varchar("reason", 50).nullable()
    val message = varchar("message", 2000).nullable()
    val createdAt = timestamp("created_at")
    val stateId = varchar("state_id", 20).nullable()

    init {
        index(false, requestId)
    }

    override val primaryKey = PrimaryKey(id)
}
