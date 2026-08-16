package com.meapet.mobile.framework

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.meapet.mobile.live2d.FloatingLive2dService
import com.meapet.mobile.live2d.Live2dDelegate
import com.meapet.mobile.live2d.Live2dRenderer
import com.meapet.mobile.ui.screen.ChatScreenContent
import com.meapet.mobile.ui.theme.MeaPetTheme
import com.meapet.mobile.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 主入口 Activity（框架层）。
 *
 * ## 设计原则
 * - `launchMode="singleTask"` 防止重复实例
 * - `isTaskRoot` 防护 Android 15+ 兼容性
 * - GLSurfaceView + ComposeView 混合渲染
 * - 所有系统回调均 try-catch 保护
 */
class MainActivity : ComponentActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var container: AppContainer
    private var insetsController: WindowInsetsControllerCompat? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 等待悬浮窗 Service 完全停止后再恢复本 Activity GL 渲染的轮询任务。 */
    private var resumeGate: Runnable? = null

    /** 聊天 ViewModel（与 Compose 里 viewModel() 共享同一实例）。 */
    private val chatViewModel: ChatViewModel by viewModels()

    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
    }

    // ── 生命周期 ──────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility", "SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        // isTaskRoot 防护（Android 15+ 兼容）
        if (!isTaskRoot) {
            finish()
            return
        }
        super.onCreate(savedInstanceState)

        container = MeaPetApplication.from(applicationContext as android.app.Application)

        // 设置窗口背景（防白闪）
        try {
            val themeMode = runBlocking { container.settingsManager.themeModeFlow.first() }
            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> (resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            window.setBackgroundDrawable(
                ColorDrawable(if (isDark) 0xFF141414.toInt() else 0xFFF7F7F7.toInt())
            )
        } catch (_: Exception) {
            window.setBackgroundDrawable(ColorDrawable(0xFF141414.toInt()))
        }

        insetsController = WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        hideSystemBars()

        // GLSurfaceView
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(Live2dRenderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setPreserveEGLContextOnPause(true)
        }

        // ComposeView
        val composeView = ComposeView(this).apply {
            setContent {
                val themeMode by container.settingsManager.themeModeFlow
                    .collectAsState(initial = "system")
                val enableDynamicColor by container.settingsManager.enableDynamicColorFlow
                    .collectAsState(initial = true)
                val colorPreset by container.settingsManager.colorPresetFlow
                    .collectAsState(initial = "default")

                // Live2D 背景色跟随主题
                val bgColor = remember(themeMode) {
                    val isDark = when (themeMode) {
                        "dark" -> true; "light" -> false
                        else -> (resources.configuration.uiMode and
                                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                    }
                    if (isDark) floatArrayOf(0.08f, 0.08f, 0.08f)
                    else floatArrayOf(0.97f, 0.97f, 0.97f)
                }
                LaunchedEffect(bgColor) {
                    Live2dDelegate.getInstance().let { d ->
                        d.bgR = bgColor[0]; d.bgG = bgColor[1]; d.bgB = bgColor[2]; d.bgA = 1.0f
                    }
                }

                // ── 隐私协议弹窗（首次启动且未做过选择时显示） ──
                val context = androidx.compose.ui.platform.LocalContext.current
                var showPrivacyDialog by remember {
                    mutableStateOf(!PrivacyConsentManager.hasUserChosen(context))
                }

                MeaPetTheme(themeMode = themeMode, dynamicColor = enableDynamicColor, colorPreset = colorPreset) {
                    if (showPrivacyDialog) {
                        com.meapet.mobile.ui.component.PrivacyDialog(
                            onAgree = {
                                PrivacyConsentManager.setAgreed(context, true)
                                (applicationContext as? MeaPetApplication)?.initUmengSdk()
                                showPrivacyDialog = false
                            },
                            onDisagree = {
                                PrivacyConsentManager.setAgreed(context, false)
                                showPrivacyDialog = false
                            }
                        )
                    }
                    ChatScreenContent(onToggleOverlay = { toggleOverlay() })
                }
            }
        }

        // 根布局 + 触摸透传
        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                try {
                    if (event != null && ::glSurfaceView.isInitialized) {
                        // MotionEvent 会被系统回收复用，先在主线程拷贝出基本类型，
                        // 再交给 GL 线程延迟读取
                        val action = event.actionMasked
                        val x = event.x
                        val y = event.y
                        glSurfaceView.queueEvent {
                            try {
                                when (action) {
                                    MotionEvent.ACTION_DOWN -> Live2dDelegate.getInstance().onTouchBegan(x, y)
                                    MotionEvent.ACTION_UP -> Live2dDelegate.getInstance().onTouchEnd(x, y)
                                    MotionEvent.ACTION_MOVE -> Live2dDelegate.getInstance().onTouchMoved(x, y)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            addView(glSurfaceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        try {
            Live2dDelegate.getInstance().onStart(this)
            // 从桌面返回时自动关悬浮窗
            if (FloatingLive2dService.overlayActive) {
                FloatingLive2dService.overlayActive = false
                FloatingLive2dService.stop(this)
                // 悬浮窗期间聊的内容在共享会话里，回到主界面刷新一次列表
                chatViewModel.reloadHistory()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStart error: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // 从悬浮窗返回时，Service 的 onDestroy（含其 GL 线程暂停）是异步的。
        // 若此刻立即恢复本 Activity 的 GL 线程，两条 GL 线程会并发使用共享的
        // CubismShaderAndroid 单例（Service 侧曾在其上下文里重建过 shader），
        // 导致本 Activity 用到无效 program → 黑屏/GL 报错。
        // 因此等 Service 完全停止（isRunning=false）后再恢复渲染。
        resumeGate?.let { mainHandler.removeCallbacks(it) }
        if (FloatingLive2dService.isRunning) {
            val gate = object : Runnable {
                override fun run() {
                    if (FloatingLive2dService.isRunning) {
                        mainHandler.postDelayed(this, 16L)
                    } else {
                        resumeGate = null
                        try { glSurfaceView.onResume() } catch (e: Exception) {
                            Log.e(TAG, "onResume(gated) error: ${e.message}")
                        }
                    }
                }
            }
            resumeGate = gate
            mainHandler.post(gate)
        } else {
            try {
                glSurfaceView.onResume()
            } catch (e: Exception) {
                Log.e(TAG, "onResume error: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 取消等待 Service 停止的轮询，避免 Activity 已 pause 后还去 onResume GL
        resumeGate?.let { mainHandler.removeCallbacks(it) }
        resumeGate = null
        try {
            glSurfaceView.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "onPause error: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            Live2dDelegate.getInstance().onStop()
        } catch (e: Exception) {
            Log.e(TAG, "onStop error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (FloatingLive2dService.isRunning) {
                // 悬浮窗 Service 的 GL 线程还在使用共享的静态单例
                // （CubismShaderAndroid / Live2dManager / CubismFramework），
                // 此处不能 dispose，置位标记交给 Service onDestroy 收尾。
                // 但必须清除单例对本 Activity 的强引用，否则悬浮窗存活期间
                // 已销毁的 Activity 及其视图树会被长期持有（内存泄漏）。
                FloatingLive2dService.pendingSharedDispose = true
                Live2dDelegate.getInstance().onActivityDestroyed(this)
            } else {
                Live2dDelegate.getInstance().onDestroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error: ${e.message}")
        }
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                requestNotificationPermissionThenStartOverlay()
            } else {
                Log.w(TAG, "Overlay permission not granted")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            // 通知权限被拒不阻塞悬浮窗：前台服务仍可运行，只是不显示常驻通知
            startOverlayAndGoBack()
        }
    }

    // ── 悬浮窗 ──────────────────────────────────────

    private fun toggleOverlay() {
        if (FloatingLive2dService.overlayActive) {
            FloatingLive2dService.overlayActive = false
            FloatingLive2dService.stop(this)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    OVERLAY_PERMISSION_REQUEST
                )
                return
            }
            requestNotificationPermissionThenStartOverlay()
        }
    }

    /** API 33+ 需要运行时通知权限，前台服务的常驻通知才可见；请求后无论结果都继续启动。 */
    private fun requestNotificationPermissionThenStartOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }
        startOverlayAndGoBack()
    }

    private fun startOverlayAndGoBack() {
        // 先让本 Activity 的 GL 线程静止：GLSurfaceView.onPause 会阻塞到
        // GL 线程完成当前帧并暂停。之后 Service 的 GL 线程在 onSurfaceCreated
        // 里 deleteInstance 重建 CubismShaderAndroid 时，不会有另一条 GL 线程
        // 在并发遍历同一份 shader 列表。Activity 侧要到 onResume 才会恢复渲染，
        // 而 moveTaskToBack 之后不会发生 onResume。
        if (::glSurfaceView.isInitialized) {
            try { glSurfaceView.onPause() } catch (_: Exception) {}
        }
        FloatingLive2dService.overlayActive = true
        FloatingLive2dService.start(this)
        moveTaskToBack(true)
    }

    private fun hideSystemBars() {
        insetsController?.hide(
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.statusBars()
        )
    }
}
