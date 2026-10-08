package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object ApproveTeamMemberTable : Table("approve_team_member") {
    val userId = varchar("user_id", 20).comment("The user id of the member")
    val since = timestamp("since").comment("The timestamp when the member was added")
    val deleted = bool("deleted").default(false).comment("Whether the member has been deleted")
}