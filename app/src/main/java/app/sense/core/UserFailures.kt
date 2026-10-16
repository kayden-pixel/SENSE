package app.sense.core

import java.io.IOException
import java.net.SocketTimeoutException

enum class FailureKind {
    NOT_CONFIGURED,
    NETWORK,
    OFFLINE,
    USERNAME_TAKEN,
    AUTHENTICATION,
    VOICE_UNAVAILABLE,
    ACCESS,
    RATE_LIMIT,
    INVALID_INPUT,
    UNKNOWN,
}

class ServiceFailure(val kind: FailureKind) : IOException()

object UserFailures {
    fun http(status: Int, route: String): FailureKind =
        when {
            status == 409 && route == "/auth/register" -> FailureKind.USERNAME_TAKEN
            status == 401 -> FailureKind.AUTHENTICATION
            status == 403 || status == 404 -> FailureKind.ACCESS
            status == 429 -> FailureKind.RATE_LIMIT
            status >= 500 && route.endsWith("/token") -> FailureKind.VOICE_UNAVAILABLE
            status >= 500 -> FailureKind.OFFLINE
            status in 400..499 -> FailureKind.INVALID_INPUT
            else -> FailureKind.UNKNOWN
        }

    fun message(error: Throwable, network: Boolean = true, voice: Boolean = false): String {
        val kind =
            when {
                !network -> FailureKind.NETWORK
                error is ServiceFailure -> error.kind
                voice -> FailureKind.VOICE_UNAVAILABLE
                error is SocketTimeoutException -> FailureKind.OFFLINE
                error is IOException -> FailureKind.OFFLINE
                else -> FailureKind.UNKNOWN
            }
        return when (kind) {
            FailureKind.NOT_CONFIGURED ->
                "This build has no SENSE service configured. Install the connected release when it is available."
            FailureKind.NETWORK -> "No connection. Check Wi-Fi or mobile data and try again."
            FailureKind.OFFLINE -> "SENSE is temporarily unavailable. Please try again shortly."
            FailureKind.USERNAME_TAKEN ->
                "That username is already taken. Choose another, or sign in if it is yours."
            FailureKind.AUTHENTICATION ->
                "Sign-in failed or your session expired. Check your username and recovery key."
            FailureKind.VOICE_UNAVAILABLE ->
                "Whisper is temporarily unavailable. Please try again shortly."
            FailureKind.ACCESS -> "This person or session is no longer available to you."
            FailureKind.RATE_LIMIT -> "Please wait a moment before trying again."
            FailureKind.INVALID_INPUT -> "Check the details you entered and try again."
            FailureKind.UNKNOWN -> "Unable to complete that action. Please try again."
        }
    }
}

object AccountRules {
    fun username(value: String): String? =
        value.trim().lowercase(java.util.Locale.ROOT).takeIf {
            Regex("[a-z0-9][a-z0-9-]{2,23}").matches(it)
        }

    enum class Entry {
        LOADING,
        SIGN_IN,
        MAIN,
    }

    fun entry(
        initialized: Boolean,
        authenticated: Boolean,
        debugBuild: Boolean,
        offlinePreview: Boolean,
    ): Entry =
        when {
            !initialized -> Entry.LOADING
            authenticated -> Entry.MAIN
            debugBuild && offlinePreview -> Entry.MAIN
            else -> Entry.SIGN_IN
        }
}
