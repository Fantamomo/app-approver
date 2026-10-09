package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Constants.BULLET
import com.fantamomo.slack.approver.model.RestrictionLevel
import com.fantamomo.slack.approver.model.ScopeType
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

object CommandManager {
    private val logger = LoggerFactory.getLogger(CommandManager::class.java)

    private val USER_ID = Regex("^[UW][A-Z0-9]{2,}$")
    private val USER_MENTION = Regex("^<@([UW][A-Z0-9]+)(?:\\|[^>]*)?>$")
    private val SCOPE = Regex("^[a-zA-Z0-9_.:*-]+$")
    private const val MAX_SCOPE_LENGTH = 40

    private data class ScopeArgs(val scope: String, val type: ScopeType, val review: Boolean)

    suspend fun execute(responseUrl: Url, args: List<String>) {
        try {
            val rest = args.drop(1)
            when (args.firstOrNull()?.lowercase()) {
                null, "help" -> SlackWorkflowService.sendHelpMessage(responseUrl)
                "team" -> team(responseUrl, rest)
                "scopes" -> scopes(responseUrl, rest)
                else -> SlackWorkflowService.sendUnknownArgMessage(responseUrl)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error while executing command $args", e)
            reply(responseUrl, ":x: An internal error occurred while executing the command.")
        }
    }

    private suspend fun team(url: Url, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null, "list" -> {
                val members = InstallRequestRepository.getTeamMembers()
                if (members.isEmpty()) {
                    reply(url, ":busts_in_silhouette: There are no team members yet.")
                } else {
                    reply(url, "*Team members (${members.size}):*\n" + members.joinToString("\n") { "$BULLET <@$it>" })
                }
            }

            "add" -> {
                val member = parseUser(args.getOrNull(1)) ?: return reply(url, USER_USAGE)
                if (InstallRequestRepository.addTeamMember(member)) {
                    reply(url, ":white_check_mark: <@$member> was added to the team.")
                } else {
                    reply(url, ":information_source: <@$member> is already a team member.")
                }
            }

            "remove" -> {
                val member = parseUser(args.getOrNull(1)) ?: return reply(url, USER_USAGE)
                if (InstallRequestRepository.removeTeamMember(member)) {
                    reply(url, ":white_check_mark: <@$member> was removed from the team.")
                } else {
                    reply(url, ":information_source: <@$member> is not a team member.")
                }
            }

            else -> SlackWorkflowService.sendUnknownArgMessage(url)
        }
    }

    private const val USER_USAGE =
        ":warning: Please provide a user, either as a mention (`@user`) or as a user ID (`U123ABC`)."

    private fun parseUser(arg: String?): String? {
        if (arg == null) return null
        USER_MENTION.matchEntire(arg)?.let { return it.groupValues[1] }
        return arg.takeIf { USER_ID.matches(it) }
    }

    private suspend fun scopes(url: Url, args: List<String>) {
        val rest = args.drop(1)
        when (args.firstOrNull()?.lowercase()) {
            null, "list" -> listScopes(url)
            "allow" -> setScope(url, rest, RestrictionLevel.ALLOWED, allowReview = false)
            "allow-unverified" -> setScope(url, rest, RestrictionLevel.ALLOWED_FOR_UNVERIFIED, allowReview = false)
            "restrict" -> setScope(url, rest, RestrictionLevel.RESTRICTED, allowReview = true)
            "reset" -> resetScope(url, rest)
            else -> SlackWorkflowService.sendUnknownArgMessage(url)
        }
    }

    private suspend fun listScopes(url: Url) {
        val scopes = InstallRequestRepository.getRestrictedScopes()
        if (scopes.isEmpty()) {
            reply(url, ":information_source: No scope configuration exists yet.")
            return
        }
        val text = buildString {
            for (level in RestrictionLevel.entries) {
                val group = scopes.filter { it.level == level }.sortedBy { it.pattern }
                if (group.isEmpty()) continue
                append(
                    when (level) {
                        RestrictionLevel.RESTRICTED -> ":no_entry_sign: *Restricted*"
                        RestrictionLevel.ALLOWED_FOR_UNVERIFIED -> ":unlock: *Allowed for unverified users*"
                        RestrictionLevel.ALLOWED -> ":white_check_mark: *Allowed*"
                    }
                )
                append('\n')
                for (s in group) {
                    append("$BULLET `${s.pattern}` [${s.scopeType}]")
                    if (level == RestrictionLevel.RESTRICTED) append(if (s.review) " – review" else " – declined")
                    append('\n')
                }
                append('\n')
            }
        }
        reply(url, text.trim())
    }

    private suspend fun setScope(url: Url, args: List<String>, level: RestrictionLevel, allowReview: Boolean) {
        val parsed = parseScopeArgs(args, allowReview) ?: return reply(url, SCOPE_USAGE)
        InstallRequestRepository.setScope(parsed.scope, level, parsed.type, parsed.review)
        val what = when (level) {
            RestrictionLevel.ALLOWED -> "allowed"
            RestrictionLevel.ALLOWED_FOR_UNVERIFIED -> "allowed for unverified users"
            RestrictionLevel.RESTRICTED -> if (parsed.review) "restricted (sent to review)" else "restricted (declined)"
        }
        reply(url, ":white_check_mark: `${parsed.scope}` [${parsed.type}] is now $what.")
    }

    private suspend fun resetScope(url: Url, args: List<String>) {
        val parsed = parseScopeArgs(args, allowReview = false) ?: return reply(url, SCOPE_USAGE)
        val removed = InstallRequestRepository.resetScope(parsed.scope, parsed.type)
        if (removed > 0) {
            reply(url, ":white_check_mark: Configuration for `${parsed.scope}` [${parsed.type}] was reset.")
        } else {
            reply(url, ":information_source: No configuration found for `${parsed.scope}` [${parsed.type}].")
        }
    }

    private const val SCOPE_USAGE =
        ":warning: Invalid arguments. Use `help` to see the correct usage."

    private fun parseScopeArgs(args: List<String>, allowReview: Boolean): ScopeArgs? {
        val scope = args.firstOrNull() ?: return null
        if (scope.length > MAX_SCOPE_LENGTH || !SCOPE.matches(scope)) return null

        var type: ScopeType? = null
        var review: Boolean? = null
        for (arg in args.drop(1)) {
            val t = ScopeType.entries.find { it.name.equals(arg, ignoreCase = true) }
            val r = arg.lowercase().toBooleanStrictOrNull()
            when {
                t != null && type == null -> type = t
                r != null && allowReview && review == null -> review = r
                else -> return null
            }
        }
        return ScopeArgs(scope, type ?: ScopeType.BOTH, review ?: false)
    }

    private suspend fun reply(url: Url, text: String) {
        SlackManager.sendEphemeral(url) {
            section {
                markdownText(text.take(3000))
            }
        }
    }
}