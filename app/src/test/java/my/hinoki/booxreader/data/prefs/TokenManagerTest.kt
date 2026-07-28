package my.hinoki.booxreader.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenManagerTest {

    private lateinit var context: Context
    private lateinit var tokenManager: TokenManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear auth_prefs before test
        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        tokenManager = TokenManager(context)
    }

    @Test
    fun `saveRememberMe with true stores email and enables preference`() {
        assertFalse(tokenManager.isRememberMeEnabled())
        assertNull(tokenManager.getSavedEmail())

        tokenManager.saveRememberMe(true, "user@example.com")

        assertTrue(tokenManager.isRememberMeEnabled())
        assertEquals("user@example.com", tokenManager.getSavedEmail())
    }

    @Test
    fun `saveRememberMe with false removes email and disables preference`() {
        tokenManager.saveRememberMe(true, "user@example.com")
        assertTrue(tokenManager.isRememberMeEnabled())

        tokenManager.saveRememberMe(false, "")

        assertFalse(tokenManager.isRememberMeEnabled())
        assertNull(tokenManager.getSavedEmail())
    }

    @Test
    fun `saveGuestMode updates guest mode state`() {
        assertFalse(tokenManager.isGuestMode())

        tokenManager.saveGuestMode(true)
        assertTrue(tokenManager.isGuestMode())

        tokenManager.saveGuestMode(false)
        assertFalse(tokenManager.isGuestMode())
    }

    @Test
    fun `saveAccessToken resets guest mode to false`() {
        tokenManager.saveGuestMode(true)
        assertTrue(tokenManager.isGuestMode())

        tokenManager.saveAccessToken("dummy_token")
        assertFalse(tokenManager.isGuestMode())
        assertEquals("dummy_token", tokenManager.getAccessToken())
    }
}
