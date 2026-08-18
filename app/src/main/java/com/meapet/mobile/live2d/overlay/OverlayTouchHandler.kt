package com.meapet.mobile.live2d.overlay

import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.sqrt

/**
 * 悬浮窗触摸/手势处理器。
 *
 * 从 [FloatingLive2dService] 抽出独立，负责拖动 / 捏合缩放 / 轻触判定。
 * 窗口参数与视图通过回调从 Service 获取；位移/缩放变化后回调 [onWindowChanged]
 * 让 Service 同步气泡/菜单锚点；判定为轻触时回调 [onTap]（双击/三击语义在调用方）。
 *
 * @param density 屏幕密度（px/dp）
 * @param resources 用于屏幕边界钳制（防止窗口拖出屏幕）
 * @param layoutParams 当前悬浮窗布局参数（可变，位移/尺寸直接写回）
 * @param windowManager 窗口管理器
 * @param glSurfaceView 悬浮窗承载视图（[WindowManager.updateViewLayout] 目标）
 * @param onWindowChanged 窗口位置/尺寸变化后的同步回调
 * @param onTap 轻触回调（距离小于 [TAP_SLOP_DP] 判定）
 */
class OverlayTouchHandler(
    private val density: Float,
    private val resources: Resources,
    private val layoutParams: () -> WindowManager.LayoutParams?,
    private val windowManager: () -> WindowManager?,
    private val glSurfaceView: () -> View?,
    private val onWindowChanged: () -> Unit,
    private val onTap: () -> Unit
) {
    companion object {
        /** 判定为轻触（而非拖动）的最大位移，单位 dp。 */
        private const val TAP_SLOP_DP = 24f
    }

    private val minWinPx: Int get() = (100 * density).toInt()
    private val maxWinPx: Int get() = (600 * density).toInt()
    private val baseAspect: Float get() = 150f / 218f  // width / height

    // ----- touch state -----
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f

    private var pinchStartDist = 0f
    private var pinchStartW = 0
    private var pinchStartH = 0

    fun handleTouch(event: MotionEvent) {
        val params = layoutParams() ?: return
        val wm = windowManager() ?: return
        val view = glSurfaceView() ?: return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // First finger down → prepare for drag or tap
                dragStartX = params.x
                dragStartY = params.y
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down → prepare for pinch
                if (event.pointerCount == 2) {
                    pinchStartDist = calcPointerDistance(event)
                    pinchStartW = params.width
                    pinchStartH = params.height
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && pinchStartDist > 0f) {
                    // --- PINCH: resize window ---
                    val curDist = calcPointerDistance(event)
                    val ratio = curDist / pinchStartDist
                    var newW = (pinchStartW * ratio).toInt().coerceIn(minWinPx, maxWinPx)
                    // Preserve aspect ratio
                    var newH = (newW / baseAspect).toInt().coerceIn(minWinPx, maxWinPx)
                    // Re-derive width from height to keep exact aspect
                    newW = (newH * baseAspect).toInt().coerceIn(minWinPx, maxWinPx)
                    params.width = newW
                    params.height = newH
                    wm.updateViewLayout(view, params)
                    onWindowChanged()
                } else {
                    // --- DRAG: move window ---
                    // 钳制在屏幕范围内（留出窗口自身尺寸），防止拖出屏幕后找不回
                    val dm = resources.displayMetrics
                    params.x = (dragStartX + (event.rawX - dragStartRawX).toInt())
                        .coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (dragStartY + (event.rawY - dragStartRawY).toInt())
                        .coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
                    wm.updateViewLayout(view, params)
                    onWindowChanged()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 捏合结束（两指 → 一指）：把拖动锚点重置到剩余手指的当前位置，
                // 否则下一个 MOVE 会按 ACTION_DOWN 时的旧锚点计算，窗口猛跳
                if (event.pointerCount == 2) {
                    pinchStartDist = 0f
                    // rawX/rawY 只对 pointer 0 提供，用「raw − 视图内坐标」求出
                    // 窗口到屏幕的偏移，再换算出剩余手指的屏幕坐标
                    val offX = event.rawX - event.getX(0)
                    val offY = event.rawY - event.getY(0)
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    dragStartX = params.x
                    dragStartY = params.y
                    dragStartRawX = event.getX(remaining) + offX
                    dragStartRawY = event.getY(remaining) + offY
                }
            }

            MotionEvent.ACTION_UP -> {
                // 轻触判定：位移在 TAP_SLOP 内视为轻触，交由调用方处理（双击/三击）
                pinchStartDist = 0f
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (sqrt((dx * dx + dy * dy).toDouble()) < TAP_SLOP_DP * density) {
                    onTap()
                }
            }
        }
    }

    /** Distance between the two pointers using getX/Y (works without rawX pointer overload). */
    private fun calcPointerDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}
