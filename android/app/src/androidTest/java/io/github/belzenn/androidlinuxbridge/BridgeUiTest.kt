package io.github.belzenn.androidlinuxbridge

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.belzenn.androidlinuxbridge.ui.BridgeApp
import io.github.belzenn.androidlinuxbridge.ui.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BridgeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetAppearance() {
        context.getSharedPreferences("bridge_appearance", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show(setup: androidx.compose.runtime.State<SetupStatus>) {
        compose.setContent {
            BridgeApp(setup.value, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun missingAccessShowsSetupAndUpdatesAfterGrant() {
        val setup = mutableStateOf(SetupStatus())
        show(setup)
        compose.onNodeWithText("Set up your bridge").assertIsDisplayed()
        compose.runOnIdle { setup.value = SetupStatus(true, true, true, true) }
        compose.onNodeWithText("Set up your bridge").assertDoesNotExist()
    }

    @Test fun setupOnlyShowsMissingPermissionsAndRemovesGrantedOnes() {
        val setup = mutableStateOf(SetupStatus(true, false, false, true))
        show(setup)
        compose.onNodeWithText("Notification access").assertDoesNotExist()
        compose.onNodeWithText("Background activity").assertDoesNotExist()
        compose.onNodeWithText("App notifications").assertIsDisplayed()
        compose.onNodeWithText("Local network").assertIsDisplayed()
        compose.runOnIdle { setup.value = setup.value.copy(notifications = true) }
        compose.onNodeWithText("App notifications").assertDoesNotExist()
        compose.onNodeWithText("Local network").assertIsDisplayed()
        compose.runOnIdle { setup.value = setup.value.copy(localNetwork = true) }
        compose.onNodeWithText("Set up your bridge").assertDoesNotExist()
    }

    @Test fun manuallyOpenedSetupClosesWhenEverythingIsEnabled() {
        val setup = mutableStateOf(SetupStatus(true, true, true, false))
        show(setup)
        compose.onNodeWithText("Set up your bridge").assertDoesNotExist()
        compose.onNodeWithText("Finish setup").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.onNodeWithText("Permissions & background").performClick()
        compose.onNodeWithText("Set up your bridge").assertIsDisplayed()
        compose.runOnIdle { setup.value = setup.value.copy(background = true) }
        compose.onNodeWithText("Set up your bridge").assertDoesNotExist()
        compose.onNodeWithText("All permissions enabled").assertIsNotEnabled()
    }

    @Test fun dismissedSetupCanBeReopenedFromSidebar() {
        show(mutableStateOf(SetupStatus()))
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Set up your bridge").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.onNodeWithText("Permissions & background").performClick()
        compose.onNodeWithText("Set up your bridge").assertIsDisplayed()
    }

    @Test fun sidebarNavigatesAndPersistsThemeWithSystemDefault() {
        show(mutableStateOf(SetupStatus(true, true, true, true)))
        compose.onNodeWithText("Set up your bridge").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.onNodeWithContentDescription("Use device theme").assertIsOn()
        compose.onNodeWithContentDescription("Dark theme").performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals("dark", context.getSharedPreferences("bridge_appearance", Context.MODE_PRIVATE).getString("theme", null))
        }
        compose.onNodeWithContentDescription("Use device theme").performClick().assertIsOn()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        compose.onNodeWithText("Devices").assertDoesNotExist()
        compose.onNodeWithText("Logs").performClick()
        compose.onNodeWithText("Connection activity").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Find computers").performClick()
        compose.onNodeWithText("Nearby computers").performScrollTo().assertIsDisplayed()
    }
}
