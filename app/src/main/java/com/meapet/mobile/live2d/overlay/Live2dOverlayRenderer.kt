package com.meapet.mobile.live2d.overlay

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid
import com.meapet.mobile.live2d.Live2dModel
import com.meapet.mobile.live2d.Live2dPal
import com.meapet.mobile.live2d.Live2dTextureManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 悬浮窗 GLSurfaceView 渲染器（Live2D 模型 + 透明背景）。
 *
 * 从 [FloatingLive2dService] 抽出独立，避免 Service 类膨胀。
 * 通过回调解耦：
 * - [isShuttingDown]：GL 线程每帧检查，服务关闭时立刻停止绘制（防竞态崩溃）；
 * - [onLoadFailed]：模型加载失败后由 Service 停掉自身（主线程回调）。
 *
 * @param isShuttingDown 服务是否正在关闭（@Volatile，GL 线程读取）
 * @param onLoadFailed 模型加载失败后的收尾回调（主线程调用）
 */
class Live2dOverlayRenderer(
    private val isShuttingDown: () -> Boolean,
    private val onLoadFailed: () -> Unit
) : GLSurfaceView.Renderer {

    private var model: Live2dModel? = null
    private var textureManager: Live2dTextureManager? = null
    private val projection = CubismMatrix44.create()
    private val viewMatrix = CubismMatrix44.create()
    private var winWidth = 0
    private var winHeight = 0
    private var modelLoaded = false

    /**
     * 释放模型持有的 native 内存（moc/model）与 renderer。
     * 必须在 GL 线程调用（经 GLSurfaceView.queueEvent），且需在 onPause
     * 释放 EGL 上下文之前入队，保证 renderer 的 GL 删除操作仍有有效上下文。
     */
    fun releaseModel() {
        model?.deleteModel()
        model = null
        modelLoaded = false
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        Log.d(TAG, "Overlay onSurfaceCreated")

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Re-initialize shader manager for THIS GL context
        CubismShaderAndroid.getInstance().releaseInvalidShaderProgram()
        CubismShaderAndroid.deleteInstance()

        if (!CubismFramework.isInitialized()) {
            CubismFramework.initialize()
            Log.d(TAG, "CubismFramework initialized for overlay")
        }

        textureManager = Live2dTextureManager()
        // surface 重建时旧 GL 上下文已销毁（GL 资源随之释放），
        // 但 moc/model 的 native 内存仍需显式释放后再重新加载
        releaseModel()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        winWidth = width
        winHeight = height
        Log.d(TAG, "Overlay onSurfaceChanged: ${width}x$height")
    }

    override fun onDrawFrame(unused: GL10?) {
        try {
            // 服务正在关闭时不执行任何 GL 操作，防止竞态崩溃
            if (isShuttingDown()) return

            if (!modelLoaded) {
                loadModel()
                modelLoaded = true
                if (isShuttingDown()) return
            }

            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            val m = model ?: return
            if (winWidth <= 0 || winHeight <= 0) return

            Live2dPal.updateTime()

            val aspect = winWidth.toFloat() / winHeight.toFloat()
            val displayRatio = winHeight.toFloat() / winWidth.toFloat()

            CubismOffscreenManagerAndroid.getInstance().beginFrameProcess()
            projection.loadIdentity()

            val zoom = 1.4f
            val core = m.model ?: return
            val mm = m.modelMatrix ?: return
            val canvasRatio = core.canvasHeight / core.canvasWidth
            if (canvasRatio < displayRatio) {
                mm.setWidth(2.0f * zoom)
                projection.scale(1.0f, aspect)
                projection.translateRelative(0f, -0.35f)
            } else {
                mm.setHeight(2.0f * zoom)
                projection.scale(1.0f / aspect, 1.0f)
                projection.translateRelative(0f, -0.35f)
            }

            viewMatrix.multiplyByMatrix(projection)
            m.update()
            m.draw(projection)

            CubismOffscreenManagerAndroid.getInstance().endFrameProcess()
            CubismOffscreenManagerAndroid.getInstance().releaseStaleRenderTextures()
        } catch (e: Exception) {
            Log.e(TAG, "Render error: ${e.message}")
        }
    }

    private fun loadModel() {
        try {
            val dir = "live2d/mea_live2d/"
            val fileName = "mea.model3.json"
            val m = Live2dModel(dir)
            val tm = textureManager ?: return
            m.loadAssets(dir, fileName)
            m.bindTextures(tm)
            model = m
            Log.d(TAG, "Overlay model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load overlay model", e)
            // 释放可能已部分构建的模型，并停掉服务——
            // 留着空窗口只会吞触摸且无法关闭
            releaseModel()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onLoadFailed()
            }
        }
    }

    private companion object {
        const val TAG = "Live2dOverlayRenderer"
    }
}
