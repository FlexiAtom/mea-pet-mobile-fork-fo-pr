package com.meapet.mobile.live2d.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
 * 悬浮窗模式下的独立菜单悬浮窗。
 *
 * 平时隐藏，双击人物悬浮窗唤起，无操作 [AUTO_HIDE_MS] 后自动隐藏。
 * 紧凑竖排小面板，贴着人物悬浮窗的侧面（离屏幕较远的一侧）：
 * - ✕ 关闭菜单
 * - 🚪 关闭悬浮窗
 * - 💬 唤起输入
 *
 * @param context 上下文（Service 即可）
 * @param onCloseOverlay 关闭悬浮窗回调（停止整个前台服务）
 * @param onOpenInput 唤起输入框回调
 */
@SuppressLint("ViewConstructor")
class OverlayMenuWindow(
    context: Context,
    private val onCloseOverlay: () -> Unit,
    private val onOpenInput: () -> Unit,
) {
    companion object {
        private const val TAG = "OverlayMenuWindow"

        /** 无操作多少毫秒后自动隐藏。 */
        private const val AUTO_HIDE_MS = 5000L

        /** 与人物悬浮窗之间的间距，dp。 */
        private const val GAP_DP = 4f

        /** 面板圆角半径，dp。 */
        private const val CORNER_RADIUS_DP = 14f

        /** 每行高度，dp。 */
        private const val ITEM_HEIGHT_DP = 34f

        private const val CLOSE_OVERLAY_EMOJI = "🚪"
        private const val OPEN_INPUT_EMOJI = "💬"

        private const val CLOSE_OVERLAY_LABEL = "关闭悬浮窗"
        private const val OPEN_INPUT_LABEL = "唤起输入"
    }

    private val ctx: Context = context
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val palette = OverlayPalette.resolve(ctx)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rootView: LinearLayout
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 不抢焦点但可点击；窗外交互仍可穿透给下层应用
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** 全屏透明遮罩：菜单弹出期间点击任意空白处自动关闭。 */
    private val scrimView = View(ctx).apply {
        // 不画任何内容；命中触摸即收起菜单（消费该次触摸，不透传给下层）。
        // 延迟到触摸分发结束后再移除，避免在事件分发中移除自身窗口导致崩溃。
        setOnTouchListener { _, _ ->
            mainHandler.post { hide() }
            true
        }
    }
    private val scrimParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** 当前锚点（人物悬浮窗矩形）；null 表示尚未定位。 */
    private var anchor: Rect? = null

    /** 是否处于"显示中"状态（入场动画为 post 延迟执行，用此标记避免被 hide 打断后仍播放）。 */
    private var showing = false

    private val autoHideRunnable = Runnable { hide() }

    init {
        rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 紧凑圆角卡片（无尾巴），与主界面同源的表面色
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant.withAlpha(0xF2))
                cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                setStroke(dp(1), palette.onSurface.withAlpha(0x1A))
            }
            elevation = 8f * density
            setPadding(dp(6), dp(4), dp(6), dp(4))
            // 触摸面板任意处重置自动隐藏定时器（返回 false 让事件继续传给子项）
            setOnTouchListener { _, _ ->
                resetAutoHide()
                false
            }
            addView(menuItemRow(CLOSE_OVERLAY_EMOJI, CLOSE_OVERLAY_LABEL) {
                hide()
                onCloseOverlay()
            })
            addView(menuItemRow(OPEN_INPUT_EMOJI, OPEN_INPUT_LABEL) {
                hide()
                onOpenInput()
            })
        }
        // WRAP_CONTENT 首次布局完才有实测宽高，统一在布局变化时重新定位
        rootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    }

    /** 是否已显示在屏幕上。 */
    val isVisible: Boolean get() = rootView.isAttachedToWindow

    /** 在人物悬浮窗侧面显示菜单并启动自动隐藏计时。 */
    fun show(anchorRect: Rect) {
        anchor = anchorRect
        showing = true
        // 先加全屏遮罩，再加菜单面板 → 菜单在上，遮罩兜住面板外的点击
        if (!scrimView.isAttachedToWindow) {
            try { windowManager.addView(scrimView, scrimParams) } catch (_: Exception) {}
        }
        if (!isVisible) {
            // 入场初值：0.85 中心缩放 + 透明（对齐关于卡片动画）
            rootView.scaleX = 0.85f
            rootView.scaleY = 0.85f
            rootView.alpha = 0f
            try { windowManager.addView(rootView, params) } catch (e: Exception) {
                Log.w(TAG, "Failed to show menu window: ${e.message}")
            }
        }
        place()
        // 等布局完成拿到真实宽高（pivot 居中）再播入场动画
        rootView.post {
            if (showing && rootView.isAttachedToWindow) {
                rootView.pivotX = rootView.width / 2f
                rootView.pivotY = rootView.height / 2f
                rootView.animate().cancel()
                rootView.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200)
                    .start()
            }
        }
        resetAutoHide()
    }

    /** 隐藏菜单（同时移除遮罩）：退场播放中心缩放 + 淡出动画，播完再移除窗口。 */
    fun hide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        showing = false
        // 遮罩不参与动画，立即移除（它正在拦截触摸）
        if (scrimView.isAttachedToWindow) {
            try { windowManager.removeView(scrimView) } catch (_: Exception) {}
        }
        if (isVisible) {
            rootView.pivotX = rootView.width / 2f
            rootView.pivotY = rootView.height / 2f
            rootView.animate().cancel()
            rootView.animate()
                .scaleX(0.85f).scaleY(0.85f).alpha(0f)
                .setDuration(200)
                .withEndAction {
                    if (rootView.isAttachedToWindow) {
                        try { windowManager.removeView(rootView) } catch (_: Exception) {}
                    }
                }
                .start()
        }
    }

    /** 人物悬浮窗移动后跟随定位。 */
    fun reposition(anchorRect: Rect) {
        anchor = anchorRect
        if (isVisible) place()
    }

    /** 释放窗口（等同 hide 并清除锚点）。 */
    fun destroy() {
        anchor = null
        hide()
    }

    // ================ 定位 ================

    private fun place() {
        val a = anchor ?: return
        val w = rootView.width
        val h = rootView.height
        if (w <= 0 || h <= 0) return
        // 中心缩放动画的锚点
        rootView.pivotX = w / 2f
        rootView.pivotY = h / 2f
        val dm = rootView.resources.displayMetrics
        val gap = dp(GAP_DP)

        // 贴着人物侧面：人物偏左 → 面板在右；人物偏右 → 面板在左
        val sideRight = a.centerX() < dm.widthPixels / 2
        params.x = if (sideRight) a.right + gap else a.left - w - gap
        params.x = params.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        // 垂直与人物中心对齐，钳制在屏内
        params.y = (a.centerY() - h / 2).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))

        try { windowManager.updateViewLayout(rootView, params) } catch (_: Exception) {}
    }

    private fun resetAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_MS)
    }

    // ================ 视图构建 ================

    private fun menuItemRow(emoji: String, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(8), 0, dp(8), 0)
            // 按压水波纹反馈（行背景，不影响面板卡片背景）
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }

            addView(
                TextView(ctx).apply {
                    text = emoji
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(24), dp(24))
            )
            addView(
                TextView(ctx).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.onSurface)
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(ITEM_HEIGHT_DP)
            )
        }

    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    /** 替换颜色最高位 alpha 通道。 */
    private fun Int.withAlpha(a: Int): Int = this and 0x00FFFFFF or (a shl 24)
}
