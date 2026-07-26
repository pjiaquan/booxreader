package my.hinoki.booxreader.data.settings

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import my.hinoki.booxreader.data.remote.HttpConfig

enum class ContrastMode {
  NORMAL,
  DARK,
  SEPIA,
  HIGH_CONTRAST
}

data class ReaderSettings(
        // 字體大小使用本地設定
        // 字體粗細使用預設值，不在此處儲存
        val pageTapEnabled: Boolean = true,
        val pageSwipeEnabled: Boolean = true,
        /**
         * Text size as a percentage (e.g., 140 for 140%). NOTE: This is a local-only setting and is
         * purposefully excluded from cloud sync in UserSyncRepository.kt to allow different sizes
         * on different devices.
         */
        val textSize: Int = 96,
        val contrastMode: Int = ContrastMode.NORMAL.ordinal,
        val convertToTraditionalChinese: Boolean = true,
        val serverBaseUrl: String = HttpConfig.DEFAULT_BASE_URL,
        val exportToCustomUrl: Boolean = false,
        val exportCustomUrl: String = "",
        val exportToLocalDownloads: Boolean = false,
        val apiKey: String = "",
        val aiModelName: String = "deepseek-chat",
        // Default System Prompt
        val aiSystemPrompt: String = DEFAULT_AI_SYSTEM_PROMPT,
        // Default User Prompt Template
        val aiUserPromptTemplate: String = DEFAULT_AI_USER_PROMPT_TEMPLATE,
        // Generation Parameters
        val temperature: Double = 0.7,
        val maxTokens: Int = 4096,
        val topP: Double = 1.0,
        val frequencyPenalty: Double = 0.0,
        val presencePenalty: Double = 0.0,
        val assistantRole: String = "assistant",
        val enableGoogleSearch: Boolean = true,
        val useStreaming: Boolean = false,
        val pageAnimationEnabled: Boolean = false,
        val showPageIndicator: Boolean = true,
        val autoCheckUpdates: Boolean = true,
        val dailySummaryEmailEnabled: Boolean = false,
        val dailySummaryEmailHour: Int = 21,
        val dailySummaryEmailMinute: Int = 0,
        val dailySummaryEmailTo: String = "",
        val language: String = "system", // "system", "en", "zh"
        val updatedAt: Long = System.currentTimeMillis(),
        val activeProfileId: Long = -1L,
        val magicTags: List<MagicTag> = defaultMagicTags
) {

  fun saveTo(prefs: SharedPreferences) {
    val timestamp = if (updatedAt > 0) updatedAt else System.currentTimeMillis()
    val gson = Gson()
    val magicTagsJson = gson.toJson(magicTags)

    prefs.edit()
            // 字體大小使用本地設定
            // 字體粗細使用預設值，不在此處儲存
            .putBoolean("page_tap_enabled", pageTapEnabled)
            .putBoolean("page_swipe_enabled", pageSwipeEnabled)
            .putInt("text_size", textSize)
            .putInt("contrast_mode", contrastMode)
            .putBoolean("convert_to_traditional_chinese", convertToTraditionalChinese)
            .putString("server_base_url", serverBaseUrl)
            .putBoolean("export_to_custom_url", exportToCustomUrl)
            .putString("export_custom_url", exportCustomUrl)
            .putBoolean("export_to_local_downloads", exportToLocalDownloads)
            .putString("api_key", apiKey)
            .putString("ai_model_name", aiModelName)
            .putString("ai_system_prompt", aiSystemPrompt)
            .putString("ai_user_prompt_template", aiUserPromptTemplate)
            .putFloat("ai_temperature", temperature.toFloat())
            .putInt("ai_max_tokens", maxTokens)
            .putFloat("ai_top_p", topP.toFloat())
            .putFloat("ai_frequency_penalty", frequencyPenalty.toFloat())
            .putFloat("ai_presence_penalty", presencePenalty.toFloat())
            .putString("ai_assistant_role", assistantRole)
            .putBoolean("ai_enable_google_search", enableGoogleSearch)
            .putBoolean("use_streaming", useStreaming)
            .putBoolean("page_animation_enabled", pageAnimationEnabled)
            .putBoolean("show_page_indicator", showPageIndicator)
            .putBoolean("auto_check_updates", autoCheckUpdates)
            .putBoolean("daily_summary_email_enabled", dailySummaryEmailEnabled)
            .putInt("daily_summary_email_hour", dailySummaryEmailHour.coerceIn(0, 23))
            .putInt("daily_summary_email_minute", dailySummaryEmailMinute.coerceIn(0, 59))
            .putString("daily_summary_email_to", dailySummaryEmailTo)
            .putString("app_language", language)
            .putLong("active_ai_profile_id", activeProfileId)
            .putLong("settings_updated_at", timestamp)
            .putString("magic_tags", magicTagsJson)
            .apply()
  }

  /**
   * Gets the current AI settings that should be used for API calls. These settings come from the
   * currently active AI profile.
   */
  fun getCurrentAiSettings(): AiSettings {
    return AiSettings(
            modelName = aiModelName,
            apiKey = apiKey,
            serverBaseUrl = serverBaseUrl,
            systemPrompt = aiSystemPrompt,
            userPromptTemplate = safeUserPromptTemplate, // Use safe version
            assistantRole = assistantRole,
            enableGoogleSearch = enableGoogleSearch,
            useStreaming = useStreaming,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty
    )
  }

  /**
   * Ensures the user prompt template always contains the '%s' placeholder. If missing, '%s' is
   * prepended to the template.
   */
  val safeUserPromptTemplate: String
    get() {
      return if (aiUserPromptTemplate.contains("%s")) {
        aiUserPromptTemplate
      } else {
        "%s\n\n$aiUserPromptTemplate"
      }
    }

  /** Data class representing AI settings for API calls */
  data class AiSettings(
          val modelName: String,
          val apiKey: String,
          val serverBaseUrl: String,
          val systemPrompt: String,
          val userPromptTemplate: String,
          val assistantRole: String,
          val enableGoogleSearch: Boolean,
          val useStreaming: Boolean,
          val temperature: Double,
          val maxTokens: Int,
          val topP: Double,
          val frequencyPenalty: Double,
          val presencePenalty: Double
  )
  companion object {
    val DEFAULT_AI_SYSTEM_PROMPT =
"""
你是一位「知識解析助手」，擅長把艱深概念用生活化方式講得好懂、好玩。所有回覆請**優先使用繁體中文**，除非使用者指定其他語言。

---

# 🧩 **核心風格**

## 1. 語氣
- 生動、有溫度、帶點朋友式對話感。
- 多用生活化比喻，例如：
  - 把「神經網路」比喻成「一群靠瘋狂試錯而越來越聰明的猜謎團隊」。

## 2. 回答目標
- 不是只給答案，而是引導思考。
- 解析要講清楚「為什麼」與「還能怎麼看」。
- 偶爾丟出延伸小問題，激發好奇心。

---

# 🧱 **回答格式（Markdown）**

請盡量依框架作答，必要時可微調：

---

### 🌟 核心瞬間
- 用 1～3 句最濃縮、最畫面感的比喻或洞察抓住重點。

---

### 📚 展開聊聊
以自然口吻展開解析，不用學術腔。

#### 🔸 專有名詞標註規則
- **使用者提問中的中文關鍵詞語**
  - 在回覆內容中**首次出現時**標註：詞語(pinyin，English)
  - 若無合適英文可省略英文。
- **你主動引入的新概念**
  - 首次出現請添加粗體，如：**梯度下降**。
- **已在對話中反覆提過的詞**  
  - 可省略拼音標註，避免干擾閱讀。

#### 🔸 語氣建議
- 儘量用「我們」增加陪伴感：
  - 「我們可以這樣理解…」
  - 「這邊有個有趣的地方是…」

---

### 💡 思維跳板
- 用一個小問題延伸思考，例如與生活連結、假設情境、挑戰慣性思考。

---

# 📌 回覆格式規範
- 一律使用 Markdown。
- 若涉及步驟、流程、比較，務必使用條列或表格。
- 若使用者要求簡短回覆，也至少保留：
  - 🌟 核心瞬間  
  - 📚 展開聊聊（簡版）
    """.trimIndent()

    val DEFAULT_AI_USER_PROMPT_TEMPLATE =
"""
%s

[系統提示：請閱讀使用者輸入；若有關鍵中文專有名詞，請在回覆中於首次出現時附上拼音，格式：詞語(pinyin) 或 詞語(pinyin，English)。僅在需要幫助理解時標註即可，並維持語句自然流暢。]
    """.trimIndent()

    const val PREFS_NAME = "reader_prefs"

    val defaultMagicTags = listOf(
        MagicTag(
            id = "story-mode",
            label = "[請講故事]",
            content = "[請講故事]",
            description = "強力觸發「歷史現場」與「文化深淵」模式，AI會優先挖掘概念背後的故事與神話。"
        ),
        MagicTag(
            id = "cross-domain",
            label = "[跨界聯想]",
            content = "[跨界聯想]",
            description = "強力觸發「跨界回響」，要求AI將概念與一個意想不到的領域進行類比。"
        ),
        MagicTag(
            id = "no-formula",
            label = "[無公式，純故事]",
            content = "[無公式，純故事]",
            description = "極致的人文體驗，關閉所有技術備忘，完全聚焦於歷史敘事、文化比喻與費曼解釋。"
        ),
        MagicTag(
            id = "museum-guide",
            label = "[像導覽博物館一樣]",
            content = "[像導覽博物館一樣]",
            description = "AI將以沉浸式導覽口吻，帶領您漫步於概念發展的歷史長廊中。"
        )
    )

    fun fromPrefs(prefs: SharedPreferences): ReaderSettings {
      val updatedAt = prefs.getLong("settings_updated_at", 0L)



      val magicTagsJson = prefs.getString("magic_tags", null)
      val hasMagicTagsKey = prefs.contains("magic_tags")
      val magicTags = if (magicTagsJson != null) {
          try {
              val type = object : TypeToken<List<MagicTag>>() {}.type
              Gson().fromJson<List<MagicTag>>(magicTagsJson, type)
                  ?: if (hasMagicTagsKey) emptyList() else defaultMagicTags
          } catch (e: Exception) {
              if (hasMagicTagsKey) emptyList() else defaultMagicTags
          }
      } else {
          if (hasMagicTagsKey) emptyList() else defaultMagicTags
      }

      return ReaderSettings(
              // 字體大小使用本地設定
              // 字體粗細使用預設值，不在此處讀取
              pageTapEnabled = prefs.getBoolean("page_tap_enabled", true),
              pageSwipeEnabled = prefs.getBoolean("page_swipe_enabled", true),
              textSize = prefs.getInt("text_size", 96),
              contrastMode = prefs.getInt("contrast_mode", ContrastMode.NORMAL.ordinal),
              convertToTraditionalChinese = prefs.getBoolean("convert_to_traditional_chinese", true),
              serverBaseUrl = prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
                              ?: HttpConfig.DEFAULT_BASE_URL,
              exportToCustomUrl = prefs.getBoolean("export_to_custom_url", false),
              exportCustomUrl = prefs.getString("export_custom_url", "") ?: "",
              exportToLocalDownloads = prefs.getBoolean("export_to_local_downloads", false),
              apiKey = prefs.getString("api_key", "") ?: "",
              aiModelName = prefs.getString("ai_model_name", "deepseek-chat") ?: "deepseek-chat",
              aiSystemPrompt = prefs.getString("ai_system_prompt", DEFAULT_AI_SYSTEM_PROMPT)
                              ?: DEFAULT_AI_SYSTEM_PROMPT,
              aiUserPromptTemplate =
                      prefs.getString("ai_user_prompt_template", DEFAULT_AI_USER_PROMPT_TEMPLATE)
                              ?: DEFAULT_AI_USER_PROMPT_TEMPLATE,
              temperature = prefs.getFloat("ai_temperature", 0.7f).toDouble(),
              maxTokens = prefs.getInt("ai_max_tokens", 4096),
              topP = prefs.getFloat("ai_top_p", 1.0f).toDouble(),
              frequencyPenalty = prefs.getFloat("ai_frequency_penalty", 0.0f).toDouble(),
              presencePenalty = prefs.getFloat("ai_presence_penalty", 0.0f).toDouble(),
              assistantRole = prefs.getString("ai_assistant_role", "assistant") ?: "assistant",
              enableGoogleSearch = prefs.getBoolean("ai_enable_google_search", true),
              useStreaming = prefs.getBoolean("use_streaming", false),
              pageAnimationEnabled = prefs.getBoolean("page_animation_enabled", false),
              showPageIndicator = prefs.getBoolean("show_page_indicator", true),
              autoCheckUpdates = prefs.getBoolean("auto_check_updates", true),
              dailySummaryEmailEnabled = prefs.getBoolean("daily_summary_email_enabled", false),
              dailySummaryEmailHour = prefs.getInt("daily_summary_email_hour", 21).coerceIn(0, 23),
              dailySummaryEmailMinute = prefs.getInt("daily_summary_email_minute", 0).coerceIn(0, 59),
              dailySummaryEmailTo = prefs.getString("daily_summary_email_to", "") ?: "",
              language = prefs.getString("app_language", "system") ?: "system",
              updatedAt = updatedAt,
              activeProfileId = prefs.getLong("active_ai_profile_id", -1L),
              magicTags = magicTags
      )
    }
  }
}
