package com.meapet.mobile.content

/**
 * 检索命中校验：检索回来的条目**是不是查询要的那一首/那一个词条**。
 *
 * 移植桌面 `variants.py:69-88` 的 is_hit 形状（纯函数、比对侧去过标点空白并做
 * 简繁折叠），但判据按本案 §2 实测反证**加强**为：折叠后的 **title 含折叠后的查询**，
 * 且排除判决书/消歧页一类的噪声标题形状。理由（2026-10-18 开发机实发取证）：
 *
 * - 旧的"≥3 字连续片段"闸在 snippet 上判定，拦不住"名句被别篇原样引用"——
 *   《四季歌》歌词现文就含「只見床前明月光」，三窗口全中被放行；
 * - 同一查询下"…民事判决书"的简体正文原样含查询词，也被放行。
 * - 而查询**简体**、库条目**繁體**时，无折叠会把真古籍《靜夜思》误拦。
 *
 * 代价要说在前面：查询是**一句诗**（而非诗题/作者）时，title 不含该句，正确的
 * 命中也会被拒——与桌面判语同口径："宁可少给一句，不可念错一首"；本案里它还有
 * 人工裁决兜底（校验失败 → 回退分区语音并出声报错，不是静默念别的）。
 * 残余风险：真诗题恰好被别篇标题原样引用，或判决书标题本身含查询词——由噪声表拦一族常见形状，
 * 不承诺穷尽。
 */
object HitVerifier {

    /**
     * 比对用形状：逐字折回简体，再去掉保留集之外的字符（桌面 `variants.py:22` 同集），
     * ASCII 字母统一小写——检索线按设计接受任意关键词（含拉丁词），大小写之差不该误拦
     * 正确词条；汉字保留集不受 lowercaseChar 影响。
     */
    fun compact(text: String): String {
        val folded = ScriptFolding.toSimplified(text)
        return buildString(folded.length) {
            for (ch in folded) {
                if (ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' ||
                    ch in '\u3400'..'\u9FFF' || ch in '\uF900'..'\uFAFF'
                ) {
                    append(ch.lowercaseChar())
                }
            }
        }
    }

    /**
     * 这一条检索结果是否算命中查询。
     *
     * 判据只有两格：噪声形状先否掉，然后折叠 title 必须原样含折叠 query。
     * snippet 不参与判定（它正是"名句被别篇引用"混进来的那格），只作展示正文。
     */
    fun isHit(query: String, title: String): Boolean {
        val needle = compact(query)
        val haystack = compact(title)
        if (needle.isEmpty() || haystack.isEmpty()) return false
        if (isNoiseTitle(title)) return false
        return haystack.contains(needle)
    }

    /** 消歧页与判决书一族的标题形状（折叠后判断，覆盖繁两种写法）。 */
    private fun isNoiseTitle(title: String): Boolean {
        val folded = compact(title)
        return folded.contains("消歧") || folded.contains("判决书")
    }

    /**
     * 检索型古籍库该按什么顺序打：原样在前、繁體改写在后（去重）。
     * 桌面 `variants.py:91-103` 同形；移动端折叠表恒在，不存在"转换器缺席只剩原样"的降级档。
     */
    fun queryVariants(query: String): List<String> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val traditional = ScriptFolding.toTraditional(raw)
        return if (traditional != raw) listOf(raw, traditional) else listOf(raw)
    }
}
