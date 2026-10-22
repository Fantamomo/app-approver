package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table

object AppCommandsTable : Table("app_commands") {
    val command = varchar("command", 32) // slacks max length is 32 (with the slash)
    val app = varchar("app", 20)

    // only ONE "command" is active at a time
    val active = bool("active")


    override val primaryKey = PrimaryKey(command, app)
}