package my.hinoki.booxreader.ui.reader
import my.hinoki.booxreader.data.settings.SharedPreferencesStorage

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Patterns
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.widget.SwitchCompat
import my.hinoki.booxreader.data.prefs.TokenManager
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.createAiNoteRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.repo.createUserSyncRepository
import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.worker.DailySummaryEmailScheduler
import my.hinoki.booxreader.ui.auth.UserProfileActivity
import my.hinoki.booxreader.ui.common.BaseActivity
import my.hinoki.booxreader.ui.settings.AiProfileListActivity
import my.hinoki.booxreader.data.remote.isValidHttpUrl

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

    private val syncRepo by lazy { createUserSyncRepository(applicationContext) }

    private var selectedContrastMode: ContrastMode = ContrastMode.NORMAL
    private var selectedDailySummaryHour: Int = 0
    private var selectedDailySummaryMinute: Int = 0
    private var selectedLanguage: String = "system"

    private data class ButtonVisualStyle(
        val fillColor: Int,
        val pressedFillColor: Int,
        val disabledFillColor: Int,
        val strokeColor: Int,
        val textColor: Int,
        val disabledTextColor: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarSettings)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            toolbar.setNavigationOnClickListener { finish() }
        }
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

        // Account & Profile
        val btnUserProfile = dialogView.findViewById<Button>(R.id.btnUserProfile)
        val btnAiProfiles = dialogView.findViewById<Button>(R.id.btnAiProfiles)
        btnUserProfile?.setOnClickListener {
            startActivity(Intent(this, UserProfileActivity::class.java))
        }
        btnAiProfiles?.setOnClickListener {
            AiProfileListActivity.open(this)
        }

        // Reading & Theme Controls
        val btnThemeNormal = dialogView.findViewById<Button>(R.id.btnThemeNormal)
        val btnThemeDark = dialogView.findViewById<Button>(R.id.btnThemeDark)
        val btnThemeSepia = dialogView.findViewById<Button>(R.id.btnThemeSepia)
        val btnThemeHighContrast = dialogView.findViewById<Button>(R.id.btnThemeHighContrast)
        val seekBarTextSize = dialogView.findViewById<SeekBar>(R.id.seekBarTextSize)
        val tvTextSizeValue = dialogView.findViewById<TextView>(R.id.tvTextSizeValue)

        // Navigation & Display Controls
        val switchPageTap = dialogView.findViewById<SwitchCompat>(R.id.switchPageTap)
        val switchPageSwipe = dialogView.findViewById<SwitchCompat>(R.id.switchPageSwipe)
        val switchPageAnimation = dialogView.findViewById<SwitchCompat>(R.id.switchPageAnimation)
        val switchPageIndicator = dialogView.findViewById<SwitchCompat>(R.id.switchPageIndicator)
        val switchConvertChinese = dialogView.findViewById<SwitchCompat>(R.id.switchConvertChinese)

        val rowPageTap = dialogView.findViewById<View>(R.id.rowPageTap)
        val rowPageSwipe = dialogView.findViewById<View>(R.id.rowPageSwipe)
        val rowPageAnimation = dialogView.findViewById<View>(R.id.rowPageAnimation)
        val rowPageIndicator = dialogView.findViewById<View>(R.id.rowPageIndicator)
        val rowConvertChinese = dialogView.findViewById<View>(R.id.rowConvertChinese)

        rowPageTap?.setOnClickListener { switchPageTap?.toggle() }
        rowPageSwipe?.setOnClickListener { switchPageSwipe?.toggle() }
        rowPageAnimation?.setOnClickListener { switchPageAnimation?.toggle() }
        rowPageIndicator?.setOnClickListener { switchPageIndicator?.toggle() }
        rowConvertChinese?.setOnClickListener { switchConvertChinese?.toggle() }

        // Language Controls (iOS Checkmark List Rows)
        val rowLangSystem = dialogView.findViewById<View>(R.id.rowLangSystem)
        val rowLangEnglish = dialogView.findViewById<View>(R.id.rowLangEnglish)
        val rowLangChinese = dialogView.findViewById<View>(R.id.rowLangChinese)
        val ivCheckLangSystem = dialogView.findViewById<ImageView>(R.id.ivCheckLangSystem)
        val ivCheckLangEnglish = dialogView.findViewById<ImageView>(R.id.ivCheckLangEnglish)
        val ivCheckLangChinese = dialogView.findViewById<ImageView>(R.id.ivCheckLangChinese)

        // AI & Cloud Sync Controls
        val btnManageMagicTags = dialogView.findViewById<Button>(R.id.btnManageMagicTags)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etApiKey)

        // Daily Digest Controls
        val switchDailySummaryEmail = dialogView.findViewById<SwitchCompat>(R.id.switchDailySummaryEmail)
        val rowDailySummaryEmail = dialogView.findViewById<View>(R.id.rowDailySummaryEmail)
        val tvDailySummaryTimeValue = dialogView.findViewById<TextView>(R.id.tvDailySummaryTimeValue)
        val btnDailySummaryPickTime = dialogView.findViewById<Button>(R.id.btnDailySummaryPickTime)
        val etDailySummaryEmailTo = dialogView.findViewById<EditText>(R.id.etDailySummaryEmailTo)
        rowDailySummaryEmail?.setOnClickListener { switchDailySummaryEmail?.toggle() }

        // Bookmarks & Export Controls
        val btnSettingsAddBookmark = dialogView.findViewById<Button>(R.id.btnSettingsAddBookmark)
        val btnSettingsShowBookmarks = dialogView.findViewById<Button>(R.id.btnSettingsShowBookmarks)
        val cbCustomExport = dialogView.findViewById<SwitchCompat>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)
        val cbLocalExport = dialogView.findViewById<SwitchCompat>(R.id.cbLocalExport)
        val btnTestExport = dialogView.findViewById<Button>(R.id.btnTestExportEndpoint)
        val rowCustomExport = dialogView.findViewById<View>(R.id.rowCustomExport)
        val rowLocalExport = dialogView.findViewById<View>(R.id.rowLocalExport)
        rowCustomExport?.setOnClickListener { cbCustomExport?.toggle() }
        rowLocalExport?.setOnClickListener { cbLocalExport?.toggle() }

        // App Preferences & Version
        val switchAutoCheckUpdates = dialogView.findViewById<SwitchCompat>(R.id.switchAutoCheckUpdates)
        val rowAutoCheckUpdates = dialogView.findViewById<View>(R.id.rowAutoCheckUpdates)
        val tvAppVersion = dialogView.findViewById<TextView>(R.id.tvAppVersion)
        rowAutoCheckUpdates?.setOnClickListener { switchAutoCheckUpdates?.toggle() }
        tvAppVersion?.text = "BooxReader v${my.hinoki.booxreader.BuildConfig.VERSION_NAME}"

        val prefs = getSharedPreferences(ReaderActivity.PREFS_NAME, MODE_PRIVATE)
        val readerSettings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
        selectedDailySummaryHour = readerSettings.dailySummaryEmailHour.coerceIn(0, 23)
        selectedDailySummaryMinute = readerSettings.dailySummaryEmailMinute.coerceIn(0, 59)
        selectedContrastMode = ContrastMode.values().getOrNull(readerSettings.contrastMode) ?: ContrastMode.NORMAL

        selectedLanguage = readerSettings.language

        fun updateLanguageUI(lang: String) {
            selectedLanguage = lang
            ivCheckLangSystem?.visibility = if (lang == "system" || (lang != "zh" && lang != "en")) View.VISIBLE else View.GONE
            ivCheckLangEnglish?.visibility = if (lang == "en") View.VISIBLE else View.GONE
            ivCheckLangChinese?.visibility = if (lang == "zh") View.VISIBLE else View.GONE
        }

        updateLanguageUI(selectedLanguage)

        rowLangSystem?.setOnClickListener { updateLanguageUI("system") }
        rowLangEnglish?.setOnClickListener { updateLanguageUI("en") }
        rowLangChinese?.setOnClickListener { updateLanguageUI("zh") }

        // Setup Theme selection buttons
        fun updateThemeSelection(mode: ContrastMode) {
            selectedContrastMode = mode
            applySettingsPageTheme(dialogView, selectedContrastMode)
            applySettingsChrome(selectedContrastMode)
            updateThemeButtonsSelection(dialogView, selectedContrastMode)
        }

        btnThemeNormal?.setOnClickListener { updateThemeSelection(ContrastMode.NORMAL) }
        btnThemeDark?.setOnClickListener { updateThemeSelection(ContrastMode.DARK) }
        btnThemeSepia?.setOnClickListener { updateThemeSelection(ContrastMode.SEPIA) }
        btnThemeHighContrast?.setOnClickListener { updateThemeSelection(ContrastMode.HIGH_CONTRAST) }

        setupGeneralPreferences(
            readerSettings, prefs, etServerUrl, etApiKey, switchPageTap, switchPageSwipe,
            switchPageAnimation, switchPageIndicator, switchAutoCheckUpdates, switchDailySummaryEmail,
            etDailySummaryEmailTo, switchConvertChinese, cbCustomExport, etCustomExportUrl,
            cbLocalExport, seekBarTextSize, tvTextSizeValue
        )

        setupDailySummary(switchDailySummaryEmail, tvDailySummaryTimeValue, btnDailySummaryPickTime, etDailySummaryEmailTo)
        setupExportTest(cbCustomExport, etCustomExportUrl, btnTestExport, etServerUrl, readerSettings)

        btnManageMagicTags?.setOnClickListener { showMagicTagManager() }

        val hasBookContext = !intent.getStringExtra(EXTRA_BOOK_KEY).isNullOrBlank()
        btnSettingsAddBookmark?.isEnabled = hasBookContext
        btnSettingsShowBookmarks?.isEnabled = hasBookContext
        btnSettingsAddBookmark?.setOnClickListener {
            if (!hasBookContext) return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_ADD_BOOKMARK))
            finish()
        }
        btnSettingsShowBookmarks?.setOnClickListener {
            if (!hasBookContext) return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_SHOW_BOOKMARKS))
            finish()
        }

        btnSettingsCancel.setOnClickListener { finish() }
        btnSettingsSave.setOnClickListener {
            val latestSettings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
            saveSettings(
                prefs = prefs,
                currentSettings = latestSettings,
                selectedContrastMode = selectedContrastMode,
                selectedLanguage = selectedLanguage,
                etServerUrl = etServerUrl,
                etApiKey = etApiKey,
                switchPageTap = switchPageTap,
                switchPageSwipe = switchPageSwipe,
                switchPageAnimation = switchPageAnimation,
                switchPageIndicator = switchPageIndicator,
                switchAutoCheckUpdates = switchAutoCheckUpdates,
                switchDailySummaryEmail = switchDailySummaryEmail,
                dailySummaryHour = selectedDailySummaryHour,
                dailySummaryMinute = selectedDailySummaryMinute,
                etDailySummaryEmailTo = etDailySummaryEmailTo,
                switchConvertChinese = switchConvertChinese,
                cbCustomExport = cbCustomExport,
                etCustomExportUrl = etCustomExportUrl,
                cbLocalExport = cbLocalExport,
                seekBarTextSize = seekBarTextSize
            )
        }

        applySettingsPageTheme(dialogView, selectedContrastMode)
        applySettingsChrome(selectedContrastMode)
        updateThemeButtonsSelection(dialogView, selectedContrastMode)
    }

    private fun updateThemeButtonsSelection(root: View, mode: ContrastMode) {
        val btnNormal = root.findViewById<Button>(R.id.btnThemeNormal) ?: return
        val btnDark = root.findViewById<Button>(R.id.btnThemeDark) ?: return
        val btnSepia = root.findViewById<Button>(R.id.btnThemeSepia) ?: return
        val btnHighContrast = root.findViewById<Button>(R.id.btnThemeHighContrast) ?: return

        val activeBorderColor = Color.parseColor("#007AFF")

        fun styleThemeOptionButton(
            button: Button,
            bg: Int,
            txt: Int,
            isSelected: Boolean,
            unselectedBorder: Int
        ) {
            val stroke = if (isSelected) activeBorderColor else unselectedBorder
            val strokeWidth = if (isSelected) 4 else 1
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 8f, root.resources.displayMetrics
                )
                setColor(bg)
                setStroke(strokeWidth, stroke)
            }
            button.backgroundTintList = null
            button.background = drawable
            button.setTextColor(txt)
        }

        styleThemeOptionButton(
            btnNormal,
            bg = Color.parseColor("#F8F9FA"),
            txt = Color.parseColor("#1C1C1E"),
            isSelected = (mode == ContrastMode.NORMAL),
            unselectedBorder = Color.parseColor("#D1D1D6")
        )

        styleThemeOptionButton(
            btnDark,
            bg = Color.parseColor("#1C1C1E"),
            txt = Color.parseColor("#FFFFFF"),
            isSelected = (mode == ContrastMode.DARK),
            unselectedBorder = Color.parseColor("#38383A")
        )

        styleThemeOptionButton(
            btnSepia,
            bg = Color.parseColor("#F4ECD8"),
            txt = Color.parseColor("#4A3B2C"),
            isSelected = (mode == ContrastMode.SEPIA),
            unselectedBorder = Color.parseColor("#DCD0BA")
        )

        styleThemeOptionButton(
            btnHighContrast,
            bg = Color.parseColor("#000000"),
            txt = Color.parseColor("#FFFFFF"),
            isSelected = (mode == ContrastMode.HIGH_CONTRAST),
            unselectedBorder = Color.parseColor("#48484A")
        )
    }

    private fun saveSettings(
        prefs: android.content.SharedPreferences,
        currentSettings: ReaderSettings,
        selectedContrastMode: ContrastMode,
        selectedLanguage: String,
        etServerUrl: EditText,
        etApiKey: EditText,
        switchPageTap: SwitchCompat,
        switchPageSwipe: SwitchCompat,
        switchPageAnimation: SwitchCompat,
        switchPageIndicator: SwitchCompat,
        switchAutoCheckUpdates: SwitchCompat,
        switchDailySummaryEmail: SwitchCompat,
        dailySummaryHour: Int,
        dailySummaryMinute: Int,
        etDailySummaryEmailTo: EditText,
        switchConvertChinese: SwitchCompat,
        cbCustomExport: SwitchCompat,
        etCustomExportUrl: EditText,
        cbLocalExport: SwitchCompat,
        seekBarTextSize: SeekBar
    ) {
        val newUrlRaw = etServerUrl.text.toString().trim()
        val newApiKey = etApiKey.text.toString().trim()
        val newPageTap = switchPageTap.isChecked
        val newPageSwipe = switchPageSwipe.isChecked
        val newPageAnimation = switchPageAnimation.isChecked
        val newShowPageIndicator = switchPageIndicator.isChecked
        val newAutoCheckUpdates = switchAutoCheckUpdates.isChecked
        val newDailySummaryEmailEnabled = switchDailySummaryEmail.isChecked
        val newDailySummaryEmailTo = etDailySummaryEmailTo.text.toString().trim()
        val newConvertChinese = switchConvertChinese.isChecked
        val useCustomExport = cbCustomExport.isChecked
        val customExportUrlRaw = etCustomExportUrl.text.toString().trim()
        val exportToLocal = cbLocalExport.isChecked
        val newTextSize = seekBarTextSize.progress + 50

        val normalizedBaseUrl = if (newUrlRaw.isNotEmpty()) normalizeUrl(newUrlRaw) else currentSettings.serverBaseUrl
        val normalizedCustomUrl = if (useCustomExport && customExportUrlRaw.isNotEmpty()) normalizeUrl(customExportUrlRaw) else ""

        if (newUrlRaw.isNotEmpty()) {
            TokenManager(applicationContext).saveCustomBackendUrl(normalizedBaseUrl)
        }

        if (newUrlRaw.isNotEmpty() && normalizedBaseUrl.isValidHttpUrl().not()) {
            Toast.makeText(this, "Server URL is invalid", Toast.LENGTH_SHORT).show()
            return
        }
        if (useCustomExport && customExportUrlRaw.isNotEmpty() && normalizedCustomUrl.isValidHttpUrl().not()) {
            Toast.makeText(this, "Custom export URL is invalid", Toast.LENGTH_SHORT).show()
            return
        }
        if (newDailySummaryEmailTo.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(newDailySummaryEmailTo).matches()) {
            Toast.makeText(this, getString(R.string.reader_settings_invalid_email), Toast.LENGTH_SHORT).show()
            return
        }

        val updatedSettings = currentSettings.copy(
            serverBaseUrl = normalizedBaseUrl,
            apiKey = newApiKey,
            pageTapEnabled = newPageTap,
            pageSwipeEnabled = newPageSwipe,
            pageAnimationEnabled = newPageAnimation,
            showPageIndicator = newShowPageIndicator,
            autoCheckUpdates = newAutoCheckUpdates,
            dailySummaryEmailEnabled = newDailySummaryEmailEnabled,
            dailySummaryEmailHour = dailySummaryHour.coerceIn(0, 23),
            dailySummaryEmailMinute = dailySummaryMinute.coerceIn(0, 59),
            dailySummaryEmailTo = newDailySummaryEmailTo,
            convertToTraditionalChinese = newConvertChinese,
            exportToCustomUrl = useCustomExport,
            exportCustomUrl = normalizedCustomUrl,
            exportToLocalDownloads = exportToLocal,
            textSize = newTextSize,
            contrastMode = selectedContrastMode.ordinal,
            language = selectedLanguage,
            updatedAt = System.currentTimeMillis()
        )

        updatedSettings.saveTo(SharedPreferencesStorage(prefs))
        DailySummaryEmailScheduler.schedule(this, updatedSettings, forceUpdate = true)
        pushSettingsToCloud()

        if (updatedSettings.language != currentSettings.language) {
            val intent = Intent(applicationContext, my.hinoki.booxreader.ui.welcome.WelcomeActivity::class.java)
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

    private fun formatTimeOfDay(hour: Int, minute: Int): String {
        return String.format("%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    private fun applySettingsPageTheme(root: View, mode: ContrastMode) {
        val backgroundColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#FAF9F6")
            ContrastMode.DARK -> Color.parseColor("#121212")
            ContrastMode.SEPIA -> Color.parseColor("#F2E7D0")
            ContrastMode.HIGH_CONTRAST -> Color.BLACK
        }
        val textColor = when (mode) {
            ContrastMode.NORMAL -> Color.BLACK
            ContrastMode.DARK -> Color.parseColor("#F2F5FA")
            ContrastMode.SEPIA -> Color.parseColor("#5B4636")
            ContrastMode.HIGH_CONTRAST -> Color.WHITE
        }
        val hintColor = ColorUtils.setAlphaComponent(
            textColor,
            if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 190 else 140
        )
        val dividerColor = ColorUtils.setAlphaComponent(
            textColor,
            if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 90 else 60
        )
        val isDarkMode = mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST
        val secondaryFill = ColorUtils.blendARGB(backgroundColor, textColor, if (isDarkMode) 0.22f else 0.11f)
        val secondaryStyle = buttonStyle(
            fillColor = secondaryFill,
            textColor = when (mode) {
                ContrastMode.NORMAL -> Color.parseColor("#16324F")
                else -> textColor
            },
            backgroundColor = backgroundColor,
            darkMode = isDarkMode,
            strokeColor = ColorUtils.setAlphaComponent(textColor, if (isDarkMode) 80 else 56)
        )

        root.setBackgroundColor(backgroundColor)

        val cardBgColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#FFFFFF")
            ContrastMode.DARK -> Color.parseColor("#1E1E1E")
            ContrastMode.SEPIA -> Color.parseColor("#F7EFE0")
            ContrastMode.HIGH_CONTRAST -> Color.parseColor("#121212")
        }

        fun applyToView(view: View) {
            if (view is com.google.android.material.card.MaterialCardView) {
                view.setCardBackgroundColor(cardBgColor)
                view.strokeColor = dividerColor
            } else if (view is ViewGroup &&
                view !is com.google.android.material.textfield.TextInputLayout &&
                view.background != null
            ) {
                view.setBackgroundColor(backgroundColor)
            }
            when (view) {
                is EditText -> {
                    view.setTextColor(textColor)
                    view.setHintTextColor(hintColor)
                }
                is Button -> {
                    if (isPrimarySettingsContentButton(view)) {
                        applyButtonStyle(view, primarySettingsButtonStyle(mode))
                    } else if (!isThemeChoiceButton(view)) {
                        applyButtonStyle(view, secondaryStyle)
                    }
                }
                is RadioButton -> {
                    view.setTextColor(textColor)
                    view.buttonTintList = ColorStateList.valueOf(textColor)
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
                is ImageView -> {
                    when (view.tag) {
                        "settings_icon" -> {
                            // Section header icon: tinted circle + icon
                            val circleColor =
                                ColorUtils.blendARGB(
                                    backgroundColor,
                                    textColor,
                                    if (isDarkMode) 0.22f else 0.12f
                                )
                            val iconColor =
                                ColorUtils.setAlphaComponent(
                                    textColor,
                                    if (isDarkMode) 230 else 200
                                )
                            view.backgroundTintList = ColorStateList.valueOf(circleColor)
                            view.imageTintList = ColorStateList.valueOf(iconColor)
                        }
                        "check_icon" -> {
                            // Language selection checkmark
                            view.imageTintList = ColorStateList.valueOf(textColor)
                        }
                        else -> { /* leave default icons untouched */ }
                    }
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

    private fun isThemeChoiceButton(button: Button): Boolean {
        return when (button.id) {
            R.id.btnThemeNormal,
            R.id.btnThemeDark,
            R.id.btnThemeSepia,
            R.id.btnThemeHighContrast -> true
            else -> false
        }
    }

    private fun isPrimarySettingsContentButton(button: Button): Boolean {
        return when (button.id) {
            R.id.btnManageMagicTags,
            R.id.btnTestExportEndpoint -> true
            else -> false
        }
    }

    private fun primarySettingsButtonStyle(mode: ContrastMode): ButtonVisualStyle {
        val backgroundColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#FAF9F6")
            ContrastMode.DARK -> Color.parseColor("#121212")
            ContrastMode.SEPIA -> Color.parseColor("#F2E7D0")
            ContrastMode.HIGH_CONTRAST -> Color.BLACK
        }
        val accentColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#3F6FA8")
            ContrastMode.DARK -> Color.parseColor("#86AEEA")
            ContrastMode.SEPIA -> Color.parseColor("#8A6740")
            ContrastMode.HIGH_CONTRAST -> Color.parseColor("#F2F2F2")
        }
        val darkMode = mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST
        val textColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#F8FBFF")
            else -> if (ColorUtils.calculateLuminance(accentColor) > 0.5) Color.BLACK else Color.WHITE
        }
        return buttonStyle(
            fillColor = accentColor,
            textColor = textColor,
            backgroundColor = backgroundColor,
            darkMode = darkMode
        )
    }

    private fun pushSettingsToCloud() {
        val prefs = getSharedPreferences(ReaderActivity.PREFS_NAME, MODE_PRIVATE)
        lifecycleScope.launch { syncRepo.pushSettings(ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))) }
    }

    private fun applyFooterInsets() {
        val root = findViewById<View>(R.id.readerSettingsRoot)
        val footer = findViewById<View>(R.id.settingsFooter)
        val toolbar = findViewById<View>(R.id.toolbarSettings)
        val baseBottom = footer.paddingBottom
        val baseTopToolbar = toolbar?.paddingTop ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            footer.updatePadding(bottom = baseBottom + systemBars.bottom)
            toolbar?.updatePadding(top = baseTopToolbar + systemBars.top)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySettingsChrome(mode: ContrastMode) {
        val pageColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#F8F9FB")
            ContrastMode.DARK -> Color.parseColor("#0B0E13")
            ContrastMode.SEPIA -> Color.parseColor("#F0E6D3")
            ContrastMode.HIGH_CONTRAST -> Color.BLACK
        }
        val barColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#EEF1F5")
            ContrastMode.DARK -> Color.parseColor("#0A0D12")
            ContrastMode.SEPIA -> Color.parseColor("#E7D9BF")
            ContrastMode.HIGH_CONTRAST -> Color.BLACK
        }
        val footerColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#F2F4F8")
            ContrastMode.DARK -> Color.parseColor("#0E1217")
            ContrastMode.SEPIA -> Color.parseColor("#EBDDCA")
            ContrastMode.HIGH_CONTRAST -> Color.BLACK
        }
        val dividerColor = when (mode) {
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
        val backgroundColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#FAF9F6")
            ContrastMode.DARK -> Color.parseColor("#121212")
            ContrastMode.SEPIA -> Color.parseColor("#F2E7D0")
            ContrastMode.HIGH_CONTRAST -> Color.BLACK
        }
        val textColor = when (mode) {
            ContrastMode.NORMAL -> Color.BLACK
            ContrastMode.DARK -> Color.parseColor("#F2F5FA")
            ContrastMode.SEPIA -> Color.parseColor("#5B4636")
            ContrastMode.HIGH_CONTRAST -> Color.WHITE
        }
        val isDarkMode = mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST
        val accentColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#3F6FA8")
            ContrastMode.DARK -> Color.parseColor("#86AEEA")
            ContrastMode.SEPIA -> Color.parseColor("#8A6740")
            ContrastMode.HIGH_CONTRAST -> Color.parseColor("#F2F2F2")
        }
        val primaryTextColor = when (mode) {
            ContrastMode.NORMAL -> Color.parseColor("#F8FBFF")
            else -> if (ColorUtils.calculateLuminance(accentColor) > 0.5) Color.BLACK else Color.WHITE
        }
        val primaryStyle = buttonStyle(
            fillColor = accentColor,
            textColor = primaryTextColor,
            backgroundColor = backgroundColor,
            darkMode = isDarkMode
        )
        val secondaryFill = ColorUtils.blendARGB(backgroundColor, textColor, if (isDarkMode) 0.22f else 0.11f)
        val secondaryStyle = buttonStyle(
            fillColor = secondaryFill,
            textColor = when (mode) {
                ContrastMode.NORMAL -> Color.parseColor("#16324F")
                else -> textColor
            },
            backgroundColor = backgroundColor,
            darkMode = isDarkMode,
            strokeColor = ColorUtils.setAlphaComponent(textColor, if (isDarkMode) 80 else 56)
        )
        applyButtonStyle(cancel, secondaryStyle)
        applyButtonStyle(save, primaryStyle)
    }

    private fun buttonStyle(
        fillColor: Int,
        textColor: Int,
        backgroundColor: Int,
        darkMode: Boolean,
        strokeColor: Int = Color.TRANSPARENT
    ): ButtonVisualStyle {
        val pressedFillColor = if (darkMode) {
            ColorUtils.blendARGB(fillColor, Color.WHITE, 0.10f)
        } else {
            ColorUtils.blendARGB(fillColor, Color.BLACK, 0.10f)
        }
        val disabledFillColor = ColorUtils.blendARGB(fillColor, backgroundColor, 0.55f)
        val disabledTextColor = ColorUtils.setAlphaComponent(textColor, if (darkMode) 160 else 140)
        return ButtonVisualStyle(
            fillColor = fillColor,
            pressedFillColor = pressedFillColor,
            disabledFillColor = disabledFillColor,
            strokeColor = strokeColor,
            textColor = textColor,
            disabledTextColor = disabledTextColor
        )
    }

    private fun setupDailySummary(
        switchDailySummaryEmail: SwitchCompat,
        tvDailySummaryTimeValue: TextView,
        btnDailySummaryPickTime: Button,
        etDailySummaryEmailTo: EditText
    ) {
        tvDailySummaryTimeValue.text = formatTimeOfDay(selectedDailySummaryHour, selectedDailySummaryMinute)

        fun applyDailySummaryControlsState() {
            val enabled = switchDailySummaryEmail.isChecked
            tvDailySummaryTimeValue.alpha = if (enabled) 1f else 0.6f
            btnDailySummaryPickTime.isEnabled = enabled
            etDailySummaryEmailTo.isEnabled = enabled
        }
        applyDailySummaryControlsState()

        switchDailySummaryEmail.setOnCheckedChangeListener { _, _ ->
            applyDailySummaryControlsState()
        }
        btnDailySummaryPickTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedDailySummaryHour = hourOfDay
                    selectedDailySummaryMinute = minute
                    tvDailySummaryTimeValue.text = formatTimeOfDay(selectedDailySummaryHour, selectedDailySummaryMinute)
                },
                selectedDailySummaryHour,
                selectedDailySummaryMinute,
                true
            ).show()
        }
    }

    private fun setupExportTest(
        cbCustomExport: SwitchCompat,
        etCustomExportUrl: EditText,
        btnTestExport: Button,
        etServerUrl: EditText,
        readerSettings: ReaderSettings
    ) {
        cbCustomExport.setOnCheckedChangeListener { _, isChecked ->
            etCustomExportUrl.isEnabled = isChecked
        }

        btnTestExport.setOnClickListener {
            val app = application as BooxReaderApp
            val repo = createAiNoteRepository(app, syncRepo)
            val baseUrl = etServerUrl.text.toString().trim().ifEmpty { readerSettings.serverBaseUrl }
            val targetUrl = if (cbCustomExport.isChecked && etCustomExportUrl.text.toString().trim().isNotEmpty()) {
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
                Toast.makeText(this@ReaderSettingsActivity, "Export test: $result", Toast.LENGTH_LONG).show()
                btnTestExport.text = originalText
                btnTestExport.isEnabled = true
            }
        }
    }

    private fun setupGeneralPreferences(
        readerSettings: ReaderSettings,
        prefs: android.content.SharedPreferences,
        etServerUrl: EditText,
        etApiKey: EditText,
        switchPageTap: SwitchCompat,
        switchPageSwipe: SwitchCompat,
        switchPageAnimation: SwitchCompat,
        switchPageIndicator: SwitchCompat,
        switchAutoCheckUpdates: SwitchCompat,
        switchDailySummaryEmail: SwitchCompat,
        etDailySummaryEmailTo: EditText,
        switchConvertChinese: SwitchCompat,
        cbCustomExport: SwitchCompat,
        etCustomExportUrl: EditText,
        cbLocalExport: SwitchCompat,
        seekBarTextSize: SeekBar,
        tvTextSizeValue: TextView
    ) {
        etServerUrl.setText(readerSettings.serverBaseUrl)
        etApiKey.setText(readerSettings.apiKey)
        switchPageTap.isChecked = readerSettings.pageTapEnabled
        switchPageSwipe.isChecked = readerSettings.pageSwipeEnabled
        switchPageAnimation.isChecked = readerSettings.pageAnimationEnabled
        switchPageIndicator.isChecked = readerSettings.showPageIndicator
        switchAutoCheckUpdates.isChecked = readerSettings.autoCheckUpdates
        switchDailySummaryEmail.isChecked = readerSettings.dailySummaryEmailEnabled
        etDailySummaryEmailTo.setText(readerSettings.dailySummaryEmailTo)
        switchConvertChinese.isChecked = readerSettings.convertToTraditionalChinese
        cbCustomExport.isChecked = readerSettings.exportToCustomUrl
        etCustomExportUrl.setText(readerSettings.exportCustomUrl)
        etCustomExportUrl.isEnabled = readerSettings.exportToCustomUrl
        cbLocalExport.isChecked = readerSettings.exportToLocalDownloads
        seekBarTextSize.progress = readerSettings.textSize - 50
        tvTextSizeValue.text = "${readerSettings.textSize}%"

        seekBarTextSize.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvTextSizeValue.text = "${progress + 50}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        switchConvertChinese.setOnCheckedChangeListener { _, isChecked ->
            val currentSettings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
            val updatedSettings = currentSettings.copy(
                convertToTraditionalChinese = isChecked,
                updatedAt = System.currentTimeMillis()
            )
            updatedSettings.saveTo(SharedPreferencesStorage(prefs))
            setResult(RESULT_OK)
            val message = if (isChecked) "已啟用簡體轉繁體" else "已停用簡體轉繁體"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyButtonStyle(button: Button, style: ButtonVisualStyle) {
        val normal = createRoundedBackground(style.fillColor, style.strokeColor)
        val pressed = createRoundedBackground(style.pressedFillColor, style.strokeColor)
        val disabled = createRoundedBackground(style.disabledFillColor, style.strokeColor)
        button.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_focused), pressed)
            addState(intArrayOf(), normal)
        }
        button.setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf()
                ),
                intArrayOf(style.disabledTextColor, style.textColor)
            )
        )
    }

    private fun createRoundedBackground(
        fillColor: Int,
        strokeColor: Int,
        cornerRadiusDp: Float = 14f
    ): GradientDrawable {
        val strokeWidthPx = (resources.displayMetrics.density * 1f).toInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * cornerRadiusDp
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun applyActionBarContentColor(barColor: Int) {
        val title = getString(R.string.reader_settings_title)
        val contentColor = if (ColorUtils.calculateLuminance(barColor) > 0.5) Color.BLACK else Color.WHITE

        val styledTitle = SpannableString(title).apply {
            setSpan(ForegroundColorSpan(contentColor), 0, length, 0)
        }
        supportActionBar?.title = styledTitle

        val backDrawable = AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
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
        val settings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))

        val dialog = MagicTagManagerDialog(this, settings.magicTags) { updatedTags ->
            val updatedSettings = settings.copy(
                magicTags = updatedTags,
                updatedAt = System.currentTimeMillis()
            )
            updatedSettings.saveTo(SharedPreferencesStorage(prefs))
            pushSettingsToCloud()
            setResult(RESULT_OK)
        }
        dialog.show()
    }
}
