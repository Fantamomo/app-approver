package com.fantamomo.slack.approver.utils

import kotlin.random.Random

fun String.substringBeforeCount(delimiter: String, count: Int): String =
    split(delimiter).take(count + 1).joinToString(delimiter)

private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

fun randomString(length: Int): String = (1..length)
    .map { CHARS[Random.nextInt(CHARS.length)] }
    .joinToString("")