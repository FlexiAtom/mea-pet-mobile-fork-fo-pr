package com.meapet.mobile.content

import com.meapet.mobile.client.HttpClientEngine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "点一下取一句诗"的超时预算与回退控制。
 *
 * 承桌面侧移交的两条判语（见提案 click-feedback-content-sources-from-pc §0.2）：
 *
 * 1. **超时两层，端到端那条线跨重试共用。** [perAttemptTimeoutMillis] 封顶每一次请求，
 *    [e2eBudgetMillis] 是所有 [maxAttempts] 次共用的一条 deadline；每次取文预算取
 *    `min(每档上限, 端到端剩余)`，预算当场用完时经 [logger] 留一行"端到端预算用完"，
 *    不静默截断。库层超时封顶不了"点一下等多久"（实测有 7.25s 后才报 200 的慢滴响应），
 *    所以端到端这条线必需。
 * 2. 重试只跨时间、不跨解析结果——Ktor CIO 无等价原语，移动端**不硬造**（提案 T3 判语保留）。
 *
 * 单源 hitokoto 无换源，失败即回退到"这一发不给诗"（返回 null），由调用侧决定展示。
 *
 * @param clock 时间源（默认 [System.currentTimeMillis]），单元测试注入虚拟时钟验证预算格。
 * @param logger 失败/预算日志出口（默认 no-op，生产由 AppContainer 注入打 Log 的实现）。
 */
class ClickPoetryFetcher(
    engine: HttpClientEngine,
    private val perAttemptTimeoutMillis: Long = 3_000,
    private val e2eBudgetMillis: Long = 8_000,
    private val maxAttempts: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {}
) {

    private val client = HitokotoClient(engine)

    /**
     * 在共享端到端预算内取一句诗，成功返回可直接展示的文本，失败/预算耗尽返回 null。
     */
    suspend fun fetch(): String? {
        val deadline = clock() + e2eBudgetMillis
        repeat(maxAttempts) { attempt ->
            val remaining = deadline - clock()
            if (remaining <= 0L) {
                logger("点击取诗端到端预算（${e2eBudgetMillis}ms）用完，第 ${attempt + 1} 次尝试前放弃")
                return null
            }
            val budget = minOf(perAttemptTimeoutMillis, remaining)
            val poem = withTimeoutOrNull(budget) { client.fetch() }
            if (poem != null) return poem.toDisplayLine()
            logger("点击取诗第 ${attempt + 1}/$maxAttempts 发未命中（预算 ${budget}ms）")
        }
        return null
    }

    companion object {
        /**
         * 概率门控（开发者选项"单击取诗概率"）：roll ∈ [0, 100)，命中条件 roll < 概率。
         * 0 = 从不，100 = 每次。纯函数便于单测，随机数由调用侧注入。
         */
        fun rollHits(probabilityPercent: Int, roll: Int): Boolean = roll < probabilityPercent
    }
}
