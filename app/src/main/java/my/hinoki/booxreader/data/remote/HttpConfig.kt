package my.hinoki.booxreader.data.remote

object HttpConfig {

    const val DEFAULT_BASE_URL = "https://your-server-url.com"

    // Endpoint Paths
    const val PATH_BOOKMARK = "/boox-update"
    const val PATH_PROGRESS = "/boox-progress"
    const val PATH_TEXT_AI = "/boox-text-ai"
    const val PATH_TEXT_AI_CONTINUE = "/boox-text-ai-continue"
    const val PATH_AI_NOTES_EXPORT = "/boox-ai-notes-export"

    // Deprecated: Kept for compatibility if referenced elsewhere, but prefer building dynamically
    val BOOKMARK_ENDPOINT = "$DEFAULT_BASE_URL$PATH_BOOKMARK"
    val PROGRESS_ENDPOINT = "$DEFAULT_BASE_URL$PATH_PROGRESS"
    val TEXT_AI_ENDPOINT = "$DEFAULT_BASE_URL$PATH_TEXT_AI"
    val TEXT_AI_CONTINUE_ENDPOINT = "$DEFAULT_BASE_URL$PATH_TEXT_AI_CONTINUE"
    val AI_NOTES_EXPORT_ENDPOINT = "$DEFAULT_BASE_URL$PATH_AI_NOTES_EXPORT"
}

