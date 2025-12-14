package my.hinoki.booxreader.data.ui.common

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getPersistedLanguage(newBase)
        if (lang != "system") {
            super.attachBaseContext(LocaleHelper.onAttach(newBase))
        } else {
            super.attachBaseContext(newBase)
        }
    }
}
