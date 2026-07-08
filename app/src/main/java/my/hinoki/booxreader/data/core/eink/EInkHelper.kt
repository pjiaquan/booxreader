package my.hinoki.booxreader.core.eink

import android.os.Build

object EInkHelper {

    // ponytail: simplified list check
    fun isBooxDevice(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
        .any { it.contains("ONYX", ignoreCase = true) || it.contains("boox", ignoreCase = true) }
}
