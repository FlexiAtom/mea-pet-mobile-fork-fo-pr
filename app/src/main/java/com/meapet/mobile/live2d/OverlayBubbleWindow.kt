package com.meapet.mobile.live2d

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 悬浮窗模式下的气泡回复悬浮窗。
 *
 * 展示 AI 回复气泡，位于人物悬浮窗左侧或右侧（取离屏幕较远的一侧），
 * 气泡带小尾巴指向人物（聊天软件式），长文本自动换行。
 * - 窗口 [FLAG_NOT_TOUCHABLE]：完全不消费触摸，点击直接穿透到下层应用；
 * - 多个回复共用本窗口：最新气泡加在最底部，旧气泡被顶上，并在寿命末期
 *   逐渐变透明后移除；
 * - 气泡存活时间随文本长度变化（[computeDuration]）；
 * - 全部气泡清空后延迟销毁窗口——窗口本身透明无感，延迟销毁避免频繁重建
 *   （[EMPTY_DESTROY_DELAY_MS]）。
 *
 * @param context 上下文（Service 即可）
 */
@SuppressLint("ViewConstructor")
class OverlayBubbleWindow(context: Context) {

    companion object {
        private const val TAG = "OverlayBubbleWindow"

        /** 气泡最短存活时间，ms。 */
        private const val BASE_DURATION_MS = 2000L

        /** 每个字符额外存活时间，ms。 */
        private const val MS_PER_CHAR = 25L

        /** 气泡最长存活时间，ms。 */
        private const val MAX_DURATION_MS = 15000L

        /** 淡出动画时长，ms。 */
        private const val FADE_OUT_MS = 400L

        /** 无气泡后延迟销毁窗口的时长，ms。 */
        private const val EMPTY_DESTROY_DELAY_MS = 1000L

        /** 同时最多显示的气泡数，超出立即淡出最旧的，防窗口越摞越高。 */
        private const val MAX_BUBBLES = 4

        /** 气泡与人物悬浮窗的间距，dp。 */
        private const val GAP_DP = 12f

        /** 气泡最大宽度，dp（超长文本自动换行）。 */
        private const val MAX_BUBBLE_WIDTH_DP = 220f

        /** 气泡最大宽度占屏宽比例（兜底）。 */
        private const val MAX_WIDTH_FRACTION = 0.6f

        /** 气泡圆角半径，dp。 */
        private const val BUBBLE_CORNER_RADIUS_DP = 14f

        /** 尾巴长度，dp。 */
        private const val TAIL_LEN_DP = 12f
    }

    private val ctx: Context = context
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val palette = OverlayPalette.resolve(ctx)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 气泡堆栈：最新气泡在底部（gravity BOTTOM 使其自底向上排列）。 */
    private val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 完全透传：不接收任何触摸，命中本窗口的事件直接穿透给下层
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** 人物悬浮窗当前位置（由 Service 在拖动/缩放时推入）。 */
    private var anchor: Rect? = null

    /** 当前尾巴朝向（朝外指向人物），null 表示尚未定位。 */
    private var currentTail: TailBubbleDrawable.Side? = null

    /** 待执行的淡出任务（气泡 → Runnable），销毁时统一取消。 */
    private val fadeTasks = mutableListOf<Pair<TextView, Runnable>>()

    private var pendingDestroy = false

    private val destroyRunnable = Runnable {
        pendingDestroy = false
        destroy()
    }

    init {
        // WRAP_CONTENT 首次布局完才有实测宽高，统一在布局变化时重算位置
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    }

    /** 窗口是否已添加到 WindowManager。 */
    val isAdded: Boolean get() = container.isAttachedToWindow

    /** 更新锚点（人物悬浮窗矩形）；窗口已添加则立即重定位。 */
    fun setAnchor(rect: Rect) {
        anchor = rect
        if (isAdded) place()
    }

    /** 添加一条 AI 回复气泡。 */
    fun addBubble(text: String) {
        cancelPendingDestroy()

        val tail = anchor?.let(::desiredTail) ?: TailBubbleDrawable.Side.LEFT
        if (tail != currentTail) currentTail = tail

        val bubble = createBubble(text, tail)
        container.addView(bubble, bubbleLayoutParams())

        // 先有内容再加窗，避免空窗口闪在 (0,0)
        if (!isAdded) {
            try { windowManager.addView(container, params) } catch (e: Exception) {
                Log.w(TAG, "Failed to add bubble window: ${e.message}")
                return
            }
        }
        // 布局完成后重定位（WRAP_CONTENT 尺寸此刻才可用）
        container.post { place() }

        // 淡入
        bubble.alpha = 0f
        bubble.animate().alpha(1f).setDuration(200).start()

        // 寿命末期淡出后移除（占布局期间旧气泡被自然顶上，即"往上挤"）
        val fadeStart = (computeDuration(text) - FADE_OUT_MS).coerceAtLeast(0)
        val task = Runnable {
            bubble.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
                container.removeView(bubble)
                fadeTasks.removeAll { it.first === bubble }
                maybeScheduleDestroy()
            }.start()
        }
        fadeTasks.add(bubble to task)
        mainHandler.postDelayed(task, fadeStart)

        // 超上限：立即淡出最旧的
        while (container.childCount > MAX_BUBBLES) {
            val oldest = container.getChildAt(0) as? TextView ?: break
            removeOldest(oldest)
        }
    }

    /** 释放窗口（取消全部任务并移除）。 */
    fun destroy() {
        cancelPendingDestroy()
        fadeTasks.forEach { (_, r) -> mainHandler.removeCallbacks(r) }
        fadeTasks.clear()
        for (i in 0 until container.childCount) {
            container.getChildAt(i).animate().cancel()
        }
        container.removeAllViews()
        if (isAdded) {
            try { windowManager.removeView(container) } catch (_: Exception) {}
        }
    }

    // ================ 定位 ================

    private fun place() {
        val a = anchor ?: return
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return
        val dm = container.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val gap = dp(GAP_DP)

        // 左右侧：人物离哪边屏幕更远，气泡显示在哪边
        val sideRight = a.centerX() < screenW / 2
        params.x = if (sideRight) a.right + gap else a.left - w - gap
        params.x = params.x.coerceIn(0, (screenW - w).coerceAtLeast(0))

        // 垂直：气泡堆底部对齐人物垂直中心（最新气泡贴人物，旧的被顶上）
        params.y = a.centerY() - h
        params.y = params.y.coerceIn(0, (screenH - h).coerceAtLeast(0))

        // 人物移动后同步气泡尾巴朝向
        applyTail(a)
        try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
    }

    /** 气泡在人物的哪一侧 → 尾巴朝哪边（朝外指向人物）。 */
    private fun desiredTail(a: Rect): TailBubbleDrawable.Side {
        val screenW = container.resources.displayMetrics.widthPixels
        return if (a.centerX() < screenW / 2) {
            TailBubbleDrawable.Side.LEFT   // 气泡在右 → 尾巴朝左指人物
        } else {
            TailBubbleDrawable.Side.RIGHT  // 气泡在左 → 尾巴朝右指人物
        }
    }

    /** 侧边变化时统一更新所有气泡的尾巴与内边距。 */
    private fun applyTail(a: Rect) {
        val tail = desiredTail(a)
        if (tail == currentTail) return
        currentTail = tail
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i) as? TextView ?: continue
            v.background = bubbleDrawable(tail)
            setBubblePadding(v, tail)
        }
    }

    // ================ 生命周期 ================

    private fun maybeScheduleDestroy() {
        if (container.childCount > 0 || pendingDestroy || !isAdded) return
        pendingDestroy = true
        mainHandler.postDelayed(destroyRunnable, EMPTY_DESTROY_DELAY_MS)
    }

    private fun cancelPendingDestroy() {
        if (pendingDestroy) {
            pendingDestroy = false
            mainHandler.removeCallbacks(destroyRunnable)
        }
    }

    private fun removeOldest(bubble: TextView) {
        fadeTasks.firstOrNull { it.first === bubble }?.let { (_, r) ->
            mainHandler.removeCallbacks(r)
        }
        fadeTasks.removeAll { it.first === bubble }
        bubble.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
            container.removeView(bubble)
            maybeScheduleDestroy()
        }.start()
    }

    // ================ 视图构建 ================

    private fun createBubble(text: String, tail: TailBubbleDrawable.Side): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)  // bodyMedium
            setTextColor(palette.onSurfaceVariant)
            setLineSpacing(dp(7).toFloat(), 1f)  // ≈ 主界面 22sp 行高的宽松阅读
            background = bubbleDrawable(tail)
            setBubblePadding(this, tail)
            // 固定最大宽度 → 长文本自动换行，气泡不会无限变宽
            maxWidth = minOf(dp(MAX_BUBBLE_WIDTH_DP), (screenWidthPx() * MAX_WIDTH_FRACTION).toInt())
        }

    private fun bubbleDrawable(tail: TailBubbleDrawable.Side): TailBubbleDrawable =
        TailBubbleDrawable(
            fillColor = palette.surfaceVariant.withAlpha(0xF2),
            strokeColor = palette.onSurface.withAlpha(0x1A),
            strokeWidthPx = dp(1).toFloat(),
            cornerRadiusPx = dp(BUBBLE_CORNER_RADIUS_DP).toFloat(),
            tailLengthPx = dp(TAIL_LEN_DP).toFloat(),
            tailHalfWidthPx = dp(6).toFloat(),
            tailSide = tail,
        )

    /** 尾巴在尾巴侧占位，正文内边距要在该侧额外留出尾巴长度。 */
    private fun setBubblePadding(v: TextView, tail: TailBubbleDrawable.Side) {
        val padH = dp(16)
        val tailPad = dp(TAIL_LEN_DP)
        v.setPadding(
            if (tail == TailBubbleDrawable.Side.LEFT) padH + tailPad else padH,
            dp(10),
            if (tail == TailBubbleDrawable.Side.RIGHT) padH + tailPad else padH,
            dp(10)
        )
    }

    private fun bubbleLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

    /** 按回复长度计算气泡存活时间。 */
    private fun computeDuration(text: String): Long =
        (BASE_DURATION_MS + text.length * MS_PER_CHAR).coerceIn(BASE_DURATION_MS, MAX_DURATION_MS)

    private fun screenWidthPx(): Int = ctx.resources.displayMetrics.widthPixels

    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    private fun Int.withAlpha(a: Int): Int = this and 0x00FFFFFF or (a shl 24)
}
