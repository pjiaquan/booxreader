package my.hinoki.booxreader.core.eink

import android.os.Build
import android.view.View

object EInkHelper {

    fun isBoox(): Boolean {
        return Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }

    fun refresh(view: View, full: Boolean = false) {
        if (!isBoox()) return
        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            val method = cls.getMethod("invalidate", View::class.java, Boolean::class.java)
            method.invoke(null, view, full)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Switches to A2 (Fast) mode. Good for scrolling, dragging, or selection.
     */
    fun enableFastMode(view: View) {
        if (!isBoox()) return
        setEpdsMode(view, "EPD_A2")
    }

    /**
     * Switches back to Regal (Quality) mode. Good for static text reading.
     */
    fun restoreQualityMode(view: View) {
        if (!isBoox()) return
        // "EPD_REGAL" is standard for text, or "EPD_TEXT" depending on SDK version.
        // Fallback to default if unsure, but setting explicit helps.
        setEpdsMode(view, "EPD_REGAL") 
    }

    private fun setEpdsMode(view: View, modeName: String) {
        try {
            val cls = Class.forName("com.onyx.android.sdk.api.device.EpdController")
            // Attempt to find the static field for the mode
            val modeField = try {
                cls.getField(modeName)
            } catch (e: NoSuchFieldException) {
                // Fallback for some devices/SDKs
                if (modeName == "EPD_REGAL") cls.getField("EPD_TEXT") else null
            }

            if (modeField != null) {
                val modeValue = modeField.get(null)
                // Method signature: setMode(View view, int mode) or setMode(View view, Mode mode)
                // Most Onyx SDKs use an Enum or Int. Let's try to find the method dynamically.
                val methods = cls.methods
                val setModeMethod = methods.find { 
                    it.name == "setMode" && it.parameterTypes.size == 2 && it.parameterTypes[0] == View::class.java 
                }
                
                setModeMethod?.invoke(null, view, modeValue)
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}

