package utils

import io.ktor.server.request.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object SlackRequestVerifier {

    private const val VERSION = "v0"
    private const val MAX_REQUEST_AGE_SECONDS = 60L * 5

    @Suppress("UastIncorrectHttpHeaderInspection")
    fun verify(
        call: ApplicationRequest,
        rawBody: String,
        signingSecret: String
    ): Boolean {
        val timestamp = call.headers["X-Slack-Request-Timestamp"] ?: return false
        val slackSignature = call.headers["X-Slack-Signature"] ?: return false
        val timestampLong = timestamp.toLongOrNull() ?: return false

        val currentTimestamp = System.currentTimeMillis() / 1000
        if (abs(currentTimestamp - timestampLong) > MAX_REQUEST_AGE_SECONDS) {
            return false
        }

        val baseString = "$VERSION:$timestamp:$rawBody"
        val expectedSignature = hmacSha256(signingSecret, baseString)

        return MessageDigest.isEqual(
            expectedSignature.toByteArray(Charsets.UTF_8),
            slackSignature.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hmacSha256(
        secret: String,
        value: String
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

        mac.init(secretKey)

        val hash = mac.doFinal(value.toByteArray(Charsets.UTF_8))

        return "$VERSION=${hash.toHexString()}"
    }
}