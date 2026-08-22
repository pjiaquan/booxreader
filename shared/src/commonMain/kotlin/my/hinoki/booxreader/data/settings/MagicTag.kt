package my.hinoki.booxreader.data.settings

data class MagicTag(
    val id: String,
    val label: String,
    val content: String = "",
    val description: String = "",
    val role: String = "system"
)
