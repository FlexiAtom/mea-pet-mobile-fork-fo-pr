package com.meapet.mobile.live2d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid
import com.meapet.mobile.framework.MeaPetApplication
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that renders the Live2D model in a transparent floating window.
 * - Drag to move
 * - Pinch to resize
 * - Double-tap to open menu (关闭菜单 / 关闭悬浮窗 / 唤起输入框)
 * - 输入框发消息走主界面同一 chat 包，回复以气泡显示在人物旁
 */
class FloatingLive2dService : Service() {

    companion object {
        private const val TAG = "FloatingLive2d"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "live2d_overlay"

        /** 判定为轻触（而非拖动）的最大位移，单位 dp。 */
        private const val TAP_SLOP_DP = 24f

        /** 连击判定时间窗，ms（两次轻触间隔超过则重新计数）。 */
        private const val TAP_INTERVAL_MS = 500L

        /** 双击后延迟开菜单的确认时长，ms——留出时间判断是否会有第三击（三击关悬浮窗）。 */
        private const val TAP_CONFIRM_MS = 250L

        /** Whether the overlay is currently active. */
        @Volatile
        var overlayActive = false

        /** Whether the overlay was active; checked to reset GL state after close. */
        @Volatile
        var wasActive = false

        /** Service 是否存活（onCreate → onDestroy 之间）。与 [overlayActive] 不同，
         *  本标记只由 Service 自身在主线程读写，是判断"Service 还在用共享单例"的可靠依据。 */
        @Volatile
        var isRunning = false
            private set

        /** MainActivity 销毁时因 Service 仍在运行而跳过了共享单例的全局 dispose，
         *  置位此标记，由 Service onDestroy 收尾。两个 onDestroy 都在主线程回调，无并发。 */
        @Volatile
        var pendingSharedDispose = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingLive2dService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingLive2dService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private var renderer: FloatingRenderer? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // ----- touch state -----
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f

    private var pinchStartDist = 0f
    private var pinchStartW = 0
    private var pinchStartH = 0

    /** 主线程 Handler（延迟任务：开菜单确认等）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 轻触计数与时间戳（区分双击开菜单 / 三击关悬浮窗）。 */
    private var lastTapTime = 0L
    private var tapCount = 0
    private val pendingShowMenu = Runnable { showMenu() }

    /** 悬浮窗子窗口相关协程作用域（Main），onDestroy 时取消。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ----- 悬浮窗子窗口：菜单 / 输入框 / 气泡回复 -----
    private var menuWindow: OverlayMenuWindow? = null
    private var inputWindow: OverlayInputWindow? = null
    private var bubbleWindow: OverlayBubbleWindow? = null

    /** Screen-density conversion cache */
    private var _density = 0f
    private val density: Float get() {
        if (_density == 0f) _density = resources.displayMetrics.density
        return _density
    }
    private val minWinPx: Int get() = (100 * density).toInt()
    private val maxWinPx: Int get() = (600 * density).toInt()
    private val baseAspect: Float get() = 150f / 218f  // width / height

    override fun onCreate() {
        super.onCreate()
        wasActive = false
        overlayActive = true
        isRunning = true
        // 提供 application context，Activity 已销毁时悬浮窗仍能加载模型资源
        Live2dDelegate.getInstance().attachContext(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        // 无悬浮窗权限时（如权限被用户在后台撤销后服务被重启）直接退出，
        // 否则会留下一个加不上视图/吞触摸的空壳服务
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing, stopping service")
            overlayActive = false
            stopSelf()
            return
        }
        createFloatingWindow()
        initOverlayWindows()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 返回 [START_NOT_STICKY]：进程被系统杀死后 CubismFramework 未 startUp、
     * Live2dDelegate 也没有 Activity，自动重启只会得到一个模型加载失败、
     * 只吞触摸的隐形窗口，因此不做自动重启，由用户手动重新打开。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: flags=$flags startId=$startId")
        return START_NOT_STICKY
    }

    /** 标记服务正在关闭，GL 线程应立刻停止工作。 */
    @Volatile
    private var shuttingDown = false

    override fun onDestroy() {
        isRunning = false
        overlayActive = false
        shuttingDown = true
        // 收尾子窗口：先取消协程与延迟任务、隐藏输入框（收起键盘），再逐个销毁，
        // 避免服务销毁后 Handler 任务仍引用已移除的视图
        serviceScope.cancel()
        mainHandler.removeCallbacks(pendingShowMenu)
        inputWindow?.hide()
        inputWindow = null
        menuWindow?.destroy()
        menuWindow = null
        bubbleWindow?.destroy()
        bubbleWindow = null
        if (::glSurfaceView.isInitialized) {
            // 先在 GL 线程释放模型的 native 资源。事件在 onPause 之前入队：
            // GLSurfaceView 的 GL 线程按序处理事件队列且优先于暂停处理，
            // 因此 releaseModel 执行时 EGL 上下文仍然有效
            glSurfaceView.queueEvent { renderer?.releaseModel() }
            // onPause 会阻塞到 GL 线程完成当前帧并暂停，此后本服务不再发出任何 GL 调用
            glSurfaceView.onPause()
            // 延迟移除视图——SurfaceView 可能有未完成的绘制回调，
            // 立即 removeView 会导致 pending callback 中 getParent() 为 null → NPE
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { windowManager?.removeView(glSurfaceView) } catch (_: Exception) {}
                windowManager = null
            }
        }
        // 必须在 GL 线程静止（上面的 onPause 返回）之后再置位 wasActive：
        // MainActivity 的 GL 线程看到 wasActive 才会 deleteInstance 重建 shader 单例，
        // 这个顺序保证两条 GL 线程不会并发操作 CubismShaderAndroid
        wasActive = true
        // MainActivity 已销毁且把共享单例的收尾托付给了本服务
        if (pendingSharedDispose) {
            pendingSharedDispose = false
            try { Live2dDelegate.getInstance().onDestroy() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Live2D Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows Live2D model floating over other apps" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Live2D Overlay")
            .setContentText("Double-tap for menu · Drag to move · Pinch to resize")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun createFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val winWidth = (150 * density).toInt()
        val winHeight = (218 * density).toInt()

        val floatingRenderer = FloatingRenderer(this)
        renderer = floatingRenderer

        glSurfaceView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                handleTouch(event)
                return true
            }
        }.apply {
            // CRITICAL: explicit EGL config with 8-bit alpha for transparency
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setZOrderOnTop(true)
            setEGLContextClientVersion(2)
            setRenderer(floatingRenderer)
            // 模型有常驻待机动画，每帧都要重绘，如实使用连续渲染模式
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setPreserveEGLContextOnPause(false)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        layoutParams = WindowManager.LayoutParams(
            winWidth, winHeight,
            flags,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 160
        }

        windowManager?.addView(glSurfaceView, layoutParams)
        Log.d(TAG, "Floating window created: ${winWidth}x$winHeight")
    }

    // ================ Overlay sub-windows ================

    private fun initOverlayWindows() {
        bubbleWindow = OverlayBubbleWindow(this).also { it.setAnchor(anchorRect()) }
        menuWindow = OverlayMenuWindow(
            context = this,
            onCloseOverlay = { stopSelf() },
            onOpenInput = { toggleInputWindow() }
        )
        inputWindow = OverlayInputWindow(this, onSend = ::sendFromOverlay, onClose = { inputWindow?.hide() })
    }

    /** 当前人物悬浮窗在屏幕上的矩形（位置由 layoutParams 表达）。 */
    private fun anchorRect(): Rect {
        val p = layoutParams ?: return Rect()
        return Rect(p.x, p.y, p.x + p.width, p.y + p.height)
    }

    /** 人物悬浮窗被拖动/缩放后，同步气泡与菜单的位置。 */
    private fun repositionOverlayAnchors() {
        val r = anchorRect()
        bubbleWindow?.setAnchor(r)
        menuWindow?.takeIf { it.isVisible }?.reposition(r)
    }

    private fun showMenu() {
        menuWindow?.show(anchorRect())
    }

    /**
     * 轻触人物悬浮窗：
     * - 菜单开着 → 任意轻触收起菜单；
     * - 双击（[TAP_INTERVAL_MS] 内两击）→ 延迟确认后开菜单；
     * - 快速三连击 → 直接关闭整个悬浮窗（无需打开菜单）。
     */
    private fun onOverlayTap() {
        val now = System.currentTimeMillis()

        // 菜单开着：任何轻触直接收起，并重置连击计数
        if (menuWindow?.isVisible == true) {
            hideMenu()
            lastTapTime = now
            tapCount = 1
            return
        }

        // 连击计数（间隔超过 TAP_INTERVAL_MS 视为新一轮）
        if (now - lastTapTime > TAP_INTERVAL_MS) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTime = now

        when {
            tapCount == 2 -> {
                // 等片刻确认不是第三击再开菜单，避免与"三击关闭"冲突
                mainHandler.removeCallbacks(pendingShowMenu)
                mainHandler.postDelayed(pendingShowMenu, TAP_CONFIRM_MS)
            }
            tapCount >= 3 -> {
                mainHandler.removeCallbacks(pendingShowMenu)
                Log.d(TAG, "Triple-tap: closing overlay")
                stopSelf()
            }
        }
    }

    private fun hideMenu() {
        menuWindow?.hide()
    }

    /** 输入框可见则隐藏，隐藏则显示（菜单项"唤起输入框"的 toggle 语义）。 */
    private fun toggleInputWindow() {
        if (inputWindow?.isVisible == true) inputWindow?.hide() else inputWindow?.show(anchorRect())
    }

    /** 从悬浮窗输入框发送消息：走主界面同一个 chat 包，回复以气泡展示。 */
    private fun sendFromOverlay(text: String) {
        val container = (applicationContext as? MeaPetApplication)?.container ?: return
        inputWindow?.setSending(true)
        serviceScope.launch {
            // sendMessage 内部已 withContext(IO)，返回后回到 Main 直接更新 UI
            val result = container.chatService.sendMessage(text)
            inputWindow?.setSending(false)
            result.fold(
                onSuccess = { (_, assistant) ->
                    inputWindow?.clearText()
                    bubbleWindow?.addBubble(assistant.content)
                },
                onFailure = { e ->
                    Log.w(TAG, "Overlay send failed: ${e.message}")
                    bubbleWindow?.addBubble("发送失败：${e.message ?: "未知错误"}")
                }
            )
        }
    }

    // ================ Multi-touch handling ================

    private fun handleTouch(event: MotionEvent) {
        val params = layoutParams ?: return

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
                    windowManager?.updateViewLayout(glSurfaceView, params)
                    repositionOverlayAnchors()
                } else {
                    // --- DRAG: move window ---
                    // 钳制在屏幕范围内（留出窗口自身尺寸），防止拖出屏幕后找不回
                    val dm = resources.displayMetrics
                    params.x = (dragStartX + (event.rawX - dragStartRawX).toInt())
                        .coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (dragStartY + (event.rawY - dragStartRawY).toInt())
                        .coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
                    windowManager?.updateViewLayout(glSurfaceView, params)
                    repositionOverlayAnchors()
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
                // 轻触处理：双击开菜单 / 三击关悬浮窗；菜单开着时任意轻触收起
                pinchStartDist = 0f
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (sqrt((dx * dx + dy * dy).toDouble()) < TAP_SLOP_DP * density) {
                    onOverlayTap()
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

    // ================ Renderer ================

    private class FloatingRenderer(
        private val service: FloatingLive2dService
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
                if (service.shuttingDown) return

                if (!modelLoaded) {
                    loadModel()
                    modelLoaded = true
                    if (service.shuttingDown) return
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
                val canvasRatio = m.model!!.canvasHeight / m.model!!.canvasWidth
                if (canvasRatio < displayRatio) {
                    m.modelMatrix!!.setWidth(2.0f * zoom)
                    projection.scale(1.0f, aspect)
                    projection.translateRelative(0f, -0.35f)
                } else {
                    m.modelMatrix!!.setHeight(2.0f * zoom)
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
                model = Live2dModel(dir)
                model!!.loadAssets(dir, fileName)
                model!!.bindTextures(textureManager!!)
                Log.d(TAG, "Overlay model loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load overlay model", e)
                // 释放可能已部分构建的模型，并停掉服务——
                // 留着空窗口只会吞触摸且无法关闭
                releaseModel()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    service.stopSelf()
                }
            }
        }
    }
}
