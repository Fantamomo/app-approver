package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object AppRestrictionTable : Table("app_restriction") {
    val appId = varchar("app_id", 20)
    val appName = varchar("app_name", 100)
    val restrictedBy = varchar("restricted_by", 20)
    val restrictedAt = timestamp("restricted_at")
    val reason = varchar("reason", 2000).nullable()
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(appId)
}
