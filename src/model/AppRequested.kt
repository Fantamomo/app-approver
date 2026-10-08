package com.fantamomo.slack.approver.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppRequested(
    val id: String,
    val app: SlackApp,
    @SerialName("previous_resolution")
    val previousResolution: PreviousResolution? = null,
    val user: SlackUser,
    val team: SlackTeam? = null,
    val enterprise: SlackEnterprise? = null,
    val scopes: List<SlackScope>,
    val message: String? = null,
    @SerialName("is_user_app_collaborator")
    val isUserAppCollaborator: Boolean,
    @SerialName("date_created")
    val dateCreated: Long,
    val domains: List<String> = emptyList(),
    @SerialName("manager_app_id")
    val managerAppId: String? = null,
    @SerialName("mcp_domains")
    val mcpDomains: List<String> = emptyList(),
    val resolution: Resolution? = null,
)

@Serializable
data class SlackApp(
    val id: String,
    val name: String,
    val description: String,
    @SerialName("help_url")
    val helpUrl: String,
    @SerialName("privacy_policy_url")
    val privacyPolicyUrl: String,
    @SerialName("app_homepage_url")
    val appHomepageUrl: String,
    @SerialName("app_directory_url")
    val appDirectoryUrl: String,
    @SerialName("is_granular_bot_app")
    val isGranularBotApp: Boolean? = null,
    @SerialName("is_app_directory_approved")
    val isAppDirectoryApproved: Boolean,
    @SerialName("is_internal")
    val isInternal: Boolean,
    @SerialName("developer_type")
    val developerType: String,
    @SerialName("socket_mode_enabled")
    val socketModeEnabled: Boolean,
    @SerialName("icons")
    val icons: SlackAppIcons,
    @SerialName("additional_info")
    val additionalInfo: String,
    @SerialName("date_create")
    val dateCreate: Long? = null,
    @SerialName("is_mcp_enabled")
    val isMcpEnabled: Boolean? = null,
)

@Serializable
data class SlackAppIcons(
    @SerialName("image_32")
    val image32: String,
    @SerialName("image_36")
    val image36: String,
    @SerialName("image_48")
    val image48: String,
    @SerialName("image_64")
    val image64: String,
    @SerialName("image_72")
    val image72: String,
    @SerialName("image_96")
    val image96: String,
    @SerialName("image_128")
    val image128: String,
    @SerialName("image_192")
    val image192: String,
    @SerialName("image_512")
    val image512: String,
    @SerialName("image_1024")
    val image1024: String,
) {
    companion object {
        val EMPTY = SlackAppIcons("", "", "", "", "", "", "", "", "", "")
    }
}

@Serializable
data class SlackUser(
    val id: String,
    val name: String,
    val email: String? = null,
)

@Serializable
data class SlackTeam(
    val id: String,
    val name: String,
    val domain: String,
)

@Serializable
data class SlackEnterprise(
    val id: String,
    val name: String,
    val domain: String,
)

@Serializable
data class SlackScope(
    val name: String,
    val description: String,
    @SerialName("is_sensitive")
    val isSensitive: Boolean,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("is_optional")
    val isOptional: Boolean,
    @SerialName("is_approved")
    val isApproved: Boolean,
)

@Serializable
data class PreviousResolution(
    val status: String,
    val scopes: List<SlackScope>,
    @SerialName("last_resolved_by")
    val lastResolvedBy: ResolvedBy,
    val domains: List<String> = emptyList(),
    @SerialName("mcp_domains")
    val mcpDomains: List<String> = emptyList(),
)

@Serializable
data class ResolvedBy(
    @SerialName("actor_type")
    val actorType: String,
    @SerialName("actor_id")
    val actorId: String,
)

@Serializable
data class Resolution(
    val status: String,
)