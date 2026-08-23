package my.hinoki.booxreader.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.util.AttributeKey
import my.hinoki.booxreader.data.auth.TokenProvider

/**
 * 標記請求跳過 Bearer auth（對應 OkHttp 的 SKIP_AUTH tag）。
 * 用法：client.get(url) { attributes.put(skipAuthAttribute, true) }
 */
val skipAuthAttribute = AttributeKey<Boolean>("booxreader.skipAuth")

/**
 * 建立共用的 Ktor HttpClient（KMP commonMain）。
 *
 * - 預設 HttpTimeout（連線 30s / 請求 60s）
 * - 若提供 TokenProvider，會自動對「後端主機」的請求加上
 *   `Authorization: Bearer <token>`（與舊 AuthInterceptor 的 isBackendRequest 邏輯一致）；
 *   第三方 API（OpenAI 等）不受影響。
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
            defaultRequest {
                val backendHost =
                    runCatching { Url(tokenProvider.getBackendUrl()).host }.getOrNull()
                val isBackendRequest = url.host == backendHost
                if (isBackendRequest && attributes.getOrNull(skipAuthAttribute) != true) {
                    tokenProvider.getAccessToken()?.let { token ->
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            }
        }
    }
}
