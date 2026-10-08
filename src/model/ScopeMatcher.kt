package com.fantamomo.slack.approver.model

sealed interface ScopeMatcher {
    val priority: Int

    fun match(scope: String): Boolean

    fun hasPriorityOver(other: ScopeMatcher): Boolean = priority < other.priority

    data class DirectScopeMatcher(
        val scope: String
    ) : ScopeMatcher {
        override val priority = 1

        override fun match(scope: String): Boolean =
            this.scope == scope
    }

    data class PrefixScopeMatcher(
        val prefix: String
    ) : ScopeMatcher {
        override val priority = 2
        override fun match(scope: String): Boolean =
            scope.startsWith(prefix)
    }

    data class WildcardScopeMatcher(
        val pattern: String
    ) : ScopeMatcher {
        override val priority = 3

        private val regex: Regex = buildRegex(pattern)

        override fun match(scope: String) = pattern == "*" || regex.matches(scope)

        private fun buildRegex(pattern: String): Regex {
            val regex = buildString {
                append("^")

                pattern.forEach { char ->
                    when (char) {
                        '*' -> append(".*")
                        else -> append(Regex.escape(char.toString()))
                    }
                }

                append("$")
            }

            return Regex(regex)
        }
    }

    companion object {
        val ALL_SCOPES = WildcardScopeMatcher("*")

        fun fromString(scope: String): ScopeMatcher {
            val starIndex = scope.indexOf('*')
            if (starIndex != -1) {
                if (scope.length == 1) return ALL_SCOPES
                if (scope.length == starIndex + 1 && scope[starIndex - 1] == ':') return PrefixScopeMatcher(scope.substring(0, starIndex))
                return WildcardScopeMatcher(scope)
            }
            return DirectScopeMatcher(scope)
        }
    }
}