package my.hinoki.booxreader.ui.notes

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import my.hinoki.booxreader.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `AiNoteDetailActivity` 的佈局 inflate 測試。
 *
 * 這個畫面（2,100+ 行）在進行樣式重構前沒有任何測試。這個測試擔任護欄：
 * 重構 View 相關程式碼時，只要佈局或關鍵 View 的 id 被動到就會失敗
 * （沿用 `ReaderSettingsLayoutInflationTest` 的做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AiNoteDetailLayoutInflationTest {

    private fun inflate(): View {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(context, R.style.AppTheme_ActionBar_AiNoteDetail)
        return LayoutInflater.from(themed).inflate(R.layout.activity_ai_note_detail, null, false)
    }

    @Test
    fun layoutInflatesWithoutCrash() {
        assertNotNull(inflate())
    }

    @Test
    fun keyViewsExist() {
        val root = inflate()
        val ids =
                listOf(
                        R.id.rootView,
                        R.id.scrollView,
                        R.id.tvOriginalText,
                        R.id.tvAiResponse,
                        R.id.llInputArea,
                        R.id.etFollowUp,
                        R.id.btnFollowUp,
                        R.id.btnPublish,
                        R.id.btnRepublishSelection,
                        R.id.btnCopyAiResponse,
                        R.id.btnScrollToBottom,
                        R.id.tvAutoScrollHint
                )

        ids.forEach { id ->
            assertNotNull("missing view id ${root.resources.getResourceName(id)}", root.findViewById<View>(id))
        }
    }

    @Test
    fun scrollToBottomButtonAnchorsAboveInputArea() {
        // 迴歸測試：FAB 的 layout_above 必須指向同層兄弟，否則規則會被靜默忽略
        // （lint 的 NotSibling 檢查就是抓這件事）。
        val root = inflate()
        val fab = root.findViewById<View>(R.id.btnScrollToBottom)
        val inputArea = root.findViewById<View>(R.id.llInputArea)

        assertNotNull(fab)
        assertNotNull(inputArea)
        assert(fab.parent === inputArea.parent) {
            "btnScrollToBottom and llInputArea must be siblings in the root RelativeLayout"
        }
        assert(root is ViewGroup)
    }
}
