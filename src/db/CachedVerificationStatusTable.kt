package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.VerificationStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.datetime.timestamp

object CachedVerificationStatusTable : Table("cached_verification_status") {
    val userId = varchar("user_id", 20)

    val verificationStatus = enumerationByName<VerificationStatus>("verification_status", VerificationStatus.entries.maxOf { it.name.length })

    // for faster retrieval
    val verified = bool("verified").default(false)

    val mainAccount = reference("main_account", userId).nullable()

    val lastChecked = timestamp("last_checked")

    override val primaryKey = PrimaryKey(userId)
}