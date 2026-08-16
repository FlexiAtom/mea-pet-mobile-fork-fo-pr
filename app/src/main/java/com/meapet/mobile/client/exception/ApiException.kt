package com.meapet.mobile.client.exception

/**
 * OpenAI 兼容 API 的统一异常封装。
 *
 * @property statusCode HTTP 状态码
 * @property responseBody 服务端返回的原始响应体（JSON 字符串）
 */
class ApiException(
    val statusCode: Int,
    val responseBody: String,
    message: String
) : RuntimeException(message) {

    companion object {
        /** 常见状态码的面向用户友好提示。 */
        fun friendlyMessage(statusCode: Int): String = when (statusCode) {
            401 -> "API Key 无效或未填写，请在设置中填写正确的 API Key"
            402 -> "API 余额不足，请充值后再试"
            403 -> "API Key 无权限访问，请检查密钥权限"
            404 -> "接口地址不存在，请检查 API 地址是否填写正确"
            429 -> "请求过于频繁，请稍后再试"
            else -> "API 请求失败（HTTP $statusCode）"
        }
    }
}
