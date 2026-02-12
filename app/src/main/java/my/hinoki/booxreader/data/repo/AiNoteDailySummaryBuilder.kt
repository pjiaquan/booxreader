package my.hinoki.booxreader.data.repo

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Locale
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.reader.DailyReadingStats

object AiNoteDailySummaryBuilder {
    data class DailySummary(
            val subject: String,
            val body: String,
            val noteCount: Int
    )

    private const val SUBJECT_DATE_FORMAT = "yyyy-MM-dd"
    private const val NOTE_TIME_FORMAT = "HH:mm"
    private const val PREVIEW_MAX_LENGTH = 140
    private const val ENTITY_LIST_MAX = 12

    private val eraKeywords =
            listOf(
                    "先秦",
                    "秦朝",
                    "汉朝",
                    "三国",
                    "魏晋",
                    "南北朝",
                    "隋朝",
                    "唐朝",
                    "宋朝",
                    "元朝",
                    "明朝",
                    "清朝",
                    "民国",
                    "近代",
                    "现代",
                    "当代",
                    "古代",
                    "中世纪",
                    "文艺复兴",
                    "工业革命",
                    "冷战",
                    "春秋",
                    "战国"
            )
    private val projectLabelRegex =
            Regex("(?i)(?:项目|專案|計畫|计划|project|topic|主題)\\s*[:：]\\s*([^\\n。；;]+)")
    private val personLabelRegex =
            Regex("(?i)(?:人物|角色|人名|person|people)\\s*[:：]\\s*([^\\n。；;]+)")
    private val eraLabelRegex =
            Regex("(?i)(?:时代|時代|朝代|年代|era|period)\\s*[:：]\\s*([^\\n。；;]+)")
    private val eraPattern =
            Regex(
                    "(\\d{1,2}世纪|\\d{4}年代|\\d{3,4}年|\\b\\d{1,2}(st|nd|rd|th)\\s+century\\b)",
                    RegexOption.IGNORE_CASE
            )
    private val tagSeparatorRegex = Regex("[,，、/|；;]")

    fun build(
            context: Context,
            sourceNotes: List<AiNoteEntity>,
            nowMillis: Long = System.currentTimeMillis(),
            todayReadingMillis: Long = DailyReadingStats.getTodayReadingMillis(context, nowMillis)
    ): DailySummary {
        val todayNotes = notesForToday(sourceNotes, nowMillis)
        val dateLabel = DateFormat.format(SUBJECT_DATE_FORMAT, nowMillis).toString()
        val subject =
                context.getString(
                        R.string.ai_note_daily_summary_mail_subject,
                        dateLabel,
                        todayNotes.size
                )
        if (todayNotes.isEmpty()) {
            val emptyBody =
                    buildString {
                        appendLine("AI Note Daily Summary")
                        appendLine("Date: $dateLabel")
                        appendLine("Total Notes: 0")
                        appendLine("Total Reading Time: ${DailyReadingStats.formatDuration(todayReadingMillis)}")
                    }.trim()
            return DailySummary(subject = subject, body = emptyBody, noteCount = 0)
        }

        val projectSet = linkedSetOf<String>()
        val personSet = linkedSetOf<String>()
        val eraSet = linkedSetOf<String>()
        val bookSet = linkedSetOf<String>()
        val noteLines = mutableListOf<String>()

        todayNotes.forEachIndexed { index, note ->
            val original = resolveOriginalText(note)
            val response = resolveAiResponse(note)
            val combined = listOf(original, response).filter { it.isNotBlank() }.joinToString("\n")
            collectEntities(projectLabelRegex, combined, projectSet)
            collectEntities(personLabelRegex, combined, personSet)
            collectEntities(eraLabelRegex, combined, eraSet)
            collectEraKeywords(combined, eraSet)

            note.bookTitle?.trim()?.takeIf { it.isNotBlank() }?.let { bookSet.add(it) }
            val timeLabel = DateFormat.format(NOTE_TIME_FORMAT, note.createdAt).toString()
            val titleLabel = note.bookTitle?.takeIf { it.isNotBlank() } ?: "Untitled"
            noteLines +=
                    "${index + 1}. [$timeLabel] $titleLabel\n" +
                            "   Q: ${shortenForMail(original)}\n" +
                            "   A: ${shortenForMail(response)}"
        }

        val body =
                buildString {
                    appendLine("AI Note Daily Summary")
                    appendLine("Date: $dateLabel")
                    appendLine("Total Notes: ${todayNotes.size}")
                    appendLine("Books: ${bookSet.size}")
                    appendLine(
                            "Total Reading Time: ${DailyReadingStats.formatDuration(todayReadingMillis)}"
                    )
                    appendLine("Projects: ${projectSet.size} (${formatEntitySet(projectSet)})")
                    appendLine("People: ${personSet.size} (${formatEntitySet(personSet)})")
                    appendLine("Eras: ${eraSet.size} (${formatEntitySet(eraSet)})")
                    appendLine()
                    appendLine("Details")
                    noteLines.forEach { appendLine(it) }
                }.trim()
        return DailySummary(subject = subject, body = body, noteCount = todayNotes.size)
    }

    fun notesForToday(
            source: List<AiNoteEntity>,
            nowMillis: Long = System.currentTimeMillis()
    ): List<AiNoteEntity> {
        if (source.isEmpty()) return emptyList()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val dayStart = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val dayEndExclusive = calendar.timeInMillis
        return source.filter { note ->
            note.createdAt >= dayStart && note.createdAt < dayEndExclusive
        }.sortedBy { it.createdAt }
    }

    private fun resolveOriginalText(note: AiNoteEntity): String {
        return note.originalText?.takeIf { it.isNotBlank() }
                ?: AiNoteSerialization.originalTextFromMessages(note.messages).orEmpty()
    }

    private fun resolveAiResponse(note: AiNoteEntity): String {
        return note.aiResponse?.takeIf { it.isNotBlank() }
                ?: AiNoteSerialization.aiResponseFromMessages(note.messages).orEmpty()
    }

    private fun shortenForMail(text: String): String {
        if (text.isBlank()) return "(empty)"
        val normalized = text.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
        return if (normalized.length <= PREVIEW_MAX_LENGTH) {
            normalized
        } else {
            normalized.take(PREVIEW_MAX_LENGTH).trimEnd() + "..."
        }
    }

    private fun collectEntities(regex: Regex, text: String, target: MutableSet<String>) {
        if (text.isBlank()) return
        regex.findAll(text).forEach { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty()
            raw.split(tagSeparatorRegex)
                    .map { it.trim().trim('.', '。', ':', '：') }
                    .filter { it.length >= 2 }
                    .take(ENTITY_LIST_MAX)
                    .forEach { target.add(it) }
        }
    }

    private fun collectEraKeywords(text: String, target: MutableSet<String>) {
        if (text.isBlank()) return
        val lower = text.lowercase(Locale.ROOT)
        eraKeywords.forEach { keyword ->
            if (text.contains(keyword, ignoreCase = true)) {
                target.add(keyword)
            }
        }
        eraPattern.findAll(text).forEach { target.add(it.value.trim()) }
        if (lower.contains("modern")) target.add("modern")
        if (lower.contains("ancient")) target.add("ancient")
        if (lower.contains("medieval")) target.add("medieval")
    }

    private fun formatEntitySet(values: Set<String>): String {
        if (values.isEmpty()) return "N/A"
        return values.take(ENTITY_LIST_MAX).joinToString(", ")
    }
}
