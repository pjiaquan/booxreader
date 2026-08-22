package my.hinoki.booxreader.data.remote

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Android UI 輔助：顯示 AI 串流錯誤對話框（parseError 在 :shared commonMain）。 */
object StreamingErrorDialog {

    fun show(context: Context, errorInfo: StreamingErrorInfo) {
        val detailText = StringBuilder()
            .append("【錯誤原因】\n").append(errorInfo.reason).append("\n\n")
            .append("【如何處理】\n").append(errorInfo.resolution)
            .apply {
                if (errorInfo.rawMessage.isNotBlank()) {
                    append("\n\n【詳細訊息】\n").append(errorInfo.rawMessage)
                }
            }.toString()

        MaterialAlertDialogBuilder(context)
            .setTitle(errorInfo.title)
            .setMessage(detailText)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
