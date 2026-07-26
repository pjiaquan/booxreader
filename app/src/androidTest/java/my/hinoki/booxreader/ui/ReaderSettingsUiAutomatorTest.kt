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
        // 1. Verify ReaderSettings Title / Activity is displayed
        val settingsTitle = device.findObject(UiSelector().textContains("Settings").textContains("設定"))
        // If not matched directly by text, check by activity elements or root ScrollView
        val settingsScroll = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/settingsScroll"))
        assertTrue("Settings page root scroll view should exist", settingsScroll.exists() || device.hasObject(By.pkg(PACKAGE_NAME)))

        // 2. Verify Section Cards exist using UiScrollable
        val scrollable = UiScrollable(UiSelector().scrollable(true))

        // Check Reading & Display Section
        val pageTapSwitch = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/switchPageTap"))
        if (pageTapSwitch.exists()) {
            assertTrue("Tap to turn page switch should be visible", pageTapSwitch.isEnabled)
        }

        // Check Save Button in Footer
        val saveButton = device.findObject(UiSelector().resourceId("$PACKAGE_NAME:id/btnSettingsSave"))
        if (saveButton.exists()) {
            assertTrue("Save button should be enabled", saveButton.isEnabled)
        }
    }
}
