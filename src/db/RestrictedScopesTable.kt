package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.RestrictionLevel
import com.fantamomo.slack.approver.model.ScopeType
import org.jetbrains.exposed.v1.core.Table

object RestrictedScopesTable : Table("restricted_scopes") {
    val scope = varchar("scope", 40)
        .comment("The scope which is restricted")
    val restricted = enumerationByName<RestrictionLevel>(
        "restricted",
        RestrictionLevel.entries.maxOf { it.name.length }
    ).comment("How restricted the scope is")

    val scopeType = enumerationByName<ScopeType>("scope_type", ScopeType.entries.maxOf { it.name.length })
        .comment("The type of scope")

    val review = bool("review").comment("Whether the scope requires review or is directly declined")
}