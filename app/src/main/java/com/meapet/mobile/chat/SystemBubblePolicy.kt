package com.meapet.mobile.chat

/**
 * 系统气泡（Live2D 触摸分区提示）寿命调度策略。
 *
 * 纯逻辑、无协程/UI 依赖，便于单元测试。规则：
 * - 每个气泡初始寿命 [BASE_LIFE_MS]（7 秒）；
 * - 每次有新气泡加入、按 timestamp 重新排位后，位置靠后的气泡被「挤旧」，
 *   剩余寿命按 [REDUCE_STEP_MS]（2 秒）扣减，**累计最多扣 [MAX_REDUCE_COUNT]
 *   （2 次，共 4 秒）**，寿命下限 [MIN_LIFE_MS]（3 秒）——保证旧气泡不会
 *   因新气泡持续到来而被无限挤压至 0。
 */
object SystemBubblePolicy {

    /** 气泡初始寿命（ms）。 */
    const val BASE_LIFE_MS = 7_000L

    /** 位置靠后时每次扣减的寿命（ms）。 */
    const val REDUCE_STEP_MS = 2_000L

    /** 单个气泡最多被扣减的次数（7 秒 - 2×2 秒 = 3 秒保底）。 */
    const val MAX_REDUCE_COUNT = 2

    /** 气泡寿命下限（ms）。 */
    const val MIN_LIFE_MS = BASE_LIFE_MS - MAX_REDUCE_COUNT * REDUCE_STEP_MS

    /**
     * 按当前排位计算气泡的下一个剩余寿命。
     *
     * @param currentLifeMs 当前剩余寿命
     * @param reduceCount 本气泡已扣减次数（0 起步，上限 [MAX_REDUCE_COUNT]）
     * @param position 当前排位（1 = 最新，按 timestamp 降序）
     * @return Pair(新剩余寿命, 新的扣减次数)
     */
    fun computeNextLife(
        currentLifeMs: Long,
        reduceCount: Int,
        position: Int
    ): Pair<Long, Int> {
        var life = currentLifeMs
        var count = reduceCount
        // 位置被挤到 4 位及以后 且 还有扣减配额 时扣 2 秒
        if (position > 3 && count < MAX_REDUCE_COUNT) {
            life = (life - REDUCE_STEP_MS).coerceAtLeast(MIN_LIFE_MS)
            count++
        }
        return life to count
    }
}
