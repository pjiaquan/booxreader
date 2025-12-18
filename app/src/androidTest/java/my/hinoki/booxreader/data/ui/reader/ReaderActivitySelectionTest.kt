package my.hinoki.booxreader.data.ui.reader

import android.graphics.Rect
import android.os.Build
import android.view.ActionMode
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.hinoki.booxreader.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Instrumented test to validate ReaderActivity's selection and UI state changes.
 */
@RunWith(AndroidJUnit4::class)
class ReaderActivitySelectionTest {

    @Test
    fun testInitialUiState() {
        // We need a dummy book URI to launch ReaderActivity
        val intent = ReaderActivity.newIntent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "test_book_key",
            "Test Book"
        )
        
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // 1. Verify bottom bar is permanently hidden
                val bottomBar = activity.findViewById<View>(R.id.bottomBar)
                assertEquals("Bottom bar should be GONE", View.GONE, bottomBar.visibility)
                
                // 2. Verify tap overlay is GONE (WebView should handle touches)
                val tapOverlay = activity.findViewById<View>(R.id.tapOverlay)
                assertEquals("Tap overlay should be GONE", View.GONE, tapOverlay.visibility)
            }
        }
    }

    @Test
    fun testEdgeSafetyPaddingLogic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ReaderActivity.newIntent(context, "test_book_key", "Test Book")
        
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Access private selectionActionModeCallback via reflection
                val callbackField = ReaderActivity::class.java.getDeclaredField("selectionActionModeCallback")
                callbackField.isAccessible = true
                val callback = callbackField.get(activity) as ActionMode.Callback2

                // Access private lastSelectionContentRect via reflection
                val rectField = ReaderActivity::class.java.getDeclaredField("lastSelectionContentRect")
                rectField.isAccessible = true
                
                // Case 1: Rect is at the center - should not be shifted much
                val centerRect = Rect(100, 100, 200, 200)
                rectField.set(activity, centerRect)
                
                val outRect = Rect()
                callback.onGetContentRect(null, null, outRect)
                
                // Check if it matches or is within reasonable range
                assertTrue("OutRect should be set", outRect.width() > 0)
                
                // Case 2: Rect is at the extreme left edge
                val displayMetrics = activity.resources.displayMetrics
                val paddingX = (16 * displayMetrics.density).toInt()
                val edgeRect = Rect(0, 100, 50, 200)
                rectField.set(activity, edgeRect)
                
                callback.onGetContentRect(null, null, outRect)
                
                // The outRect.left should be pushed to at least paddingX
                assertTrue("OutRect.left (${outRect.left}) should be at least paddingX ($paddingX)", 
                    outRect.left >= paddingX)
                
                // Case 3: Rect is at the extreme right edge
                val width = activity.findViewById<View>(android.R.id.content).width
                if (width > 0) {
                    val rightEdgeRect = Rect(width - 50, 100, width, 200)
                    rectField.set(activity, rightEdgeRect)
                    
                    callback.onGetContentRect(null, null, outRect)
                    
                    assertTrue("OutRect.right (${outRect.right}) should be shifted left to avoid edge",
                        outRect.right <= width - paddingX)
                }
            }
        }
    }
    
    @Test
    fun testSelectionFlowActiveLogic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ReaderActivity.newIntent(context, "test_book_key", "Test Book")
        
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val isSelectionFlowActiveMethod = ReaderActivity::class.java.getDeclaredMethod("isSelectionFlowActive")
                isSelectionFlowActiveMethod.isAccessible = true
                
                val selectionStateField = ReaderActivity::class.java.getDeclaredField("selectionState")
                selectionStateField.isAccessible = true
                
                // Get enum constants
                val selectionStateEnum = Class.forName("my.hinoki.booxreader.data.ui.reader.ReaderActivity\$SelectionState")
                val idle = selectionStateEnum.enumConstants.find { it.toString() == "IDLE" }
                val selecting = selectionStateEnum.enumConstants.find { it.toString() == "SELECTING" }
                val menuOpen = selectionStateEnum.enumConstants.find { it.toString() == "MENU_OPEN" }
                
                // Test IDLE
                selectionStateField.set(activity, idle)
                assertFalse("Selection flow should not be active in IDLE", isSelectionFlowActiveMethod.invoke(activity) as Boolean)
                
                // Test SELECTING
                selectionStateField.set(activity, selecting)
                assertTrue("Selection flow should be active in SELECTING", isSelectionFlowActiveMethod.invoke(activity) as Boolean)
                
                // Test MENU_OPEN
                selectionStateField.set(activity, menuOpen)
                assertTrue("Selection flow should be active in MENU_OPEN", isSelectionFlowActiveMethod.invoke(activity) as Boolean)
            }
        }
    }
}
