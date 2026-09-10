package my.hinoki.booxreader.data.repo

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.hinoki.booxreader.data.db.AnnotationEntity
import my.hinoki.booxreader.data.db.AnnotationStyle
import my.hinoki.booxreader.data.db.withTransactionCompat
import my.hinoki.booxreader.data.platform.currentEpochMillis

/**
 * 畫線 / 註記同步（PocketBase `annotations` collection）。
 *
 * 這是先前缺漏的一塊：DB 早已有 `remoteId` / `isSynced` 欄位與 `MIGRATION_14_15`，
 * `AnnotationRepository` 也帶著 `syncRepo` 參數，但同步層完全沒有實作，
 * 因此畫線只留在單一裝置上（欄位是死的）。
 *
 * 結構與 [BookmarkSync] 相同（host 模式、薄 delegate），差異是：
 * - 額外帶 `selectedText` / `note` / `style`
 * - 拉取時採用**遠端 `updatedAt`** 判斷新舊（而不是本機現在時間），因此重複拉取是幂等的
 */
internal class AnnotationSync(private val host: UserSyncRepository) {

    /** 拉取遠端畫線並寫入本機。回傳寫入 / 更新的筆數。 */
    suspend fun pullAnnotations(bookId: String? = null): Int =
            withContext(host.io) {
                try {
                    val userId = host.getUserId() ?: return@withContext 0
                    val filterParam =
                            if (bookId != null) {
                                "(user='$userId'%26%26bookId='$bookId')"
                            } else {
                                "(user='$userId')"
                            }

                    val items =
                            host.fetchAllItems(
                                    "annotations",
                                    filterParam,
                                    sortParam = "-updatedAt",
                                    perPage = 100
                            )

                    // 以 chunked IN 查詢一次取回既有資料，避免 N+1
                    val remoteIds =
                            items.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.distinct()
                    val cached = mutableMapOf<String, AnnotationEntity>()
                    remoteIds.chunked(900).forEach { chunk ->
                        cached.putAll(
                                host.db.annotationDao()
                                        .getByRemoteIds(chunk)
                                        .associateBy { it.remoteId!! }
                        )
                    }

                    val toInsert = mutableListOf<AnnotationEntity>()
                    for (item in items) {
                        val remoteId = item["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val remoteBookId = item["bookId"]?.jsonPrimitive?.contentOrNull ?: continue
                        val locatorJson = item["locatorJson"]?.jsonPrimitive?.contentOrNull ?: continue

                        val existing = cached[remoteId]
                        val entity =
                                AnnotationEntity(
                                        id = existing?.id ?: 0L,
                                        remoteId = remoteId,
                                        bookId = remoteBookId,
                                        locatorJson = locatorJson,
                                        selectedText =
                                                item["selectedText"]
                                                        ?.jsonPrimitive
                                                        ?.contentOrNull
                                                        .orEmpty(),
                                        note =
                                                item["note"]
                                                        ?.jsonPrimitive
                                                        ?.contentOrNull
                                                        ?.takeIf { it.isNotBlank() },
                                        style =
                                                item["style"]
                                                        ?.jsonPrimitive
                                                        ?.contentOrNull
                                                        ?.takeIf { it.isNotBlank() }
                                                        ?: AnnotationStyle.UNDERLINE.name,
                                        createdAt =
                                                longValue(item["createdAt"]).takeIf { it > 0L }
                                                        ?: currentEpochMillis(),
                                        updatedAt =
                                                longValue(item["updatedAt"]).takeIf { it > 0L }
                                                        ?: currentEpochMillis(),
                                        isSynced = true
                                )

                        if (existing == null || entity.updatedAt > existing.updatedAt) {
                            toInsert.add(entity)
                        }
                    }

                    if (toInsert.isNotEmpty()) {
                        host.db.withTransactionCompat {
                            host.db.annotationDao().insertBatch(toInsert)
                        }
                    }

                    host.logger.d(
                            "UserSyncRepository",
                            "pullAnnotations - merged ${toInsert.size} of ${items.size} records"
                    )
                    toInsert.size
                } catch (e: Exception) {
                    host.logger.e("UserSyncRepository", "pullAnnotations failed", e)
                    0
                }
            }

    /** 推送單一畫線（有 remoteId 走 PATCH，否則 POST）。成功時回傳帶 remoteId 的 entity。 */
    suspend fun pushAnnotation(entity: AnnotationEntity): AnnotationEntity? =
            withContext(host.io) {
                try {
                    val userId = host.getUserId() ?: return@withContext null

                    val payload =
                            mapOf(
                                    "user" to userId,
                                    "bookId" to entity.bookId,
                                    "locatorJson" to entity.locatorJson,
                                    "selectedText" to entity.selectedText,
                                    "note" to (entity.note ?: ""),
                                    "style" to entity.style,
                                    "createdAt" to entity.createdAt,
                                    "updatedAt" to currentEpochMillis()
                            )
                    val requestBody = mapToJsonString(payload)

                    val responseBody =
                            if (!entity.remoteId.isNullOrBlank()) {
                                host.executeBackendRequest(
                                        "${host.pocketBaseUrl}/api/collections/annotations/records/${entity.remoteId}"
                                ) {
                                    method = HttpMethod.Patch
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                            } else {
                                host.executeBackendRequest(
                                        "${host.pocketBaseUrl}/api/collections/annotations/records"
                                ) {
                                    method = HttpMethod.Post
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                            }

                    val response = host.json.parseToJsonElement(responseBody).jsonObject
                    val remoteId =
                            response["id"]?.jsonPrimitive?.contentOrNull ?: entity.remoteId
                    host.logger.d(
                            "UserSyncRepository",
                            "pushAnnotation - annotation synced (${remoteId != null})"
                    )
                    entity.copy(remoteId = remoteId, isSynced = true)
                } catch (e: Exception) {
                    host.logger.e("UserSyncRepository", "pushAnnotation failed", e)
                    null
                }
            }

    /** 刪除遠端畫線。 */
    suspend fun deleteAnnotation(remoteId: String): Boolean =
            withContext(host.io) {
                try {
                    host.executeBackendRequest(
                            "${host.pocketBaseUrl}/api/collections/annotations/records/$remoteId"
                    ) {
                        method = HttpMethod.Delete
                    }
                    host.logger.d("UserSyncRepository", "deleteAnnotation - annotation deleted")
                    true
                } catch (e: Exception) {
                    host.logger.e("UserSyncRepository", "deleteAnnotation failed", e)
                    false
                }
            }
}
