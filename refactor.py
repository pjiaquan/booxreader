import re

with open('app/src/main/java/my/hinoki/booxreader/ui/reader/ReaderActivity.kt', 'r') as f:
    content = f.read()

# We need to replace the showSettingsDialog method with a refactored version
# and add the helper methods.

new_methods = """
    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reader_settings, null)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val readerSettings = ReaderSettings.fromPrefs(prefs)

        setupDialogLayout(dialogView)
        val (rbSystem, rbEnglish, rbChinese) = setupLanguageSection(dialogView, readerSettings)

        populateSettingsViews(dialogView, readerSettings)
        setupListeners(dialogView, prefs, readerSettings)

        val contrastMode =
                ContrastMode.values().getOrNull(readerSettings.contrastMode) ?: ContrastMode.NORMAL
        applySettingsDialogTheme(dialogView, contrastMode)

        val dialog =
                AlertDialog.Builder(this)
                        .setView(dialogView)
                        .setPositiveButton("Close", null)
                        .create()

        setupDialogSaveAction(dialog, dialogView, prefs, rbChinese, rbEnglish)

        val btnAiProfiles = Button(this).apply { text = "AI Profiles (Switch Model/API)" }
        val layout = (dialogView as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.LinearLayout
        layout?.addView(btnAiProfiles, 2)
        btnAiProfiles.setOnClickListener {
            dialog.dismiss()
            my.hinoki.booxreader.ui.settings.AiProfileListActivity.open(this@ReaderActivity)
        }

        dialog.show()
    }

    private fun setupDialogLayout(dialogView: View) {
        val layout = (dialogView as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.LinearLayout
        setupThemeSection(layout)
    }

    private fun setupThemeSection(layout: android.widget.LinearLayout?) {
        val themeTitle =
                TextView(this).apply {
                    text = "Reading Theme / 閱讀主題"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                }
        layout?.addView(themeTitle, 3) // Based on original index logic

        val themeContainer =
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    weightSum = 3f
                    layoutParams =
                            android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                }

        val btnNormal = createThemeButton("Normal", ContrastMode.NORMAL)
        val btnDark = createThemeButton("Dark", ContrastMode.DARK)
        val btnSepia = createThemeButton("Sepia", ContrastMode.SEPIA)

        themeContainer.addView(btnNormal)
        themeContainer.addView(btnDark)
        themeContainer.addView(btnSepia)
        layout?.addView(themeContainer, 4)
    }

    private fun createThemeButton(title: String, mode: ContrastMode): Button {
        return Button(this).apply {
            text = title
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                applyContrastMode(mode)
                Toast.makeText(this@ReaderActivity, "$title Mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupLanguageSection(
        dialogView: View,
        readerSettings: ReaderSettings
    ): Triple<android.widget.RadioButton, android.widget.RadioButton, android.widget.RadioButton> {
        val layout = (dialogView as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.LinearLayout

        val languageTitle =
                TextView(this).apply {
                    text = "Language / 語言"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                }
        layout?.addView(languageTitle, 2)

        val languageGroup =
                android.widget.RadioGroup(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }

        val rbSystem = android.widget.RadioButton(this).apply { text = "System Default (跟隨系統)" }
        val rbEnglish = android.widget.RadioButton(this).apply { text = "English" }
        val rbChinese =
                android.widget.RadioButton(this).apply { text = "Traditional Chinese (繁體中文)" }

        languageGroup.addView(rbSystem)
        languageGroup.addView(rbEnglish)
        languageGroup.addView(rbChinese)
        layout?.addView(languageGroup, 3)

        when (readerSettings.language) {
            "zh" -> rbChinese.isChecked = true
            "en" -> rbEnglish.isChecked = true
            else -> rbSystem.isChecked = true
        }

        return Triple(rbSystem, rbEnglish, rbChinese)
    }

    private fun populateSettingsViews(dialogView: View, readerSettings: ReaderSettings) {
        dialogView.findViewById<EditText>(R.id.etServerUrl).setText(readerSettings.serverBaseUrl)
        dialogView.findViewById<EditText>(R.id.etApiKey).setText(readerSettings.apiKey)
        dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap).isChecked = readerSettings.pageTapEnabled
        dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageSwipe).isChecked = readerSettings.pageSwipeEnabled
        dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageAnimation).isChecked = readerSettings.pageAnimationEnabled
        dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageIndicator).isChecked = readerSettings.showPageIndicator
        dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchConvertChinese).isChecked = readerSettings.convertToTraditionalChinese

        val cbCustomExport = dialogView.findViewById<CheckBox>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)
        cbCustomExport.isChecked = readerSettings.exportToCustomUrl
        etCustomExportUrl.setText(readerSettings.exportCustomUrl)
        etCustomExportUrl.isEnabled = readerSettings.exportToCustomUrl

        dialogView.findViewById<CheckBox>(R.id.cbLocalExport).isChecked = readerSettings.exportToLocalDownloads

        val seekBarTextSize = dialogView.findViewById<android.widget.SeekBar>(R.id.seekBarTextSize)
        val tvTextSizeValue = dialogView.findViewById<android.widget.TextView>(R.id.tvTextSizeValue)
        seekBarTextSize.progress = readerSettings.textSize - 50
        tvTextSizeValue.text = "${readerSettings.textSize}%"
    }

    private fun setupListeners(dialogView: View, prefs: SharedPreferences, readerSettings: ReaderSettings) {
        dialogView.findViewById<Button>(R.id.btnManageMagicTags).setOnClickListener { showMagicTagManager() }
        dialogView.findViewById<Button>(R.id.btnSettingsAddBookmark).setOnClickListener { addBookmarkFromCurrentPosition() }
        dialogView.findViewById<Button>(R.id.btnSettingsShowBookmarks).setOnClickListener { openBookmarkList() }

        val switchPageTap = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap)
        val switchPageSwipe = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageSwipe)
        val switchPageAnimation = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageAnimation)
        val switchPageIndicator = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageIndicator)

        switchPageTap.setOnCheckedChangeListener { _, isChecked -> pageTapEnabled = isChecked }
        switchPageSwipe.setOnCheckedChangeListener { _, isChecked -> pageSwipeEnabled = isChecked }
        switchPageAnimation.setOnCheckedChangeListener { _, isChecked ->
            pageAnimationEnabled = isChecked
            nativeNavigatorFragment?.setPageAnimationEnabled(isChecked)
        }
        switchPageIndicator.setOnCheckedChangeListener { _, isChecked ->
            showPageIndicator = isChecked
            nativeNavigatorFragment?.setPageIndicatorVisible(isChecked)
        }

        val cbCustomExport = dialogView.findViewById<CheckBox>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)
        cbCustomExport.setOnCheckedChangeListener { _, isChecked ->
            etCustomExportUrl.isEnabled = isChecked
        }

        setupTextSizeSeekBar(dialogView)
        setupChineseConversionSwitch(dialogView, prefs)
        setupTestExportEndpoint(dialogView, readerSettings)
    }

    private fun setupTextSizeSeekBar(dialogView: View) {
        val seekBarTextSize = dialogView.findViewById<android.widget.SeekBar>(R.id.seekBarTextSize)
        val tvTextSizeValue = dialogView.findViewById<android.widget.TextView>(R.id.tvTextSizeValue)
        seekBarTextSize.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        val size = progress + 50
                        tvTextSizeValue.text = "$size%"
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                }
        )
    }

    private fun setupChineseConversionSwitch(dialogView: View, prefs: SharedPreferences) {
        val switchConvertChinese = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchConvertChinese)
        switchConvertChinese.setOnCheckedChangeListener { _, isChecked ->
            val currentSettings = ReaderSettings.fromPrefs(prefs)
            val updatedSettings = currentSettings.copy(
                    convertToTraditionalChinese = isChecked,
                    updatedAt = System.currentTimeMillis()
            )
            updatedSettings.saveTo(prefs)
            nativeNavigatorFragment?.setChineseConversionEnabled(isChecked)

            val message = if (isChecked) "已啟用簡體轉繁體" else "已停用簡體轉繁體"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            if (isChecked) {
                val probe = my.hinoki.booxreader.data.util.ChineseConverter.toTraditional("简体中文").toString()
                if (probe == "简体中文") {
                    Toast.makeText(this, "簡體轉繁體字庫載入失敗，可能無法轉換", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupTestExportEndpoint(dialogView: View, readerSettings: ReaderSettings) {
        val btnTestExport = dialogView.findViewById<Button>(R.id.btnTestExportEndpoint)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val cbCustomExport = dialogView.findViewById<CheckBox>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)

        btnTestExport.setOnClickListener {
            val app = application as my.hinoki.booxreader.BooxReaderApp
            val repo = AiNoteRepository(app, app.okHttpClient, syncRepo)
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
                Toast.makeText(this@ReaderActivity, "Export test: $result", Toast.LENGTH_LONG).show()
                btnTestExport.text = originalText
                btnTestExport.isEnabled = true
            }
        }
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

    private fun setupDialogSaveAction(
        dialog: AlertDialog,
        dialogView: View,
        prefs: SharedPreferences,
        rbChinese: android.widget.RadioButton,
        rbEnglish: android.widget.RadioButton
    ) {
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etApiKey)
        val switchPageTap = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap)
        val switchPageSwipe = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageSwipe)
        val switchPageAnimation = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageAnimation)
        val switchPageIndicator = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageIndicator)
        val switchConvertChinese = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchConvertChinese)
        val cbCustomExport = dialogView.findViewById<CheckBox>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)
        val cbLocalExport = dialogView.findViewById<CheckBox>(R.id.cbLocalExport)
        val seekBarTextSize = dialogView.findViewById<android.widget.SeekBar>(R.id.seekBarTextSize)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val currentSettings = ReaderSettings.fromPrefs(prefs)
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

                val normalizedBaseUrl = if (newUrlRaw.isNotEmpty()) normalizeUrl(newUrlRaw) else currentSettings.serverBaseUrl
                val normalizedCustomUrl = if (useCustomExport && customExportUrlRaw.isNotEmpty()) normalizeUrl(customExportUrlRaw) else ""

                if (newUrlRaw.isNotEmpty() && normalizedBaseUrl.toHttpUrlOrNull() == null) {
                    Toast.makeText(this, "Server URL is invalid", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (useCustomExport && customExportUrlRaw.isNotEmpty() && normalizedCustomUrl.toHttpUrlOrNull() == null) {
                    Toast.makeText(this, "Custom export URL is invalid", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val conversionChanged = newConvertChinese != currentSettings.convertToTraditionalChinese
                val updatedSettings = currentSettings.copy(
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
                        language = when {
                            rbChinese.isChecked -> "zh"
                            rbEnglish.isChecked -> "en"
                            else -> "system"
                        },
                        updatedAt = System.currentTimeMillis()
                )
                updatedSettings.saveTo(prefs)

                if (updatedSettings.language != currentSettings.language) {
                    val intent = Intent(applicationContext, my.hinoki.booxreader.ui.welcome.WelcomeActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    return@setOnClickListener
                }

                pageAnimationEnabled = updatedSettings.pageAnimationEnabled
                pageSwipeEnabled = updatedSettings.pageSwipeEnabled
                showPageIndicator = updatedSettings.showPageIndicator
                nativeNavigatorFragment?.setPageIndicatorVisible(showPageIndicator)

                applyFontSize(newTextSize)
                nativeNavigatorFragment?.setFontSize(newTextSize)

                if (conversionChanged) {
                    nativeNavigatorFragment?.setChineseConversionEnabled(newConvertChinese)
                    val message = if (newConvertChinese) "已啟用簡體轉繁體" else "已停用簡體轉繁體"
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }

                if (normalizedBaseUrl != currentSettings.serverBaseUrl) {
                    Toast.makeText(this, "Server URL updated", Toast.LENGTH_SHORT).show()
                }
                if (newApiKey != currentSettings.apiKey) {
                    Toast.makeText(this, "API Key updated", Toast.LENGTH_SHORT).show()
                }

                pushSettingsToCloud()
                dialog.dismiss()
            }
        }
    }
"""

# Let's replace the original showSettingsDialog with the new_methods

pattern = r"    private fun showSettingsDialog\(\) \{.*?(?=    private fun applySettingsDialogTheme)"

match = re.search(pattern, content, re.DOTALL)
if match:
    new_content = content[:match.start()] + new_methods + content[match.end():]
    with open('app/src/main/java/my/hinoki/booxreader/ui/reader/ReaderActivity.kt', 'w') as f:
        f.write(new_content)
    print("Success replacing!")
else:
    print("Could not find method to replace.")
