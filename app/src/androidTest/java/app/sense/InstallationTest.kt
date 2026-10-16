package app.sense

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import app.sense.data.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InstallationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshInstallRegistersRestoresAndSignsInWithItsPrivateKey() {
        assertTrue("A live HTTPS service must be compiled into the APK", BuildConfig.BACKEND_URL.startsWith("https://"))
        ui.waitUntil(30000) { ui.onAllNodesWithText("Your username").fetchSemanticsNodes().isNotEmpty() }
        val username = "phone-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        ui.onNodeWithText("Your username").performTextInput(username)
        ui.onNodeWithText("Create my username").performScrollTo().performClick()
        ui.waitUntil(60000) { ui.onAllNodesWithText("Keep this private").fetchSemanticsNodes().isNotEmpty() }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SenseApplication
        val identity: DeviceIdentity = runBlocking(Dispatchers.IO) {
            requireNotNull(app.graph.store.vault.identity(app.graph.backend.origin))
        }
        assertEquals(username, identity.username)
        assertTrue(identity.recoveryKey.length >= 32)
        ui.onNodeWithText("Done", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Stay in tune.").assertExists()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(30000) { ui.onAllNodesWithText("Stay in tune.").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("You", useUnmergedTree = true).performClick()
        ui.onNodeWithText("@" + username).assertExists()
        ui.onNodeWithText("Sign out").performScrollTo().performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() }
        val vm = ui.runOnIdle { ViewModelProvider(ui.activity)[SenseViewModel::class.java] }
        ui.onNodeWithText("Your username").performTextReplacement(username)
        ui.onNodeWithText("Recovery key").performTextInput(identity.recoveryKey)
        ui.onNodeWithText("Sign in").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(60000) {
            vm.error.value != null || app.graph.backend.authenticated
        }
        assertNull("Sign in error (online=${app.graph.capabilities.state.value.online}, busy=${vm.busy.value}, service=${app.graph.backend.status.value})", vm.error.value)
        // Sign-in restores the selected You tab rather than navigating to Home.
        ui.onNodeWithText("@" + username).assertExists()
        ui.onNodeWithText("Home", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Stay in tune.").assertExists()
    }
}
