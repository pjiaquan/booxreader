package my.hinoki.booxreader.data.settings

import kotlinx.serialization.Serializable

@Serializable
data class MagicTag(
    val id: String,
    val label: String,
    val content: String = "",
    val description: String = "",
    val role: String = "system"
)
