package com.meapet.mobile.client.model

import com.meapet.mobile.core.runCatchingLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI 兼容 API 响应解析辅助函数。
 *
 * 与 [ApiRequest] 对应：仅做 JSON 结构解析，不定义任何业务领域数据类。
 * 调用方将 [com.meapet.mobile.client.OpenAiCompatibleClient] 返回的原始 JSON 传入即可。
 */
object ApiResponse {

    private const val TAG = "ApiResponse"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从 `/v1/chat/completions` 响应中提取首个 choice 的 `message.content`。
     *
     * @return 消息文本；`choices` 为空、content 缺失或结构不符时返回 null
     */
    fun chatCompletionContent(body: String): String? = runCatchingLog(TAG) {
        json.parseToJsonElement(body).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
    }

    /**
     * 从 `/v1/models` 响应中提取模型 id 列表。
     *
     * 兼容常见 OpenAI 兼容网关：优先读 `data[].id`；若顶层是数组则直接读每项 `id`。
     * 结果去重、去空、按字母序排序。
     */
    fun modelIds(body: String): List<String> = try {
        val root = json.parseToJsonElement(body)
        val array: JsonArray = when (root) {
            is JsonObject -> root["data"] as? JsonArray ?: return emptyList()
            is JsonArray -> root
            else -> return emptyList()
        }
        array.mapNotNull { element ->
            (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }
}
