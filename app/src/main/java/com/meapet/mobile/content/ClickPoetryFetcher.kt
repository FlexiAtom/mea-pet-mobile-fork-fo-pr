package com.meapet.mobile.content

import com.meapet.mobile.client.HttpClientEngine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次点击走的取诗线路。
 *
 * 检索线由人工裁决（2026-10-18）定为**独占**这一发：开启且生效时优先走检索线，
 * 失败不回退随机线——"不静默换 hitokoto（那是另一档开关的行为）"。
 * 两线都活着时随机线抽不中，与桌面 `participating_sources` 的"生效判定"同形。
 */
enum class ClickPoetryLine { NONE, RANDOM, SEARCH }

/**
 * 检索线一次取文的结果。形状承桌面 `FeedbackResult` 的判语（feedback.py:54-66）：
 * **不存在** "line==null 且 failureReason 也为空" 的形状——"静默失败且不留下日志"
 * 正是被防的那件事。`line` 在爬虫线装的是**多行素材块**（交总结那发改写后才见天日）。
 */
data class SearchOutcome(val line: String?, val failureReason: String?) {
    val ok: Boolean get() = line != null

    companion object {
        fun success(line: String) = SearchOutcome(line, null)
        fun failure(reason: String) = SearchOutcome(null, reason.ifBlank { "未知原因" })
    }
}

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
 * 单源 hitokoto（[fetch]）无换源，失败即回退到"这一发不给诗"（返回 null），由调用侧决定展示；
 * 检索线（[fetchCrawler]）失败给出**必非空**的理由（[SearchOutcome]），由调用侧按人工裁决
 * 交还分区语音并报错——两条线各自失败，互不静默顶替。
 *
 * @param clock 时间源（默认 [System.currentTimeMillis]），单元测试注入虚拟时钟验证预算格。
 * @param logger 失败/预算日志出口（默认 no-op，生产由 AppContainer 注入打 Log 的实现）。
 */
class ClickPoetryFetcher(
    engine: HttpClientEngine,
    private val perAttemptTimeoutMillis: Long = 8_000,
    private val e2eBudgetMillis: Long = 8_000,
    private val maxAttempts: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
    private val wordPicker: (List<String>) -> String = { it.random() }
) {

    private val client = HitokotoClient(engine)
    private val crawlerClient = TavilyClient(engine)

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

    /**
     * 检索线（爬虫）：**随机抽一个关键词**（人工裁决 2026-10-18），一发 Tavily 网页检索，
     * 至多 [TavilyClient.MAX_ITEMS] 条素材编号拼成一块返回（交 [ClickPoetrySummarizer] 改写）。
     *
     * 源切换的来由（人工裁决 2026-10-19：「应当为获取源选择失误，这个场景不应该是
     * Wikisource」）：真机日志实证现代词在古籍库命中的全是判文书/公司名录页，命中校验
     * 按设计拦下——闸没坏，是库不对。爬虫线**没有命中校验**（PC `fetch_crawler_material`
     * 同形）：网页检索按相关性给素材，对不对由总结那发与隔离标记兜。
     *
     * 预算形状不变：端到端 deadline、每发取 `min(每档上限, 剩余)`，预算用完即断不再发。
     * 失败理由取 misses 前 3 条拼接，**必非空**；调用侧按人工裁决把这一发交还
     * 分区语音并报错，不静默。
     */
    suspend fun fetchCrawler(keywords: List<String>): SearchOutcome {
        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return SearchOutcome.failure("未配置关键词")
        val word = wordPicker(words)
        val deadline = clock() + e2eBudgetMillis
        val misses = mutableListOf<String>()
        val remaining = deadline - clock()
        if (remaining <= 0L) {
            val reason = "端到端预算（${e2eBudgetMillis}ms）用完"
            logger("检索线取文失败：$reason")
            return SearchOutcome.failure(reason)
        }
        val budget = minOf(perAttemptTimeoutMillis, remaining)
        var materials: List<String>? = null
        var completed = false
        withTimeoutOrNull(budget) {
            materials = crawlerClient.search(word) { misses.add(it) }
            completed = true
            true
        }
        if (!completed) misses.add("超时：「$word」${budget}ms 内未完成")
        val items = materials
        if (items != null && items.isNotEmpty()) {
            logger("检索线命中：「$word」素材 ${items.size} 条")
            return SearchOutcome.success(items.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n"))
        }
        val reason = if (misses.isEmpty()) "没有可用的检索结果" else misses.take(3).joinToString(" | ")
        logger("检索线取文失败：$reason")
        return SearchOutcome.failure(reason)
    }

    companion object {
        /**
         * 概率门控（开发者选项"单击取诗概率"）：roll ∈ [0, 100)，命中条件 roll < 概率。
         * 0 = 从不，100 = 每次。纯函数便于单测，随机数由调用侧注入。
         */
        fun rollHits(probabilityPercent: Int, roll: Int): Boolean = roll < probabilityPercent
    }
}
