package com.meapet.mobile.live2d

import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.math.CubismViewMatrix
import com.live2d.sdk.cubism.framework.rendering.android.CubismRenderTargetAndroid

/**
 * Manages view transforms and optional offscreen render targets.
 */
class Live2dView : AutoCloseable {

    enum class RenderingTarget {
        NONE,
        MODEL_FRAME_BUFFER,
        VIEW_FRAME_BUFFER
    }

    var renderingTarget = RenderingTarget.NONE
        private set

    private val deviceToScreen = CubismMatrix44.create()
    private val viewMatrix = CubismViewMatrix()
    private val clearColor = floatArrayOf(1.0f, 1.0f, 1.0f, 0.0f)
    private val renderingBuffer = CubismRenderTargetAndroid()
    private var renderingSprite: Live2dSprite? = null
    private var spriteShader: Live2dSpriteShader? = null

    override fun close() {
        renderingBuffer.destroyRenderTarget()
        renderingSprite = null
        spriteShader?.close()
        spriteShader = null
    }

    fun initialize() {
        val delegate = Live2dDelegate.getInstance()
        val width = delegate.windowWidth
        val height = delegate.windowHeight

        val ratio = width.toFloat() / height.toFloat()
        val left = -ratio
        val right = ratio
        val bottom = Live2dDefine.LogicalView.BOTTOM
        val top = Live2dDefine.LogicalView.TOP

        viewMatrix.setScreenRect(left, right, bottom, top)
        viewMatrix.scale(Live2dDefine.Scale.DEFAULT, Live2dDefine.Scale.DEFAULT)

        deviceToScreen.loadIdentity()
        if (width > height) {
            val screenW = kotlin.math.abs(right - left)
            deviceToScreen.scaleRelative(screenW / width, -screenW / width)
        } else {
            val screenH = kotlin.math.abs(top - bottom)
            deviceToScreen.scaleRelative(screenH / height, -screenH / height)
        }
        deviceToScreen.translateRelative(-width * 0.5f, -height * 0.5f)

        viewMatrix.setMaxScale(Live2dDefine.Scale.MAX)
        viewMatrix.setMinScale(Live2dDefine.Scale.MIN)

        viewMatrix.setMaxScreenRect(
            Live2dDefine.LogicalView.LEFT,
            Live2dDefine.LogicalView.RIGHT,
            Live2dDefine.LogicalView.BOTTOM,
            Live2dDefine.MaxLogicalView.TOP
        )

        spriteShader = Live2dSpriteShader()
    }

    fun initializeSprite() {
        val delegate = Live2dDelegate.getInstance()
        val w = delegate.windowWidth.toFloat()
        val h = delegate.windowHeight.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f

        val shader = spriteShader ?: return
        if (renderingSprite == null) {
            renderingSprite = Live2dSprite(cx, cy, w, h, 0, shader.programId)
        } else {
            renderingSprite?.resize(cx, cy, w, h)
        }
    }

    fun render() {
        val delegate = Live2dDelegate.getInstance()
        val w = delegate.windowWidth
        val h = delegate.windowHeight

        Live2dManager.getInstance().onUpdate()

        if (renderingTarget == RenderingTarget.MODEL_FRAME_BUFFER && renderingSprite != null) {
            val uv = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)
            val model = Live2dManager.getInstance().model ?: return
            val sprite = renderingSprite ?: return
            val alpha = getSpriteAlpha(2)

            sprite.setColor(1.0f, 1.0f, 1.0f, alpha)
            sprite.setWindowSize(w, h)
            sprite.renderImmediate(model.renderingBuffer.colorBuffer[0], uv)
        }
    }

    fun switchRenderingTarget(target: RenderingTarget) {
        renderingTarget = target
    }

    /** Set background clear color (r,g,b in 0..1) */
    fun setClearColor(r: Float, g: Float, b: Float) {
        clearColor[0] = r
        clearColor[1] = g
        clearColor[2] = b
    }

    // ---- touch handling (delegate handles directly via normalized coords) ----

    // ---- pre/post model draw hooks for offscreen rendering ----

    fun preModelDraw(refModel: Live2dModel) {
        if (renderingTarget == RenderingTarget.NONE) return

        val target = when (renderingTarget) {
            RenderingTarget.VIEW_FRAME_BUFFER -> renderingBuffer
            RenderingTarget.MODEL_FRAME_BUFFER -> refModel.renderingBuffer
            else -> return
        }

        if (!target.isValid) {
            val w = Live2dDelegate.getInstance().windowWidth
            val h = Live2dDelegate.getInstance().windowHeight
            target.createRenderTarget(w, h, null)
        }
        target.beginDraw()
        target.clear(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
    }

    fun postModelDraw(refModel: Live2dModel) {
        if (renderingTarget == RenderingTarget.NONE) return

        val target = when (renderingTarget) {
            RenderingTarget.VIEW_FRAME_BUFFER -> renderingBuffer
            RenderingTarget.MODEL_FRAME_BUFFER -> refModel.renderingBuffer
            else -> return
        }

        target.endDraw()

        if (renderingTarget == RenderingTarget.VIEW_FRAME_BUFFER && renderingSprite != null) {
            val uv = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)
            val sprite = renderingSprite ?: return
            val alpha = getSpriteAlpha(0)
            sprite.setColor(1.0f, 1.0f, 1.0f, alpha)
            sprite.setWindowSize(
                Live2dDelegate.getInstance().windowWidth,
                Live2dDelegate.getInstance().windowHeight
            )
            sprite.renderImmediate(target.colorBuffer[0], uv)
        }
    }

    // ---- coordinate transforms ----

    fun transformViewX(deviceX: Float): Float {
        return viewMatrix.invertTransformX(deviceToScreen.transformX(deviceX))
    }

    fun transformViewY(deviceY: Float): Float {
        return viewMatrix.invertTransformY(deviceToScreen.transformY(deviceY))
    }

    private fun getSpriteAlpha(assign: Int): Float {
        return (0.25f + assign * 0.5f).coerceIn(0.1f, 1.0f)
    }
}
