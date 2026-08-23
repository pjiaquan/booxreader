package my.hinoki.booxreader.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.util.AttributeKey
import my.hinoki.booxreader.data.auth.TokenProvider

/**
 * 標記請求跳過 Bearer auth（對應舊 OkHttp 的 SKIP_AUTH tag）。
 * 用法：client.get(url) { attributes.put(skipAuthAttribute, true) }
 */
val skipAuthAttribute = AttributeKey<Boolean>("booxreader.skipAuth")

/** BearerAuth plugin 設定。 */
class BearerAuthConfig {
    var tokenProvider: TokenProvider? = null
}

/**
 * 對「後端主機」的請求自動加上 `Authorization: Bearer <token>`。
 * 與舊 AuthInterceptor 的 isBackendRequest 邏輯一致；第三方 API（OpenAI 等）不受影響。
 * 使用 onRequest hook（此時呼叫端 URL 已解析；defaultRequest 在 Ktor 3 看不到 URL）。
 */
val BearerAuth =
        createClientPlugin("BooxReaderBearerAuth", ::BearerAuthConfig) {
                onRequest { request, _ ->
                        val provider = this@createClientPlugin.pluginConfig.tokenProvider
                                ?: return@onRequest
                        val backendHost =
                                runCatching { Url(provider.getBackendUrl()).host }.getOrNull()
                        val isBackendRequest = request.url.host == backendHost
                        if (isBackendRequest &&
                                        request.attributes.getOrNull(skipAuthAttribute) != true
                        ) {
                                provider.getAccessToken()?.let { token ->
                                        request.headers.append(
                                                HttpHeaders.Authorization,
                                                "Bearer $token"
                                        )
                                }
                        }
                }
        }

/**
 * 建立共用的 Ktor HttpClient（KMP commonMain）。
 *
 * - 預設 HttpTimeout（連線 30s / 請求 60s）
 * - 若提供 TokenProvider，自動對後端主機請求加上 Bearer token（BearerAuth plugin）
 *
 * 取代 :app 的 OkHttpClient 建置（BooxReaderApp.okHttpClient）。
 */
fun createApiClient(
    tokenProvider: TokenProvider? = null,
    connectTimeoutMillis: Long = 30_000,
    requestTimeoutMillis: Long = 60_000,
): HttpClient {
    return HttpClient {
        install(HttpTimeout) {
            this.connectTimeoutMillis = connectTimeoutMillis
            this.requestTimeoutMillis = requestTimeoutMillis
            this.socketTimeoutMillis = requestTimeoutMillis
        }

        if (tokenProvider != null) {
            install(BearerAuth) {
                this.tokenProvider = tokenProvider
            }
        }
    }
}

/** Ktor 版 URL 驗證（取代 OkHttp 的 String.toHttpUrlOrNull()）。 */
fun String.isValidHttpUrl(): Boolean = runCatching { Url(this) }.isSuccess
