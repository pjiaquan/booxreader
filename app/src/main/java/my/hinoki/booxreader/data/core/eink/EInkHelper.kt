package my.hinoki.booxreader.core.eink

import android.os.Build
import android.view.View
import android.view.ViewGroup

object EInkHelper {

    // 文石設備型號檢測
    fun isBoox(): Boolean {
        return Build.MANUFACTURER.equals("ONYX", ignoreCase = true) ||
               Build.BRAND.equals("boox", ignoreCase = true) ||
               Build.MODEL.contains("BOOX", ignoreCase = true)
    }

    fun isBooxDevice(): Boolean = isBoox()

    // 獲取設備型號用於特定優化
    fun getBooxModel(): String {
        return if (isBoox()) {
            Build.MODEL.uppercase()
        } else {
            "UNKNOWN"
        }
    }

    // 檢查是否為新型號文石設備（支援更好刷新模式）
    fun isModernBoox(): Boolean {
        val model = getBooxModel()
        return model.startsWith("BOOX") && (
            model.contains("NOTE AIR") ||
            model.contains("PAGE") ||
            model.contains("LEAF") ||
            model.contains("KON-TIKI") ||
            model.contains("FAOLI") ||
            model.contains("PALMA") ||
            model.contains("TAB X") ||
            model.contains("TAB ULTRA")
        )
    }

    // 基本刷新功能
    fun refresh(view: View, full: Boolean = false) {
        if (!isBoox()) return
        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val method = cls.getMethod("invalidate", View::class.java, Boolean::class.java)
            method.invoke(null, view, full)
        } catch (e: Exception) {
            // fallback for newer SDKs
            tryAlternativeRefresh(view, full)
        }
    }

    // 新型號刷新功能備用方案
    private fun tryAlternativeRefresh(view: View, full: Boolean = false) {
        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val method = cls.getMethod("requestRefresh", View::class.java, Int::class.java)
            val mode = if (full) 3 else 1 // 3 = FULL, 1 = PARTIAL
            method.invoke(null, view, mode)
        } catch (e: Exception) {
            // ignore
        }
    }

    // 局部刷新（針對文字選取、頁面翻轉等）
    fun refreshPartial(view: View) {
        if (!isBoox()) return
        refresh(view, full = false)
    }

    // 全屏刷新（針對切換章節、打開書籍等）
    fun refreshFull(view: View) {
        if (!isBoox()) return
        refresh(view, full = true)
    }

    // 智能刷新：根據內容變化選擇刷新模式
    fun smartRefresh(view: View, hasTextChanges: Boolean = false, hasImageChanges: Boolean = false) {
        if (!isBoox()) return

        when {
            hasImageChanges || hasTextChanges -> refreshFull(view)
            else -> refreshPartial(view)
        }
    }

    /**
     * A2 模式 - 快速黑白模式（適合滑動、拖動、選取）
     * 最快但只有黑白，適合暫時的快速交互
     */
    fun enableFastMode(view: View) {
        if (!isBoox()) return
        setEpdsMode(view, "EPD_A2")
    }

    /**
     * AUTO 模式 - 自動模式（適合一般閱讀）
     * 在速度和品質之間平衡
     */
    fun enableAutoMode(view: View) {
        if (!isBoox()) return
        setEpdsMode(view, "EPD_AUTO")
    }

    /**
     * REGAL 模式 - 高品質模式（適合靜態文字閱讀）
     * 最清晰的文字顯示，速度較慢
     */
    fun restoreQualityMode(view: View) {
        if (!isBoox()) return
        val mode = if (isModernBoox()) "EPD_REGAL" else "EPD_TEXT"
        setEpdsMode(view, mode)
    }

    /**
     * DU 模式 - 局部刷新模式（適合小範圍更新）
     */
    fun enableDUMode(view: View) {
        if (!isBoox()) return
        setEpdsMode(view, "EPD_DU")
    }

    /**
     * GL16 模式 - 16級灰度模式（適合圖片顯示）
     */
    fun enableGL16Mode(view: View) {
        if (!isBoox()) return
        setEpdsMode(view, "EPD_GL16")
    }

    // 設置刷新模式的核心方法
    private fun setEpdsMode(view: View, modeName: String) {
        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")

            // 嘗試獲取模式常量
            val modeField = try {
                cls.getField(modeName)
            } catch (e: NoSuchFieldException) {
                // 備用模式映射
                getFallbackMode(cls, modeName)
            }

            if (modeField != null) {
                val modeValue = modeField.get(null)

                // 尋找合適的 setMode 方法
                val setModeMethod = cls.methods.find {
                    it.name == "setMode" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == View::class.java
                }

                setModeMethod?.invoke(null, view, modeValue)
            }
        } catch (e: Exception) {
            // 忽略錯誤，確保在非文石設備上不會崩潰
        }
    }

    // 備用模式映射
    private fun getFallbackMode(cls: Class<*>, modeName: String): java.lang.reflect.Field? {
        return try {
            when (modeName) {
                "EPD_REGAL" -> cls.getField("EPD_TEXT")
                "EPD_AUTO" -> cls.getField("EPD_Du") // 某些舊型號使用 Du 作為自動模式
                "EPD_DU" -> cls.getField("EPD_Du")
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // 為特定區域設置刷新（優化性能）
    fun refreshRegion(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (!isBoox()) return
        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val method = cls.getMethod(
                "invalidate",
                View::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Boolean::class.java
            )
            method.invoke(null, view, left, top, right, bottom, false)
        } catch (e: Exception) {
            // fallback to normal refresh
            refresh(view, false)
        }
    }

    // 禁用觸摸反饋以減少干擾
    fun optimizeForEInk(view: View) {
        if (!isBoox()) return

        view.isHapticFeedbackEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                optimizeForEInk(view.getChildAt(i))
            }
        }
    }

    // 等待刷新完成
    fun waitForRefresh(view: View) {
        if (!isBoox()) return
        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val method = cls.getMethod("waitForUpdateComplete", View::class.java)
            method.invoke(null, view)
        } catch (e: Exception) {
            // 忽略錯誤
        }
    }

    // 獲取當前刷新模式
    fun getCurrentRefreshMode(view: View): String? {
        if (!isBoox()) return null
        return try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val method = cls.getMethod("getMode", View::class.java)
            method.invoke(null, view).toString()
        } catch (e: Exception) {
            null
        }
    }

    // ========== 高對比模式功能 ==========

    enum class ContrastMode {
        NORMAL,     // 正常模式：白底黑字
        DARK,       // 深色模式：黑底白字
        SEPIA,      // 褐色模式：米褐色背景深色字
        HIGH_CONTRAST // 高對比模式：極強對比度
    }

    private var currentContrastMode = ContrastMode.NORMAL

    // 檢查是否支援高對比模式
    fun supportsHighContrast(): Boolean {
        return isBoox() && isModernBoox()
    }

    // 設置高對比模式
    fun setHighContrastMode(view: View?, mode: ContrastMode) {
        if (!supportsHighContrast() || view == null) return

        currentContrastMode = mode
        applyContrastMode(view, mode)
    }

    // 獲取當前對比模式
    fun getCurrentContrastMode(): ContrastMode = currentContrastMode

    // 應用對比模式到 WebView
    private fun applyContrastMode(view: View, mode: ContrastMode) {
        if (view is android.webkit.WebView) {
            val css = when (mode) {
                ContrastMode.NORMAL -> generateNormalCSS()
                ContrastMode.DARK -> generateDarkCSS()
                ContrastMode.SEPIA -> generateSepiaCSS()
                ContrastMode.HIGH_CONTRAST -> generateHighContrastCSS()
            }

            val js = """
                (function() {
                    try {
                        // 移除舊的主題樣式
                        var oldThemeStyle = document.getElementById('boox-contrast-theme');
                        if (oldThemeStyle) {
                            oldThemeStyle.remove();
                        }

                        // 添加新的主題樣式
                        var style = document.createElement('style');
                        style.id = 'boox-contrast-theme';
                        style.textContent = `${css}`;
                        document.head.appendChild(style);

                        // 強制重排以應用樣式
                        document.body.style.transition = 'none';
                        document.body.offsetHeight; // 觸發重排
                        document.body.style.transition = '';

                        console.log('Applied contrast mode: ${mode.name}');
                    } catch (e) {
                        console.log('Error applying contrast mode:', e);
                    }
                })();
            """.trimIndent()

            view.evaluateJavascript(js, null)

            // 如果是深色模式，可能需要調整刷新策略
            if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) {
                // 深色模式使用更積極的刷新
                view.postDelayed({
                    refreshFull(view)
                }, 100)
            }
        }

        // 遞歸應用到子視圖
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyContrastMode(view.getChildAt(i), mode)
            }
        }
    }

    // 生成正常模式 CSS
    private fun generateNormalCSS(): String {
        return """
            /* Normal mode - White background, black text */
            html, body {
                background-color: #ffffff !important;
                color: #000000 !important;
            }

            * {
                background-color: inherit !important;
                color: inherit !important;
            }

            p, div, span, li, h1, h2, h3, h4, h5, h6 {
                background-color: transparent !important;
                color: #000000 !important;
            }

            a {
                color: #0000ff !important;
            }

            /* 保持圖片原色 */
            img {
                filter: none !important;
                -webkit-filter: none !important;
            }
        """
    }

    // 生成深色模式 CSS
    private fun generateDarkCSS(): String {
        return """
            /* Dark mode - Black background, white text */
            html, body {
                background-color: #000000 !important;
                color: #ffffff !important;
            }

            * {
                background-color: inherit !important;
                color: inherit !important;
            }

            p, div, span, li, h1, h2, h3, h4, h5, h6 {
                background-color: transparent !important;
                color: #ffffff !important;
            }

            a {
                color: #4da6ff !important;
                text-decoration: underline !important;
            }

            /* 反轉圖片以適應深色主題 */
            img {
                filter: invert(1) hue-rotate(180deg) !important;
                -webkit-filter: invert(1) hue-rotate(180deg) !important;
            }

            /* 邊框調整 */
            table, td, th {
                border-color: #666666 !important;
            }

            /* 程式碼區塊 */
            pre, code {
                background-color: #1a1a1a !important;
                color: #00ff00 !important;
                border: 1px solid #666666 !important;
            }
        """
    }

    // 生成褐色模式 CSS
    private fun generateSepiaCSS(): String {
        return """
            /* Sepia mode - Warm brown background */
            html, body {
                background-color: #f4ecd8 !important;
                color: #5c4b37 !important;
            }

            * {
                background-color: inherit !important;
                color: inherit !important;
            }

            p, div, span, li, h1, h2, h3, h4, h5, h6 {
                background-color: transparent !important;
                color: #5c4b37 !important;
            }

            a {
                color: #8b4513 !important;
                text-decoration: underline !important;
            }

            /* 輕微調整圖片色調 */
            img {
                filter: sepia(0.3) !important;
                -webkit-filter: sepia(0.3) !important;
            }

            /* 表格邊框 */
            table, td, th {
                border-color: #d2c8b1 !important;
            }
        """
    }

    // 生成高對比模式 CSS
    private fun generateHighContrastCSS(): String {
        return """
            /* High contrast mode - Maximum contrast */
            html, body {
                background-color: #000000 !important;
                color: #ffffff !important;
            }

            * {
                background-color: inherit !important;
                color: inherit !important;
            }

            p, div, span, li, h1, h2, h3, h4, h5, h6 {
                background-color: transparent !important;
                color: #ffffff !important;
                text-shadow: 1px 1px 2px rgba(0,0,0,0.8) !important;
                font-weight: 500 !important;
            }

            a {
                color: #ffff00 !important;
                text-decoration: underline !important;
                text-shadow: 1px 1px 2px rgba(0,0,0,0.8) !important;
            }

            /* 標題更粗 */
            h1, h2, h3, h4, h5, h6 {
                font-weight: 700 !important;
                color: #00ff00 !important;
            }

            /* 反轉圖片並增強對比 */
            img {
                filter: invert(1) contrast(1.5) !important;
                -webkit-filter: invert(1) contrast(1.5) !important;
            }

            /* 強烈邊框 */
            table, td, th {
                border-color: #ffffff !important;
                border-width: 2px !important;
            }

            /* 程式碼區塊高對比 */
            pre, code {
                background-color: #000000 !important;
                color: #00ff00 !important;
                border: 2px solid #ffffff !important;
                font-weight: bold !important;
            }

            /* 引用區塊 */
            blockquote {
                border-left: 4px solid #ffffff !important;
                background-color: #111111 !important;
            }

            /* 選取文字時的高對比 */
            ::selection {
                background-color: #ffffff !important;
                color: #000000 !important;
            }

            ::-moz-selection {
                background-color: #ffffff !important;
                color: #000000 !important;
            }
        """
    }

    // 切換到下一個對比模式
    fun toggleContrastMode(view: View?): ContrastMode {
        val nextMode = when (currentContrastMode) {
            ContrastMode.NORMAL -> ContrastMode.DARK
            ContrastMode.DARK -> ContrastMode.SEPIA
            ContrastMode.SEPIA -> ContrastMode.HIGH_CONTRAST
            ContrastMode.HIGH_CONTRAST -> ContrastMode.NORMAL
        }

        setHighContrastMode(view, nextMode)
        return nextMode
    }

    // 獲取對比模式的名稱（用於 UI 顯示）
    fun getContrastModeName(mode: ContrastMode): String {
        return when (mode) {
            ContrastMode.NORMAL -> "正常模式"
            ContrastMode.DARK -> "深色模式"
            ContrastMode.SEPIA -> "褐色模式"
            ContrastMode.HIGH_CONTRAST -> "高對比模式"
        }
    }
}

