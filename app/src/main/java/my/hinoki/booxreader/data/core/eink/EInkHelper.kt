package my.hinoki.booxreader.core.eink

import android.os.Build

object EInkHelper {

    fun isBooxDevice(): Boolean {
        return Build.MANUFACTURER.contains("ONYX", ignoreCase = true) ||
                Build.BRAND.contains("ONYX", ignoreCase = true) ||
                Build.BRAND.contains("boox", ignoreCase = true) ||
                Build.MODEL.contains("BOOX", ignoreCase = true) ||
                Build.MODEL.contains("ONYX", ignoreCase = true)
    }
}
