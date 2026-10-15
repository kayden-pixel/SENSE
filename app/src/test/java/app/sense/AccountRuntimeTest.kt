package app.sense

import app.sense.core.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AccountRuntimeTest {
    @Test
    fun freshAndExpiredInstallationsRequireAuthentication() {
        assertEquals(AccountRules.Entry.LOADING, AccountRules.entry(false, false, false, false))
        assertEquals(AccountRules.Entry.SIGN_IN, AccountRules.entry(true, false, false, false))
        assertEquals(AccountRules.Entry.MAIN, AccountRules.entry(true, true, false, false))
        assertEquals(AccountRules.Entry.SIGN_IN, AccountRules.entry(true, false, false, false))
    }

    @Test
    fun aDeveloperPreferenceCannotBypassProductionSignIn() {
        assertEquals(AccountRules.Entry.SIGN_IN, AccountRules.entry(true, false, false, true))
        assertEquals(AccountRules.Entry.MAIN, AccountRules.entry(true, false, true, true))
    }

    @Test
    fun usernameNormalizationDoesNotAcceptImpersonationOrPaths() {
        assertEquals("kayden-pixel", AccountRules.username(" KAYDEN-PIXEL "))
        for (invalid in
            listOf(
                "a",
                "@someone",
                "some/one",
                "someone?token=secret",
                "some one",
                "a".repeat(25),
            )) assertNull(AccountRules.username(invalid))
    }

    @Test
    fun registrationConflictAndAuthenticationFailureAreDistinct() {
        assertEquals(FailureKind.USERNAME_TAKEN, UserFailures.http(409, "/auth/register"))
        assertEquals(FailureKind.AUTHENTICATION, UserFailures.http(401, "/auth/login"))
        assertNotEquals(FailureKind.USERNAME_TAKEN, UserFailures.http(409, "/sessions/id/schedule"))
    }

    @Test
    fun backendAndMediaOutagesHaveSeparateRecoveryMessages() {
        val offline = ServiceFailure(UserFailures.http(503, "/me"))
        val voice = ServiceFailure(UserFailures.http(503, "/sessions/id/token"))
        assertTrue(UserFailures.message(offline).contains("SENSE"))
        assertTrue(UserFailures.message(voice).contains("Whisper"))
        assertTrue(UserFailures.message(offline, network = false).contains("Wi-Fi"))
    }

    @Test
    fun exceptionsCannotExposeUrlsTokensOrSdkDetailsToUsers() {
        val technical =
            "wss://private.example/rtc?access_token=private-secret invalid API key stacktrace"
        assertFalse(UserFailures.message(IOException(technical)).contains("private-secret"))
        assertFalse(UserFailures.message(IllegalStateException(technical)).contains("stacktrace"))
        assertFalse(
            UserFailures.message(IOException(technical), voice = true).contains("invalid API key")
        )
    }
}
