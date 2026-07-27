package my.hinoki.booxreader.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import my.hinoki.booxreader.ui.reader.ReaderSettingsActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSettingsUiAutomatorTest {

    private lateinit var device: UiDevice
    private val PACKAGE_NAME = "my.hinoki.booxreader"
    private val LAUNCH_TIMEOUT = 5000L

    @Before
    fun startReaderSettingsActivity() {
        // Initialize UiDevice instance
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Start from home screen
        device.pressHome()

        // Wait for launcher
        val launcherPackage: String = device.launcherPackageName
        assertNotNull(launcherPackage)
        device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), LAUNCH_TIMEOUT)

        // Launch ReaderSettingsActivity
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ReaderSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)

        // Wait for the app to appear
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), LAUNCH_TIMEOUT)
    }

    @Test
    fun testSettingsPageLayoutWithUiAutomator() {
        // 1. Verify the settings screen is visible — check for the root package.
        //    UiSelector.textContains() can only match ONE text criterion per selector;
        //    chaining two .textContains() calls replaces the first with the second.
        //    Use By.pkg() to confirm the activity is in the foreground instead.
        assertTrue(
            "Settings activity should be in the foreground",
            device.hasObject(By.pkg(PACKAGE_NAME))
        )

        // 2. Verify the root ScrollView exists (the <include> tag assigns resource-id settingsContent)
        var settingsScroll = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/settingsContent"))
        if (!settingsScroll.exists()) {
            settingsScroll = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/settingsScroll"))
        }
        assertTrue("Settings page root scroll view should exist", settingsScroll.exists())

        // 3. Verify the Save button is present and enabled
        val saveButton = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/btnSettingsSave"))
        assertTrue("Save button should exist", saveButton.exists())
        assertTrue("Save button should be enabled", saveButton.isEnabled)

        // 4. Verify the Cancel button is present and enabled
        val cancelButton = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/btnSettingsCancel"))
        assertTrue("Cancel button should exist", cancelButton.exists())
        assertTrue("Cancel button should be enabled", cancelButton.isEnabled)

        // 5. Scroll down and verify the tap-to-turn switch if present
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        val pageTapSwitch = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/switchPageTap"))
        if (!pageTapSwitch.exists()) {
            scrollable.scrollForward()
        }
        val pageTapSwitchAfterScroll = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/switchPageTap"))
        if (pageTapSwitchAfterScroll.exists()) {
            assertTrue("Tap to turn page switch should be enabled", pageTapSwitchAfterScroll.isEnabled)
        }
    }
}
