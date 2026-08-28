package my.hinoki.booxreader.ui.auth

import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import my.hinoki.booxreader.R

/**
 * Regression tests for the crash that occurred when clicking the Profile button.
 *
 * Root cause: UserProfileActivity is themed with AppTheme.ActionBar, which extends
 * Theme.MaterialComponents.DayNight (Material 2). However, activity_user_profile.xml uses
 * Material 3 attributes (colorOutlineVariant, colorOnSurfaceVariant) on MaterialCardView
 * strokeColor and icon tints respectively. M2 theme parents do NOT define these attrs, so
 * layout inflation throws Resources$NotFoundException at runtime → crash on profile click.
 *
 * Fix: Added colorOutlineVariant, colorOnSurfaceVariant, colorSurface, and colorOnSurface
 * explicitly to AppTheme.ActionBar in themes.xml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserProfileCrashRegressionTest {

    // ─── 1. Theme attr resolution in AppTheme.ActionBar ───────────────────────

    /** colorOutlineVariant was the primary crash attr — MaterialCardView strokeColor. */
    @Test
    fun appThemeActionBar_resolves_colorOutlineVariant() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme_ActionBar)
        val tv = TypedValue()
        val resolved = themedContext.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOutlineVariant, tv, true
        )
        assert(resolved) {
            "?attr/colorOutlineVariant must resolve in AppTheme.ActionBar — " +
                "used in activity_user_profile.xml on card strokeColor."
        }
        assertNotEquals("colorOutlineVariant must not be TYPE_NULL", TypedValue.TYPE_NULL, tv.type)
    }

    /** colorOnSurfaceVariant — icon tints in activity_user_profile.xml. */
    @Test
    fun appThemeActionBar_resolves_colorOnSurfaceVariant() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme_ActionBar)
        val tv = TypedValue()
        val resolved = themedContext.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true
        )
        assert(resolved) {
            "?attr/colorOnSurfaceVariant must resolve in AppTheme.ActionBar."
        }
    }

    @Test
    fun appThemeActionBar_resolves_colorSurface() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme_ActionBar)
        val tv = TypedValue()
        val resolved = themedContext.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, tv, true
        )
        assert(resolved) { "?attr/colorSurface must resolve in AppTheme.ActionBar." }
    }

    @Test
    fun appThemeActionBar_resolves_colorOnSurface() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme_ActionBar)
        val tv = TypedValue()
        val resolved = themedContext.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOnSurface, tv, true
        )
        assert(resolved) { "?attr/colorOnSurface must resolve in AppTheme.ActionBar." }
    }

    // ─── 2. Required color resources exist ────────────────────────────────────

    @Test
    fun color_outlineVariant_isDefined() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotEquals("@color/outline_variant must exist and be non-zero",
            0, context.getColor(R.color.outline_variant))
    }

    @Test
    fun color_textSecondary_isDefined() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotEquals("@color/text_secondary must exist and be non-zero",
            0, context.getColor(R.color.text_secondary))
    }

    @Test
    fun color_dividerMajor_isDefined() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotEquals("@color/divider_major must exist and be non-zero",
            0, context.getColor(R.color.divider_major))
    }

    @Test
    fun color_primary_isDefined() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotEquals("@color/primary must exist and be non-zero",
            0, context.getColor(R.color.primary))
    }

    @Test
    fun color_error_isDefined() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotEquals("@color/error must exist and be non-zero",
            0, context.getColor(R.color.error))
    }

    // ─── 3. Required drawables exist ──────────────────────────────────────────

    @Test
    fun drawable_bgAvatarFrame_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_avatar_frame)
        assertNotNull("@drawable/bg_avatar_frame must exist", d)
    }

    @Test
    fun drawable_bgIconCircle_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_icon_circle)
        assertNotNull("@drawable/bg_icon_circle must exist", d)
    }

    // ─── 4. Required string resources exist ───────────────────────────────────

    @Test
    fun string_readerSettingsUserProfile_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.reader_settings_user_profile).isNotBlank())
    }

    @Test
    fun string_profileVersionFormat_hasStringPlaceholder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = context.getString(R.string.profile_version_format, "1.2.3")
        assert(s.contains("1.2.3")) {
            "@string/profile_version_format must have a %s placeholder; got: \"$s\""
        }
    }

    @Test
    fun string_profileUsernameRequired_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_username_required).isNotBlank())
    }

    @Test
    fun string_profilePasswordRequired_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_password_required).isNotBlank())
    }

    @Test
    fun string_profilePasswordMismatch_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_password_mismatch).isNotBlank())
    }

    @Test
    fun string_profilePasswordTooShort_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_password_too_short).isNotBlank())
    }

    @Test
    fun string_profileSaveFailed_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_save_failed).isNotBlank())
    }

    @Test
    fun string_profileSaved_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_saved).isNotBlank())
    }

    @Test
    fun string_profileLogout_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_logout).isNotBlank())
    }

    @Test
    fun string_profileGuestName_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_guest_name).isNotBlank())
    }

    @Test
    fun string_profileLoginRequired_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_login_required).isNotBlank())
    }

    @Test
    fun string_profilePasswordChangeFailed_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_password_change_failed).isNotBlank())
    }

    @Test
    fun string_profilePasswordUpdated_exists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assert(context.getString(R.string.profile_password_updated).isNotBlank())
    }
}
