package com.example.booxreader.core.eink

import android.os.Build
import android.view.View

object EInkHelper {
    /**
     * 現階段先用最保險的方式：
     * - 呼叫 view.invalidate()
     * - 讓 Boox 自己決定用什麼 waveform 刷新
     *
     * 之後如果你加了 Onyx SDK，可以在這裡用反射 / 官方 API 嚴格指定 A2 / Regal。
     */

    fun isBoox(): Boolean {
        return Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }

    fun refresh(view: View, full: Boolean = false) {
        if (!isBoox()) return

        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val method = cls.getMethod(
                "invalidate",
                View::class.java,
                Boolean::class.java
            )
            method.invoke(null, view, full)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setA2Mode(view: View) {
        if (!isBoox()) return

        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val mode = cls.getField("EPD_A2").get(null)
            val method = cls.getMethod("setMode", View::class.java, mode.javaClass)
            method.invoke(null, view, mode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
