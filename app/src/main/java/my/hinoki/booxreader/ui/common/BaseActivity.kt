package my.hinoki.booxreader.ui.common

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getPersistedLanguage(newBase)
        if (lang != "system") {
            super.attachBaseContext(LocaleHelper.onAttach(newBase))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    protected fun applyTopInsets(view: View, includeActionBar: Boolean = false) {
        val initialTop = view.paddingTop
        val actionBarExtra =
                if (includeActionBar) {
                    val typedValue = android.util.TypedValue()
                    if (theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
                        android.util.TypedValue.complexToDimensionPixelSize(
                                typedValue.data,
                                resources.displayMetrics
                        )
                    } else {
                        0
                    }
                } else {
                    0
                }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(
                    v.paddingLeft,
                    initialTop + topInset + actionBarExtra,
                    v.paddingRight,
                    v.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    protected fun applyActionBarPadding(view: View) {
        val initialTop = view.paddingTop
        val typedValue = android.util.TypedValue()
        val actionBarSize =
                if (theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
                    android.util.TypedValue.complexToDimensionPixelSize(
                            typedValue.data,
                            resources.displayMetrics
                    )
                } else {
                    0
                }
        val adjusted = (actionBarSize * 0.6f).toInt()
        view.setPadding(view.paddingLeft, initialTop + adjusted, view.paddingRight, view.paddingBottom)
    }
}
