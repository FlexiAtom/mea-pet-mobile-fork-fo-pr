package com.meapet.mobile.client

import com.meapet.mobile.client.exception.ApiException

/**
 * OpenAI 兼容 HTTP 客户端。
 *
 * 特点：
 * - 仅负责 HTTP 通信与请求体序列化，不处理业务逻辑；
 * - 所有 API 返回原始 JSON 字符串或二进制字节数组，由调用方自行解析；
 * - HTTP 引擎通过 [HttpClientEngine] 抽象注入，默认使用 Ktor CIO；
 * - 所有公开方法均为 `suspend`，原生协程支持；
 * - 用户在设置里填的是 **API 根**（通常以 `/v1` 结尾），客户端只自动补齐后面的请求路径
 *   （`/chat/completions`、`/models` 等）。
 *
 * 例如填 `https://api.openai.com/v1` → 实际请求 `https://api.openai.com/v1/chat/completions`。
 * 若用户只填了 `https://api.openai.com`，也会自动补上 `/v1` 再拼后续路径。
 *
 * @param apiKey API 密钥
 * @param baseUrl API 根地址，例如 `https://api.openai.com/v1`
 * @param engine HTTP 引擎，单元测试可注入 Fake 实现
 */
class OpenAiCompatibleClient(
    private val apiKey: String,
    baseUrl: String,
    private val engine: HttpClientEngine = KtorHttpClientEngine()
) {

    /**
     * 规范化后的 API 根（**一定以 `/v1` 结尾**，无尾部 `/`）：
     * - 去空白、去尾部 `/`
     * - 若末尾还不是 `/v1`，自动补上
     */
    private val baseUrl: String = normalizeBaseUrl(baseUrl)

    /** `GET .../models`（完整路径为 `{base}/models`，base 已含 `/v1`） */
    suspend fun listModels(): String {
        val request = HttpRequest(
            method = HttpMethod.GET,
            url = apiUrl("models"),
            headers = authHeaders()
        )
        return executeExpectText(request)
    }

    /** `POST .../chat/completions` */
    suspend fun chatCompletion(requestBody: String): String {
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = apiUrl("chat/completions"),
            headers = authHeaders(),
            body = RequestBody.Json(requestBody)
        )
        return executeExpectText(request)
    }

    /** `POST .../audio/transcriptions` */
    suspend fun createTranscription(parts: List<MultipartPart>): String {
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = apiUrl("audio/transcriptions"),
            headers = authHeaders(),
            body = RequestBody.Multipart(parts)
        )
        return executeExpectText(request)
    }

    /** `POST .../audio/speech` */
    suspend fun createSpeech(requestBody: String): ByteArray {
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = apiUrl("audio/speech"),
            headers = authHeaders(),
            body = RequestBody.Json(requestBody)
        )
        return executeExpectOk(request).body
    }

    /**
     * 在已含 `/v1` 的基址后补齐请求路径。
     *
     * @param path `/v1` 之后的路径，例如 `"chat/completions"` / `"models"`
     */
    private fun apiUrl(path: String): String {
        val cleaned = path.trim().trimStart('/')
        require(cleaned.isNotEmpty()) { "API path must not be empty" }
        return "$baseUrl/$cleaned"
    }

    companion object {
        /**
         * 把用户填写的地址规范成 API 根：`.../v1`。
         *
         * - `https://api.openai.com` / `https://api.openai.com/` → `https://api.openai.com/v1`
         * - `https://api.openai.com/v1` / `https://api.openai.com/v1/` → `https://api.openai.com/v1`
         * - `https://proxy.example.com/openai/v1/` → `https://proxy.example.com/openai/v1`
         */
        internal fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.endsWith("/v1", ignoreCase = true)) {
                url = "$url/v1"
            }
            return url
        }
    }

    /** 关闭底层 HTTP 引擎，释放资源。 */
    fun close() {
        engine.close()
    }

    /**
     * API Key 为空时不带 `Authorization` 头——本地模型（Ollama / LM Studio 等）通常
     * 不需要鉴权，未填 Key 也能工作；云端服务缺 Key 会返回 401，由上层给出友好提示。
     */
    private fun authHeaders(): Map<String, String> =
        if (apiKey.isBlank()) {
            emptyMap()
        } else {
            mapOf("Authorization" to "Bearer $apiKey")
        }

    private suspend fun executeExpectText(request: HttpRequest): String {
        val response = executeExpectOk(request)
        return response.bodyAsText()
    }

    private suspend fun executeExpectOk(request: HttpRequest): HttpResponse {
        val response = engine.execute(request)
        if (response.statusCode !in 200..299) {
            // 用面向用户的友好文案（401 → 提示填写 API Key）
            throw ApiException(
                statusCode = response.statusCode,
                responseBody = response.bodyAsText(),
                message = ApiException.friendlyMessage(response.statusCode)
            )
        }
        return response
    }
}
