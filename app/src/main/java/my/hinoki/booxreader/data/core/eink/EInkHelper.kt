package my.hinoki.booxreader.core.eink

import android.os.Build
import android.view.View
import android.view.ViewGroup
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

object EInkHelper {

    // Always preserve system engine (Boox App Optimization)
    // No manual SDK calls will be made.
    @Volatile private var preserveSystemEngine: Boolean = false

    fun setPreserveSystemEngine(preserve: Boolean) {
        preserveSystemEngine = preserve
    }

    fun isPreservingSystemEngine(): Boolean = preserveSystemEngine

    // 文石設備型號檢測
    fun isBoox(): Boolean {
        return Build.MANUFACTURER.contains("ONYX", ignoreCase = true) ||
                Build.BRAND.contains("ONYX", ignoreCase = true) ||
                Build.BRAND.contains("boox", ignoreCase = true) ||
                Build.MODEL.contains("BOOX", ignoreCase = true) ||
                Build.MODEL.contains("ONYX", ignoreCase = true)
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

    // 檢查是否為新型號文石設備
    fun isModernBoox(): Boolean {
        if (!isBoox()) return false
        val model = getBooxModel()
        return (model.startsWith("BOOX") || model.contains("GO")) &&
                (model.contains("NOTE AIR") ||
                        model.contains("PAGE") ||
                        model.contains("LEAF") ||
                        model.contains("KON-TIKI") ||
                        model.contains("FAOLI") ||
                        model.contains("PALMA") ||
                        model.contains("TAB X") ||
                        model.contains("TAB ULTRA") ||
                        model.contains("COLOR") ||
                        model.contains("GO"))
    }

    // 基本刷新功能 - 只從事標準無效化，讓系統引擎接管
    fun refresh(view: View, full: Boolean = false) {
        if (!isBoox()) return
        if (preserveSystemEngine) {
            view.invalidate()
            view.postInvalidateOnAnimation()
            return
        }

        val mode = if (full) UpdateMode.GC else UpdateMode.GU
        EpdController.invalidate(view, mode)
    }

    // 局部刷新
    fun refreshPartial(view: View) {
        refresh(view, full = false)
    }

    // 全屏刷新
    fun refreshFull(view: View) {
        refresh(view, full = true)
    }

    // 智能刷新
    fun smartRefresh(
            view: View,
            @Suppress("UNUSED_PARAMETER") hasTextChanges: Boolean = false,
            hasImageChanges: Boolean = false
    ) {
        if (hasImageChanges) {
            refreshFull(view)
        } else {
            refreshPartial(view)
        }
    }

    /** Mode switching methods - now using Onyx SDK */
    fun enableFastMode(view: View) {
        if (!isBoox() || preserveSystemEngine) return
        EpdController.invalidate(view, UpdateMode.DU)
    }

    fun enableAutoMode(view: View) {
        if (!isBoox() || preserveSystemEngine) return
        // Auto usually means let the system decide or standard quality
        EpdController.invalidate(view, UpdateMode.GU)
    }

    fun restoreQualityMode(view: View) {
        if (!isBoox() || preserveSystemEngine) return
        EpdController.invalidate(view, UpdateMode.GC)
    }

    fun enableDUMode(view: View) {
        if (!isBoox() || preserveSystemEngine) return
        EpdController.invalidate(view, UpdateMode.DU)
    }

    fun enableGL16Mode(view: View) {
        if (!isBoox() || preserveSystemEngine) return
        // GL16 is distinct, often used for video or high speed animations
        // Defaulting to DU/A2 if GL16 assumption matches Animation mode
        EpdController.invalidate(view, UpdateMode.ANIMATION)
    }

    // 為特定區域設置刷新
    fun refreshRegion(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (!isBoox()) {
            view.invalidate()
            return
        }
        if (preserveSystemEngine) {
            view.invalidate()
            return
        }
        // Onyx EpdController handles view-based updates well.
        // For specific rect, simple view update is usually enough unless using specific rect API
        // which is complex.
        // We will default to full view update with fast mode for regions to ensure response.
        EpdController.invalidate(view, UpdateMode.DU)
    }

    // 禁用觸摸反饋以減少干擾
    fun optimizeForEInk(view: View) {
        if (!isBoox()) return

        view.isHapticFeedbackEnabled = false
        // Keep standard overscroll behavior or let activity decide
        // view.overScrollMode = View.OVER_SCROLL_NEVER

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                optimizeForEInk(view.getChildAt(i))
            }
        }
    }

    // 等待刷新完成 - NO-OP
    fun waitForRefresh(@Suppress("UNUSED_PARAMETER") view: View) {
        // SDK doesn't expose synchronous wait easily.
    }

    // 獲取當前刷新模式
    fun getCurrentRefreshMode(@Suppress("UNUSED_PARAMETER") view: View): String? {
        // Since we are using SDK requests, we don't hold state here.
        return "Onyx SDK Controlled"
    }

    // ========== 高對比模式功能 ==========

    enum class ContrastMode {
        NORMAL, // 正常模式：白底黑字
        DARK, // 深色模式：黑底白字
        SEPIA, // 褐色模式：米褐色背景深色字
        HIGH_CONTRAST // 高對比模式：極強對比度
    }

    private var currentContrastMode = ContrastMode.NORMAL

    // 檢查是否支援高對比模式
    fun supportsHighContrast(): Boolean {
        return isBoox() && isModernBoox()
    }

    // 設置高對比模式
    fun setHighContrastMode(view: View?, mode: ContrastMode) {
        currentContrastMode = mode
        if (view != null) {
            applyContrastMode(view, mode)
        }
    }

    // 獲取當前對比模式
    fun getCurrentContrastMode(): ContrastMode = currentContrastMode

    // 應用對比模式到 WebView
    private fun applyContrastMode(view: View, mode: ContrastMode) {
        if (view is android.webkit.WebView) {
            val css =
                    when (mode) {
                        ContrastMode.NORMAL -> generateNormalCSS()
                        ContrastMode.DARK -> generateDarkCSS()
                        ContrastMode.SEPIA -> generateSepiaCSS()
                        ContrastMode.HIGH_CONTRAST -> generateHighContrastCSS()
                    }

            val js =
                    """
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
                view.postDelayed({ refreshFull(view) }, 100)
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
            /* Normal mode - Soft off-white background, deep gray text */
            html, body {
                background-color: #FAF9F6 !important;
                color: #1A1A1A !important;
            }

            * {
                background-color: inherit !important;
                color: inherit !important;
            }

            p, div, span, li, h1, h2, h3, h4, h5, h6 {
                background-color: transparent !important;
                color: #1A1A1A !important;
            }

            a {
                color: #0066CC !important;
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
            /* Dark mode - Deep charcoal background, soft gray text */
            html, body {
                background-color: #121212 !important;
                color: #BDBDBD !important;
            }

            * {
                background-color: inherit !important;
                color: inherit !important;
            }

            p, div, span, li, h1, h2, h3, h4, h5, h6 {
                background-color: transparent !important;
                color: #BDBDBD !important;
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
                font-weight: bold !important;
            }
        """
    }

    // 生成褐色模式 CSS
    private fun generateSepiaCSS(): String {
        return """
            /* Sepia mode - Warm paper background, deep coffee text */
            html, body {
                background-color: #F2E7D0 !important;
                color: #433422 !important;
            }

            * {
                background-color: inherit !important;
                color: inherit !important;
            }

            p, div, span, li, h1, h2, h3, h4, h5, h6 {
                background-color: transparent !important;
                color: #433422 !important;
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
        val nextMode =
                when (currentContrastMode) {
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
