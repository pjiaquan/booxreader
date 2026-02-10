package my.hinoki.booxreader.ui.reader

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.launch
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.ui.auth.UserProfileActivity
import my.hinoki.booxreader.ui.common.BaseActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ReaderSettingsActivity : BaseActivity() {

    companion object {
        private const val EXTRA_BOOK_KEY = "extra_book_key"
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_ADD_BOOKMARK = "action_add_bookmark"
        const val ACTION_SHOW_BOOKMARKS = "action_show_bookmarks"

        fun newIntent(context: Context, bookKey: String?): Intent {
            return Intent(context, ReaderSettingsActivity::class.java).apply {
                if (!bookKey.isNullOrBlank()) {
                    putExtra(EXTRA_BOOK_KEY, bookKey)
                }
            }
        }

        fun open(context: Context, bookKey: String?) {
            context.startActivity(newIntent(context, bookKey))
        }
    }

    private val syncRepo by lazy { UserSyncRepository(applicationContext) }

    private data class MagicTagRow(
            val id: String?,
            val titleInput: EditText,
            val contentInput: EditText,
            val container: View
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.reader_settings_title)
        applyFooterInsets()

        setupSettingsScreen()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupSettingsScreen() {
        val dialogView = findViewById<View>(R.id.settingsContent)
        val btnSettingsSave = findViewById<Button>(R.id.btnSettingsSave)
        val btnSettingsCancel = findViewById<Button>(R.id.btnSettingsCancel)
        btnSettingsSave.isAllCaps = false
        btnSettingsCancel.isAllCaps = false

        val btnSettingsAddBookmark = dialogView.findViewById<Button>(R.id.btnSettingsAddBookmark)
        val btnSettingsShowBookmarks =
                dialogView.findViewById<Button>(R.id.btnSettingsShowBookmarks)
        val switchPageTap =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap)
        val switchPageSwipe =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchPageSwipe
                )
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etApiKey)
        val cbCustomExport = dialogView.findViewById<CheckBox>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)
        val cbLocalExport = dialogView.findViewById<CheckBox>(R.id.cbLocalExport)
        val btnTestExport = dialogView.findViewById<Button>(R.id.btnTestExportEndpoint)
        val btnManageMagicTags = dialogView.findViewById<Button>(R.id.btnManageMagicTags)
        val switchPageAnimation =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchPageAnimation
                )
        val switchPageIndicator =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchPageIndicator
                )
        val switchConvertChinese =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchConvertChinese
                )
        val seekBarTextSize = dialogView.findViewById<SeekBar>(R.id.seekBarTextSize)
        val tvTextSizeValue = dialogView.findViewById<TextView>(R.id.tvTextSizeValue)

        val layout = (dialogView as? ViewGroup)?.getChildAt(0) as? LinearLayout

        var topInsertIndex = 0
        fun insertTop(view: View) {
            layout?.addView(view, topInsertIndex)
            topInsertIndex += 1
        }

        val languageTitle =
                TextView(this).apply {
                    text = getString(R.string.reader_settings_language_title)
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply {
                                        topMargin =
                                                resources.getDimensionPixelSize(
                                                        R.dimen.reader_settings_language_section_margin_top
                                                )
                                    }
                }
        insertTop(languageTitle)

        val languageGroup =
                android.widget.RadioGroup(this).apply {
                    orientation = android.widget.RadioGroup.VERTICAL
                }

        val rbSystem = android.widget.RadioButton(this).apply { text = "System Default (跟隨系統)" }
        val rbEnglish = android.widget.RadioButton(this).apply { text = "English" }
        val rbChinese =
                android.widget.RadioButton(this).apply { text = "Traditional Chinese (繁體中文)" }
        languageGroup.addView(rbSystem)
        languageGroup.addView(rbEnglish)
        languageGroup.addView(rbChinese)
        insertTop(languageGroup)

        val btnUserProfile =
                Button(this).apply {
                    text = getString(R.string.reader_settings_user_profile)
                    isAllCaps = false
                }
        insertTop(btnUserProfile)

        val themeTitle =
                TextView(this).apply {
                    text = getString(R.string.reader_settings_theme_title)
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                }
        insertTop(themeTitle)

        val themeContainer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 3f
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                }

        var selectedContrastMode: ContrastMode =
                ContrastMode.values()
                        .getOrNull(
                                ReaderSettings.fromPrefs(
                                                getSharedPreferences(
                                                        ReaderActivity.PREFS_NAME,
                                                        MODE_PRIVATE
                                                )
                                        )
                                        .contrastMode
                        ) ?: ContrastMode.NORMAL

        val btnNormal =
                Button(this).apply {
                    text = "Normal"
                    isAllCaps = false
                    layoutParams =
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        selectedContrastMode = ContrastMode.NORMAL
                        applySettingsPageTheme(dialogView, selectedContrastMode)
                        applySettingsChrome(selectedContrastMode)
                        Toast.makeText(this@ReaderSettingsActivity, "Normal Mode", Toast.LENGTH_SHORT)
                                .show()
                    }
                }
        val btnDark =
                Button(this).apply {
                    text = "Dark"
                    isAllCaps = false
                    layoutParams =
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        selectedContrastMode = ContrastMode.DARK
                        applySettingsPageTheme(dialogView, selectedContrastMode)
                        applySettingsChrome(selectedContrastMode)
                        Toast.makeText(this@ReaderSettingsActivity, "Dark Mode", Toast.LENGTH_SHORT)
                                .show()
                    }
                }
        val btnSepia =
                Button(this).apply {
                    text = "Sepia"
                    isAllCaps = false
                    layoutParams =
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        selectedContrastMode = ContrastMode.SEPIA
                        applySettingsPageTheme(dialogView, selectedContrastMode)
                        applySettingsChrome(selectedContrastMode)
                        Toast.makeText(this@ReaderSettingsActivity, "Sepia Mode", Toast.LENGTH_SHORT)
                                .show()
                    }
                }
        themeContainer.addView(btnNormal)
        themeContainer.addView(btnDark)
        themeContainer.addView(btnSepia)
        insertTop(themeContainer)

        val btnAiProfiles =
                Button(this).apply {
                    text = getString(R.string.reader_settings_ai_profiles)
                    isAllCaps = false
                }
        insertTop(btnAiProfiles)

        val prefs = getSharedPreferences(ReaderActivity.PREFS_NAME, MODE_PRIVATE)
        val readerSettings = ReaderSettings.fromPrefs(prefs)
        selectedContrastMode =
                ContrastMode.values().getOrNull(readerSettings.contrastMode) ?: ContrastMode.NORMAL

        when (readerSettings.language) {
            "zh" -> rbChinese.isChecked = true
            "en" -> rbEnglish.isChecked = true
            else -> rbSystem.isChecked = true
        }

        etServerUrl.setText(readerSettings.serverBaseUrl)
        etApiKey.setText(readerSettings.apiKey)
        switchPageTap.isChecked = readerSettings.pageTapEnabled
        switchPageSwipe.isChecked = readerSettings.pageSwipeEnabled
        switchPageAnimation.isChecked = readerSettings.pageAnimationEnabled
        switchPageIndicator.isChecked = readerSettings.showPageIndicator
        switchConvertChinese.isChecked = readerSettings.convertToTraditionalChinese
        cbCustomExport.isChecked = readerSettings.exportToCustomUrl
        etCustomExportUrl.setText(readerSettings.exportCustomUrl)
        etCustomExportUrl.isEnabled = readerSettings.exportToCustomUrl
        cbLocalExport.isChecked = readerSettings.exportToLocalDownloads
        seekBarTextSize.progress = readerSettings.textSize - 50
        tvTextSizeValue.text = "${readerSettings.textSize}%"

        seekBarTextSize.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        tvTextSizeValue.text = "${progress + 50}%"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
        )

        switchConvertChinese.setOnCheckedChangeListener { _, isChecked ->
            val currentSettings = ReaderSettings.fromPrefs(prefs)
            val updatedSettings =
                    currentSettings.copy(
                            convertToTraditionalChinese = isChecked,
                            updatedAt = System.currentTimeMillis()
                    )
            updatedSettings.saveTo(prefs)
            setResult(RESULT_OK)
            val message = if (isChecked) "已啟用簡體轉繁體" else "已停用簡體轉繁體"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        cbCustomExport.setOnCheckedChangeListener { _, isChecked ->
            etCustomExportUrl.isEnabled = isChecked
        }

        btnTestExport.setOnClickListener {
            val app = application as BooxReaderApp
            val repo = AiNoteRepository(app, app.okHttpClient, syncRepo)
            val baseUrl =
                    etServerUrl.text.toString().trim().ifEmpty { readerSettings.serverBaseUrl }
            val targetUrl =
                    if (cbCustomExport.isChecked &&
                                    etCustomExportUrl.text.toString().trim().isNotEmpty()
                    ) {
                        etCustomExportUrl.text.toString().trim()
                    } else {
                        val trimmed = baseUrl.trimEnd('/')
                        if (trimmed.isNotEmpty()) trimmed + HttpConfig.PATH_AI_NOTES_EXPORT else ""
                    }

            if (targetUrl.isEmpty()) {
                Toast.makeText(this, "請輸入有效的 URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTestExport.isEnabled = false
            val originalText = btnTestExport.text
            btnTestExport.text = "Testing..."
            lifecycleScope.launch {
                val result = repo.testExportEndpoint(targetUrl)
                Toast.makeText(this@ReaderSettingsActivity, "Export test: $result", Toast.LENGTH_LONG)
                        .show()
                btnTestExport.text = originalText
                btnTestExport.isEnabled = true
            }
        }

        btnManageMagicTags.setOnClickListener { showMagicTagManager() }

        val hasBookContext = !intent.getStringExtra(EXTRA_BOOK_KEY).isNullOrBlank()
        btnSettingsAddBookmark.isEnabled = hasBookContext
        btnSettingsShowBookmarks.isEnabled = hasBookContext
        btnSettingsAddBookmark.setOnClickListener {
            if (!hasBookContext) return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_ADD_BOOKMARK))
            finish()
        }
        btnSettingsShowBookmarks.setOnClickListener {
            if (!hasBookContext) return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_SHOW_BOOKMARKS))
            finish()
        }

        btnUserProfile.setOnClickListener {
            startActivity(Intent(this@ReaderSettingsActivity, UserProfileActivity::class.java))
        }

        btnAiProfiles.setOnClickListener {
            my.hinoki.booxreader.ui.settings.AiProfileListActivity.open(this@ReaderSettingsActivity)
        }

        btnSettingsCancel.setOnClickListener { finish() }
        btnSettingsSave.setOnClickListener {
            saveSettings(
                    prefs = prefs,
                    currentSettings = readerSettings,
                    selectedContrastMode = selectedContrastMode,
                    rbChinese = rbChinese,
                    rbEnglish = rbEnglish,
                    etServerUrl = etServerUrl,
                    etApiKey = etApiKey,
                    switchPageTap = switchPageTap,
                    switchPageSwipe = switchPageSwipe,
                    switchPageAnimation = switchPageAnimation,
                    switchPageIndicator = switchPageIndicator,
                    switchConvertChinese = switchConvertChinese,
                    cbCustomExport = cbCustomExport,
                    etCustomExportUrl = etCustomExportUrl,
                    cbLocalExport = cbLocalExport,
                    seekBarTextSize = seekBarTextSize
            )
        }

        applySettingsPageTheme(dialogView, selectedContrastMode)
        applySettingsChrome(selectedContrastMode)
    }

    private fun saveSettings(
            prefs: android.content.SharedPreferences,
            currentSettings: ReaderSettings,
            selectedContrastMode: ContrastMode,
            rbChinese: android.widget.RadioButton,
            rbEnglish: android.widget.RadioButton,
            etServerUrl: EditText,
            etApiKey: EditText,
            switchPageTap: androidx.appcompat.widget.SwitchCompat,
            switchPageSwipe: androidx.appcompat.widget.SwitchCompat,
            switchPageAnimation: androidx.appcompat.widget.SwitchCompat,
            switchPageIndicator: androidx.appcompat.widget.SwitchCompat,
            switchConvertChinese: androidx.appcompat.widget.SwitchCompat,
            cbCustomExport: CheckBox,
            etCustomExportUrl: EditText,
            cbLocalExport: CheckBox,
            seekBarTextSize: SeekBar
    ) {
        val newUrlRaw = etServerUrl.text.toString().trim()
        val newApiKey = etApiKey.text.toString().trim()
        val newPageTap = switchPageTap.isChecked
        val newPageSwipe = switchPageSwipe.isChecked
        val newPageAnimation = switchPageAnimation.isChecked
        val newShowPageIndicator = switchPageIndicator.isChecked
        val newConvertChinese = switchConvertChinese.isChecked
        val useCustomExport = cbCustomExport.isChecked
        val customExportUrlRaw = etCustomExportUrl.text.toString().trim()
        val exportToLocal = cbLocalExport.isChecked
        val newTextSize = seekBarTextSize.progress + 50

        val normalizedBaseUrl =
                if (newUrlRaw.isNotEmpty()) normalizeUrl(newUrlRaw) else currentSettings.serverBaseUrl
        val normalizedCustomUrl =
                if (useCustomExport && customExportUrlRaw.isNotEmpty())
                        normalizeUrl(customExportUrlRaw)
                else ""

        if (newUrlRaw.isNotEmpty() && normalizedBaseUrl.toHttpUrlOrNull() == null) {
            Toast.makeText(this, "Server URL is invalid", Toast.LENGTH_SHORT).show()
            return
        }
        if (useCustomExport &&
                        customExportUrlRaw.isNotEmpty() &&
                        normalizedCustomUrl.toHttpUrlOrNull() == null
        ) {
            Toast.makeText(this, "Custom export URL is invalid", Toast.LENGTH_SHORT).show()
            return
        }

        val updatedSettings =
                currentSettings.copy(
                        serverBaseUrl = normalizedBaseUrl,
                        apiKey = newApiKey,
                        pageTapEnabled = newPageTap,
                        pageSwipeEnabled = newPageSwipe,
                        pageAnimationEnabled = newPageAnimation,
                        showPageIndicator = newShowPageIndicator,
                        convertToTraditionalChinese = newConvertChinese,
                        exportToCustomUrl = useCustomExport,
                        exportCustomUrl = normalizedCustomUrl,
                        exportToLocalDownloads = exportToLocal,
                        textSize = newTextSize,
                        contrastMode = selectedContrastMode.ordinal,
                        language =
                                when {
                                    rbChinese.isChecked -> "zh"
                                    rbEnglish.isChecked -> "en"
                                    else -> "system"
                                },
                        updatedAt = System.currentTimeMillis()
                )

        updatedSettings.saveTo(prefs)
        pushSettingsToCloud()

        if (updatedSettings.language != currentSettings.language) {
            val intent =
                    Intent(
                            applicationContext,
                            my.hinoki.booxreader.ui.welcome.WelcomeActivity::class.java
                    )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
            return
        }

        setResult(RESULT_OK)
        finish()
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun applySettingsPageTheme(root: View, mode: ContrastMode) {
        val backgroundColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#FAF9F6")
                    ContrastMode.DARK -> Color.parseColor("#121212")
                    ContrastMode.SEPIA -> Color.parseColor("#F2E7D0")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                }
        val textColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.BLACK
                    ContrastMode.DARK -> Color.parseColor("#F2F5FA")
                    ContrastMode.SEPIA -> Color.parseColor("#5B4636")
                    ContrastMode.HIGH_CONTRAST -> Color.WHITE
                }
        val hintColor =
                ColorUtils.setAlphaComponent(
                        textColor,
                        if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 190
                        else 140
                )
        val dividerColor =
                ColorUtils.setAlphaComponent(
                        textColor,
                        if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 90
                        else 60
                )
        val buttonColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#E0E0E0")
                    ContrastMode.DARK -> Color.parseColor("#2B323C")
                    ContrastMode.SEPIA -> Color.parseColor("#D9C5A3")
                    ContrastMode.HIGH_CONTRAST -> Color.DKGRAY
                }

        root.setBackgroundColor(backgroundColor)

        fun applyToView(view: View) {
            if (view is ViewGroup && view.background != null) {
                view.setBackgroundColor(backgroundColor)
            }
            when (view) {
                is EditText -> {
                    view.setTextColor(textColor)
                    view.setHintTextColor(hintColor)
                }
                is Button -> {
                    view.setTextColor(textColor)
                    view.backgroundTintList = ColorStateList.valueOf(buttonColor)
                }
                is android.widget.CompoundButton -> {
                    view.setTextColor(textColor)
                }
                is TextView -> {
                    view.setTextColor(textColor)
                }
                is SeekBar -> {
                    view.progressTintList = ColorStateList.valueOf(textColor)
                    view.thumbTintList = ColorStateList.valueOf(textColor)
                }
                else -> {
                    val height = view.layoutParams?.height ?: 0
                    if (height in 1..2) {
                        view.setBackgroundColor(dividerColor)
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    applyToView(view.getChildAt(i))
                }
            }
        }

        applyToView(root)
    }

    private fun pushSettingsToCloud() {
        val prefs = getSharedPreferences(ReaderActivity.PREFS_NAME, MODE_PRIVATE)
        lifecycleScope.launch { syncRepo.pushSettings(ReaderSettings.fromPrefs(prefs)) }
    }

    private fun applyFooterInsets() {
        val root = findViewById<View>(R.id.readerSettingsRoot)
        val footer = findViewById<View>(R.id.settingsFooter)
        val baseBottom = footer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            footer.updatePadding(bottom = baseBottom + systemBars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySettingsChrome(mode: ContrastMode) {
        val pageColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#F8F9FB")
                    ContrastMode.DARK -> Color.parseColor("#0B0E13")
                    ContrastMode.SEPIA -> Color.parseColor("#F0E6D3")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                }
        val barColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#EEF1F5")
                    ContrastMode.DARK -> Color.parseColor("#0A0D12")
                    ContrastMode.SEPIA -> Color.parseColor("#E7D9BF")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                }
        val footerColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#F2F4F8")
                    ContrastMode.DARK -> Color.parseColor("#0E1217")
                    ContrastMode.SEPIA -> Color.parseColor("#EBDDCA")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                }
        val dividerColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#D9DEE6")
                    ContrastMode.DARK -> Color.parseColor("#26303A")
                    ContrastMode.SEPIA -> Color.parseColor("#CCBCA0")
                    ContrastMode.HIGH_CONTRAST -> Color.WHITE
                }

        findViewById<View>(R.id.readerSettingsRoot).setBackgroundColor(pageColor)
        findViewById<View>(R.id.settingsFooter).setBackgroundColor(footerColor)
        findViewById<View>(R.id.settingsFooterDivider).setBackgroundColor(dividerColor)
        styleFooterButtons(mode)

        supportActionBar?.setBackgroundDrawable(ColorDrawable(barColor))
        applyActionBarContentColor(barColor)
        @Suppress("DEPRECATION")
        run {
            window.decorView.setBackgroundColor(pageColor)
            window.statusBarColor = barColor
            window.navigationBarColor = footerColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val useDarkIcons = ColorUtils.calculateLuminance(barColor) > 0.5
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(footerColor) > 0.5
        }
    }

    private fun styleFooterButtons(mode: ContrastMode) {
        val cancel = findViewById<Button>(R.id.btnSettingsCancel)
        val save = findViewById<Button>(R.id.btnSettingsSave)
        val cancelBackground: Int
        val cancelText: Int
        val saveBackground: Int
        val saveText: Int
        when (mode) {
            ContrastMode.NORMAL -> {
                cancelBackground = Color.parseColor("#E4E8EE")
                cancelText = Color.parseColor("#1F2937")
                saveBackground = Color.parseColor("#0A84FF")
                saveText = Color.WHITE
            }
            ContrastMode.DARK -> {
                cancelBackground = Color.parseColor("#2A3038")
                cancelText = Color.parseColor("#E5E9F0")
                saveBackground = Color.parseColor("#0A84FF")
                saveText = Color.WHITE
            }
            ContrastMode.SEPIA -> {
                cancelBackground = Color.parseColor("#D9C9AA")
                cancelText = Color.parseColor("#4C392C")
                saveBackground = Color.parseColor("#6E5635")
                saveText = Color.parseColor("#FFF8E9")
            }
            ContrastMode.HIGH_CONTRAST -> {
                cancelBackground = Color.parseColor("#222222")
                cancelText = Color.WHITE
                saveBackground = Color.WHITE
                saveText = Color.BLACK
            }
        }
        cancel.backgroundTintList = ColorStateList.valueOf(cancelBackground)
        cancel.setTextColor(cancelText)
        save.backgroundTintList = ColorStateList.valueOf(saveBackground)
        save.setTextColor(saveText)
    }

    private fun applyActionBarContentColor(barColor: Int) {
        val title = getString(R.string.reader_settings_title)
        val contentColor =
                if (ColorUtils.calculateLuminance(barColor) > 0.5) Color.BLACK else Color.WHITE

        val styledTitle = SpannableString(title).apply {
            setSpan(ForegroundColorSpan(contentColor), 0, length, 0)
        }
        supportActionBar?.title = styledTitle

        val backDrawable =
                AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                        ?.mutate()
                        ?.let { drawable ->
                            DrawableCompat.setTint(drawable, contentColor)
                            drawable
                        }
        if (backDrawable != null) {
            supportActionBar?.setHomeAsUpIndicator(backDrawable)
        }
    }

    private fun showMagicTagManager() {
        val prefs = getSharedPreferences(ReaderActivity.PREFS_NAME, MODE_PRIVATE)
        val settings = ReaderSettings.fromPrefs(prefs)

        val rows = mutableListOf<MagicTagRow>()
        val contentLayout =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 16)
                }

        val btnAdd =
                Button(this).apply { text = getString(R.string.action_manage_magic_tags) + " +" }
        contentLayout.addView(btnAdd)

        val scrollView = android.widget.ScrollView(this).apply { addView(contentLayout) }

        fun addRow(tag: MagicTag?) {
            val rowContainer =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 16, 0, 16)
                    }

            val titleInput =
                    EditText(this).apply {
                        hint = "Tag Name"
                        setText(tag?.label.orEmpty())
                    }
            val contentInput =
                    EditText(this).apply {
                        hint = "Tag Content"
                        setText(tag?.content?.ifBlank { tag.label }.orEmpty())
                        minLines = 2
                    }
            val btnDelete = Button(this).apply { text = "Delete" }

            rowContainer.addView(titleInput)
            rowContainer.addView(contentInput)
            rowContainer.addView(btnDelete)

            val row = MagicTagRow(tag?.id, titleInput, contentInput, rowContainer)
            rows.add(row)
            contentLayout.addView(rowContainer)

            btnDelete.setOnClickListener {
                rows.remove(row)
                contentLayout.removeView(rowContainer)
            }
        }

        settings.magicTags.forEach { addRow(it) }

        btnAdd.setOnClickListener { addRow(null) }

        val dialog =
                androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.action_manage_magic_tags))
                        .setView(scrollView)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?.setOnClickListener {
                        Log.d("MagicTags", "Save clicked; rows=${rows.size}")
                        val seen = mutableSetOf<String>()
                        val updatedTags =
                                rows.mapIndexedNotNull { index, row ->
                                    val title = row.titleInput.text.toString().trim()
                                    val content =
                                            row.contentInput.text.toString().trim().ifBlank {
                                                title
                                            }
                                    if (title.isBlank()) {
                                        if (content.isNotBlank()) {
                                            Toast.makeText(
                                                            this,
                                                            getString(
                                                                    R.string
                                                                            .magic_tag_invalid_empty_name
                                                            ),
                                                            Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                            return@setOnClickListener
                                        }
                                        return@mapIndexedNotNull null
                                    }
                                    if (!seen.add(title)) {
                                        Toast.makeText(
                                                        this,
                                                        getString(
                                                                R.string.magic_tag_invalid_duplicate
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                        return@setOnClickListener
                                    }
                                    val id = row.id ?: "custom-${System.currentTimeMillis()}-$index"
                                    MagicTag(
                                            id = id,
                                            label = title,
                                            content = content,
                                            description = content
                                    )
                                }
                        val updatedSettings =
                                settings.copy(
                                        magicTags = updatedTags,
                                        updatedAt = System.currentTimeMillis()
                                )
                        updatedSettings.saveTo(prefs)
                        val magicTagsJson = Gson().toJson(updatedTags)
                        prefs.edit()
                                .putString("magic_tags", magicTagsJson)
                                .putLong("settings_updated_at", updatedSettings.updatedAt)
                                .apply()
                        pushSettingsToCloud()
                        setResult(RESULT_OK)
                        Toast.makeText(
                                        this,
                                        getString(R.string.action_manage_magic_tags) + " OK",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                        dialog.dismiss()
                    }
        }

        dialog.show()
    }
}
