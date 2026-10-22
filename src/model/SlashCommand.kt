package com.fantamomo.slack.approver.model

data class SlashCommand(
    val name: String,
    val description: String,
    val usageHint: String
)