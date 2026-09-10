package my.hinoki.booxreader.data.db

import androidx.room.TypeConverter
import my.hinoki.booxreader.data.security.Secrets

/**
 * LLM API key 的型別包裝。
 *
 * Room 會透過 [ApiKeyConverter] 在寫入 / 讀取時自動加解密，因此**所有** DB 路徑
 * （insert / update / batch / query / Flow）都不會讓明文金鑰落地 —— 用型別而非
 * 呼叫慣例來保證，漏掉某個呼叫點也不會洩漏。
 *
 * 舊版本以明文寫入的資料，由 [Secrets.reveal] 在讀取時自動相容，無需 Room migration
 * （欄位仍是 TEXT，只是內容變成密文）。
 */
data class ApiKey(val value: String = "") {

    val isBlank: Boolean get() = value.isBlank()

    /** 避免 `"$apiKey"`、`mapOf(... to apiKey)` 之類的意外字串化產生 `ApiKey(value=...)`。 */
    override fun toString(): String = value
}

class ApiKeyConverter {

    @TypeConverter fun toStoredValue(key: ApiKey): String = Secrets.protect(key.value)

    @TypeConverter fun fromStoredValue(stored: String): ApiKey = ApiKey(Secrets.reveal(stored))
}
