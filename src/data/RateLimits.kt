package com.fantamomo.slack.approver.data

import com.fantamomo.slack.approver.App
import com.fantamomo.slack.approver.utils.RateLimitAvoider

object RateLimits {
    val ADMIN_APPS_REQUESTS_LIST = create(20)
    val ADMIN_APPS_REQUESTS_CANCEL = create(20)
    val ADMIN_APPS_RESTRICT = create(20)
    val ADMIN_APPS_APPROVE = create(20)
    val ADMIN_APPS_CLEAR_RESOLUTION = create(20)
    val CHAT_POST_MESSAGE = create(60)
    val CHAT_POST_EPHEMERAL = create(100)
    val CHAT_UPDATE = create(50)
    val CONVERSATION_OPEN = create(50)
    val VIEWS_OPEN = create(100)
    val VIEWS_PUBLISH = create(100)

    val AUTH_CHECK = create(60)

    private fun create(perMinute: Int) = RateLimitAvoider(perMinute, App.scope)
}