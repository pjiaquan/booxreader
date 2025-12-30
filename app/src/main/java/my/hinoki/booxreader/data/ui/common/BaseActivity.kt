package my.hinoki.booxreader.data.ui.common

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

    protected fun applyStatusBarInset(view: View) {
        val initialTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, initialTop + topInset, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
