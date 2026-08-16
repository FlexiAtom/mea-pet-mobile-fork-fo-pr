package com.meapet.mobile.live2d

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 带小尾巴的气泡背景。
 *
 * 聊天气泡与菜单面板共用：圆角矩形主体 + 指向一侧（人物悬浮窗）的三角形尾巴。
 * 尾巴位于 drawable 边界内（主体向尾巴侧让出 [tailLengthPx]），因此视图内容
 * 需要在该侧额外加上 [tailLengthPx] 的内边距，避免文字/按钮压到尾巴。
 *
 * @param fillColor 填充色
 * @param strokeColor 描边色
 * @param strokeWidthPx 描边宽度
 * @param cornerRadiusPx 圆角半径
 * @param tailLengthPx 尾巴长度（主体向内让出的宽度）
 * @param tailHalfWidthPx 尾巴高度的一半
 * @param tailSide 尾巴朝向（朝外指向人物）
 */
class TailBubbleDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val strokeWidthPx: Float,
    private val cornerRadiusPx: Float,
    private val tailLengthPx: Float,
    private val tailHalfWidthPx: Float,
    private val tailSide: Side,
) : Drawable() {

    enum class Side { LEFT, RIGHT }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = strokeWidthPx
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        buildPath()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    private fun buildPath() {
        path.reset()
        val b = bounds
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.right.toFloat()
        val bottom = b.bottom.toFloat()
        val cy = (top + bottom) / 2f

        // 主体圆角矩形向尾巴侧让出尾巴长度
        val bodyLeft = left + (if (tailSide == Side.LEFT) tailLengthPx else 0f)
        val bodyRight = right - (if (tailSide == Side.RIGHT) tailLengthPx else 0f)
        path.addRoundRect(
            RectF(bodyLeft, top, bodyRight, bottom),
            cornerRadiusPx, cornerRadiusPx, Path.Direction.CW
        )

        // 尾巴三角形：尖朝外，底贴在主体边缘
        if (tailSide == Side.LEFT) {
            path.moveTo(left, cy)
            path.lineTo(bodyLeft, cy - tailHalfWidthPx)
            path.lineTo(bodyLeft, cy + tailHalfWidthPx)
        } else {
            path.moveTo(right, cy)
            path.lineTo(bodyRight, cy - tailHalfWidthPx)
            path.lineTo(bodyRight, cy + tailHalfWidthPx)
        }
        path.close()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
