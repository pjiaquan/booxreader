package my.hinoki.booxreader.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * 每日摘要 email 的寄送邏輯（自 `UserSyncRepository` 抽出的 email 叢集）。
 *
 * PocketBase 的郵件端點依部署方式而異，因此依序嘗試三種策略：
 * 1) `/api/mails/send`（需要 admin 權限）
 * 2) `pb_hooks` 自訂路由（[MAIL_CUSTOM_ROUTE_CANDIDATES]）
 * 3) 寫入 mail queue collection，交由 server-side hook 寄送
 *
 * 只透過建構子注入少量協作者，因此可獨立於 `UserSyncRepository` 之外理解與測試。
 */
internal class DailySummaryEmailSender(
        private val pocketBaseUrl: String,
        private val client: HttpClient,
        private val io: CoroutineDispatcher,
        private val accessToken: () -> String?,
        private val refreshAuthSession: suspend (String) -> String?,
        private val currentUserId: suspend () -> String?
) {

    /**
     * 寄送每日摘要。
     *
     * Strategy:
     * 1) Try PocketBase direct mail endpoint (/api/mails/send).
     * 2) Fallback to inserting a record into a mail queue collection for server-side hooks.
     */
    suspend fun send(toEmail: String, subject: String, body: String): CheckResult =
            withContext(io) {
                // Allow both host-root and mistakenly-configured */api base URLs.
                val pocketBaseRoot = pocketBaseUrl.removeSuffix("/api")
                val refreshedUserId = refreshAuthSession(pocketBaseRoot)
                val email = toEmail.trim()
                if (email.isBlank()) {
                    return@withContext CheckResult(false, "Missing recipient email")
                }
                val userId =
                        refreshedUserId
                                ?: currentUserId()
                                ?: return@withContext CheckResult(false, "No logged in user")

                var lastError: String? = null
                var directStatusCode: Int? = null
                val customRouteStatusCodes = mutableListOf<Int>()
                val queueStatusCodes = mutableListOf<Int>()
                var firstNon404QueueError: String? = null

                // Strategy 1: PocketBase direct mail API (usually requires elevated access).
                runCatching {
                            val htmlBody =
                                    "<pre style=\"white-space:pre-wrap;font-family:monospace;\">${escapeHtmlForEmail(body)}</pre>"
                            val payload =
                                    mapToJsonString(
                                            mapOf(
                                                    "to" to listOf(email),
                                                    "subject" to subject,
                                                    "html" to htmlBody,
                                                    "text" to body
                                            )
                                    )
                            val response =
                                    client.post("$pocketBaseRoot/api/mails/send") {
                                        header(
                                                "Authorization",
                                                "Bearer ${accessToken().orEmpty()}"
                                        )
                                        contentType(ContentType.Application.Json)
                                        setBody(payload)
                                    }
                            val responseBody = response.bodyAsText().trim()
                            directStatusCode = response.status.value
                            if (response.status.isSuccess()) {
                                return@withContext CheckResult(true, "sent via /api/mails/send")
                            }
                            lastError = "direct mail failed (${response.status.value})"
                            if (responseBody.isNotEmpty()) {
                                lastError += ": $responseBody"
                            }
                        }
                        .onFailure {
                            lastError =
                                    it.message?.takeIf { message -> message.isNotBlank() }
                                            ?: "direct mail request failed"
                        }

                // Strategy 1.5: custom routerAdd endpoint in PocketBase hooks.
                for (routePath in MAIL_CUSTOM_ROUTE_CANDIDATES) {
                    runCatching {
                                val payload =
                                        mapToJsonString(
                                                mapOf(
                                                        "toEmail" to email,
                                                        "subject" to subject,
                                                        "body" to body
                                                )
                                        )
                                val response =
                                        client.post("$pocketBaseRoot$routePath") {
                                            header(
                                                    "Authorization",
                                                    "Bearer ${accessToken().orEmpty()}"
                                            )
                                            contentType(ContentType.Application.Json)
                                            setBody(payload)
                                        }
                                val responseBody = response.bodyAsText().trim()
                                customRouteStatusCodes += response.status.value
                                if (response.status.isSuccess()) {
                                    return@withContext CheckResult(
                                            true,
                                            "sent via $routePath"
                                    )
                                }
                                lastError =
                                        "custom route $routePath failed (${response.status.value})"
                                if (responseBody.isNotEmpty()) {
                                    lastError += ": $responseBody"
                                }
                            }
                            .onFailure {
                                val errorMessage =
                                        it.message?.takeIf { message -> message.isNotBlank() }
                                                ?: "custom route request failed"
                                if (lastError.isNullOrBlank()) {
                                    lastError = errorMessage
                                }
                            }
                }

                // Strategy 2: queue record for PocketBase hook/automation mail dispatch.
                for (collection in MAIL_QUEUE_COLLECTION_CANDIDATES) {
                    val queuePayload =
                            mapToJsonString(
                                    mapOf(
                                            "user" to userId,
                                            "toEmail" to email,
                                            "subject" to subject,
                                            "body" to body,
                                            "category" to "ai_note_daily_summary",
                                            "status" to "pending"
                                    )
                            )
                    try {
                        val response =
                                client.post("$pocketBaseRoot/api/collections/$collection/records") {
                                    header(
                                            "Authorization",
                                            "Bearer ${accessToken().orEmpty()}"
                                    )
                                    contentType(ContentType.Application.Json)
                                    setBody(queuePayload)
                                }
                        val responseBody = response.bodyAsText().trim()
                        queueStatusCodes += response.status.value
                        if (response.status.isSuccess()) {
                            return@withContext CheckResult(true, "queued via $collection")
                        }
                        val queueError = "queue $collection failed (${response.status.value})"
                        if (responseBody.isNotEmpty()) {
                            lastError = "$queueError: $responseBody"
                        } else {
                            lastError = queueError
                        }
                        if (response.status.value != 404 && firstNon404QueueError == null) {
                            firstNon404QueueError = lastError
                        }
                    } catch (e: Exception) {
                        lastError =
                                e.message?.takeIf { message -> message.isNotBlank() }
                                        ?: "queue $collection request failed"
                    }
                }

                if (firstNon404QueueError != null) {
                    lastError = firstNon404QueueError
                }

                val setupHint =
                        when {
                            directStatusCode == 401 || directStatusCode == 403 ->
                                    "PocketBase /api/mails/send requires admin permission. Use mail_queue hook mode."
                            queueStatusCodes.any { it == 401 || it == 403 } ->
                                    "PocketBase queue write denied. Check createRule for mail_queue/email_queue/outbox_emails (expect @request.auth.id != \"\")."
                            customRouteStatusCodes.any { it == 401 || it == 403 } ->
                                    "PocketBase custom mail route denied. Check routerAdd auth handling."
                            directStatusCode == 404 &&
                                    queueStatusCodes.all { it == 404 } &&
                                    queueStatusCodes.isNotEmpty() ->
                                    "PocketBase endpoints not found. Check POCKETBASE_URL (use host root, no /api) and create mail_queue/email_queue/outbox_emails with a send hook."
                            customRouteStatusCodes.isNotEmpty() &&
                                    customRouteStatusCodes.all { it == 404 } ->
                                    "PocketBase custom route /boox-mail-send not found. Ensure pb_hooks/main.pb.js is deployed and loaded."
                            queueStatusCodes.isNotEmpty() && queueStatusCodes.all { it == 404 } ->
                                    "Queue collection not found. Create mail_queue/email_queue/outbox_emails in PocketBase."
                            else -> null
                        }

                CheckResult(
                        false,
                        (setupHint ?: lastError)
                                ?: "PocketBase mail failed; /api/mails/send and mail_queue endpoints unavailable"
                )
            }

    private companion object {
        val MAIL_QUEUE_COLLECTION_CANDIDATES =
                listOf("mail_queue", "email_queue", "outbox_emails")
        val MAIL_CUSTOM_ROUTE_CANDIDATES = listOf("/boox-mail-send", "/api/boox-mail-send")
    }
}
