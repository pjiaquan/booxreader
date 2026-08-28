package my.hinoki.booxreader.ui.reader

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import my.hinoki.booxreader.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies that the ReaderSettingsActivity layout inflates without crashing.
 *
 * This was added to catch the Resources$NotFoundException that occurred when
 * MaterialCardView tried to resolve ?android:attr/listDivider (a drawable)
 * via strokeColor (which expects a ColorStateList). Material 1.12.0 enforces
 * this type check strictly, causing InflateException on all API levels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderSettingsLayoutInflationTest {

    /**
     * Regression test: ReaderSettingsActivity should create and inflate its
     * layout (activity_reader_settings -> dialog_reader_settings) without
     * throwing InflateException.
     */
    @Test
    fun readerSettingsActivityLayout_inflatesWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = ContextThemeWrapper(context, R.style.AppTheme_ReaderSettings)

        val inflater = LayoutInflater.from(themedContext)
        val rootView = inflater.inflate(R.layout.activity_reader_settings, null) as ViewGroup
        assertNotNull(
            "activity_reader_settings root view should be inflated",
            rootView.findViewById<View>(R.id.readerSettingsRoot)
        )

        val scrollView = rootView.findViewById<View>(R.id.settingsContent)
        assertNotNull(
            "dialog_reader_settings scroll view (settingsContent) should be inflated",
            scrollView
        )
    }


    /**
     * Verifies that dialog_reader_settings.xml can be inflated standalone
     * under AppTheme.ReaderSettings theme.
     */
    @Test
    fun dialogReaderSettingsLayout_inflatesWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = ContextThemeWrapper(context, R.style.AppTheme_ReaderSettings)

        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.dialog_reader_settings, null)
        assertNotNull("dialog_reader_settings should inflate", view)
    }

    /**
     * Verify the Modernist redesign: the settings layout must no longer use
     * rounded MaterialCardView containers (design spec: 0dp corner radius,
     * flat surfaces with 1dp/2dp architectural rules). Any remaining card
     * would violate the handoff spec and must be caught here.
     */
    @Test
    fun modernistLayout_isCardFree() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = ContextThemeWrapper(context, R.style.AppTheme_ReaderSettings)

        val inflater = LayoutInflater.from(themedContext)
        val rootView = inflater.inflate(R.layout.activity_reader_settings, null) as ViewGroup
        assertNotNull("Root view must exist", rootView)

        val cardViews = mutableListOf<MaterialCardView>()
        collectViews(rootView, MaterialCardView::class.java, cardViews)

        assertTrue(
            "Modernist settings redesign must not contain MaterialCardView " +
                "(flat surfaces, 0dp radius per design handoff)",
            cardViews.isEmpty()
        )

        // Core Modernist controls must still inflate: square toggle + slider
        assertNotNull(
            "Square toggle (Widget.App.Switch.Modernist) should inflate",
            rootView.findViewById<View>(R.id.switchPageTap)
        )
        assertNotNull(
            "Font size slider should inflate",
            rootView.findViewById<View>(R.id.seekBarTextSize)
        )
    }

    /**
     * Static analysis: scan all layout XML files to ensure no strokeColor
     * references ?android:attr/listDivider (a drawable, not a color).
     * This prevents future regressions without needing a running Activity.
     */
    @Test
    fun layoutXml_strokeColor_doesNotReferenceListDivider() {
        val layoutDir = File("src/main/res/layout")
        if (!layoutDir.exists()) {
            return
        }

        val badPattern = Regex("""strokeColor\s*=\s*"\?android:attr/listDivider"""")
        val violations = mutableListOf<String>()

        layoutDir.listFiles { f -> f.extension == "xml" }?.forEach { xmlFile ->
            xmlFile.readLines().forEachIndexed { lineIndex, line ->
                if (badPattern.containsMatchIn(line)) {
                    violations.add("${xmlFile.name}:${lineIndex + 1}: $line")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Found strokeColor referencing ?android:attr/listDivider " +
                    "(a drawable, not a ColorStateList). Use ?attr/colorOutlineVariant " +
                    "or a concrete @color instead:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    /** Recursively collect all views of a given type from a ViewGroup hierarchy. */
    private fun <T : View> collectViews(
        parent: ViewGroup,
        type: Class<T>,
        result: MutableList<T>
    ) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (type.isInstance(child)) {
                @Suppress("UNCHECKED_CAST")
                result.add(child as T)
            }
            if (child is ViewGroup) {
                collectViews(child, type, result)
            }
        }
    }
}

