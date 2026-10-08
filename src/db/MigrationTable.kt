package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object MigrationTable : Table("migration") {
    val migration = varchar("migration", 255)
    val appliedAt = timestamp("applied_at")
}