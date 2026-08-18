package com.meapet.mobile.live2d

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live2D 渲染协调状态（主界面 Activity GL 线程 ↔ 悬浮窗 Service GL 线程共享）。
 *
 * 两个 GLSurfaceView（[com.meapet.mobile.ui.MainActivity] 与
 * [com.meapet.mobile.live2d.overlay.FloatingLive2dService]）并发使用同一个
 * CubismShaderAndroid / CubismFramework / Live2dManager 单例。通过本对象的一组状态
 * 协调两条 GL 线程的生命周期，避免并发操作共享 shader 单例导致黑屏 / GL 报错。
 *
 * 用 **StateFlow** 替代裸 `@Volatile` 布尔：读取线程安全（GL 线程 / 主线程均安全），
 * 且可被订阅观察。写入统一经 setter，语义与原先 `@Volatile var` 一致。
 *
 * ## 线程安全边界
 * StateFlow 保证**单值读取**线程安全；跨状态的复合判断（如「刚关闭且不再激活」）
 * 请使用 [consumeShaderResetRequest] 这类原子方法，避免 check-then-act 竞态。
 * 两条 GL 线程互斥的最终保障仍是 `GLSurfaceView.onPause()` 阻塞主线程的时序契约。
 *
 * ## 字段语义（与原 FloatingLive2dService.companion 一致）
 * - [overlayActive] 悬浮窗是否当前激活。
 * - [wasActive] 悬浮窗刚关闭（其 GL 上下文已销毁），下一次主界面渲染需重建 shader 单例。
 * - [isRunning] Service 是否存活（onCreate → onDestroy 之间）。
 * - [pendingSharedDispose] MainActivity 销毁时因 Service 仍在运行而跳过了共享单例的
 *   全局 dispose，置位后由 Service onDestroy 收尾。
 */
object Live2dRenderState {

    private val _overlayActive = MutableStateFlow(false)

    /** 悬浮窗是否当前激活。 */
    val overlayActive: StateFlow<Boolean> = _overlayActive.asStateFlow()

    private val _wasActive = MutableStateFlow(false)

    /** 悬浮窗刚关闭，主界面渲染需重建 shader 单例。 */
    val wasActive: StateFlow<Boolean> = _wasActive.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** Service 是否存活（onCreate → onDestroy 之间）。 */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _pendingSharedDispose = MutableStateFlow(false)

    /** MainActivity 销毁时置位，交由 Service onDestroy 收尾全局 dispose。 */
    val pendingSharedDispose: StateFlow<Boolean> = _pendingSharedDispose.asStateFlow()

    private val _shuttingDown = MutableStateFlow(false)

    /** 悬浮窗服务是否正在关闭（GL 线程每帧检查，防止服务销毁期间继续绘制）。 */
    val shuttingDown: StateFlow<Boolean> = _shuttingDown.asStateFlow()

    /** 设置悬浮窗激活状态。 */
    fun setOverlayActive(active: Boolean) {
        _overlayActive.value = active
    }

    /** 标记悬浮窗刚关闭（其 GL 上下文已销毁）。 */
    fun setWasActive(active: Boolean) {
        _wasActive.value = active
    }

    /** 标记 Service 运行状态。仅由 FloatingLive2dService 自身调用。 */
    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    /** 置位/清除「MainActivity 已销毁、全局 dispose 交由 Service 收尾」标记。 */
    fun setPendingSharedDispose(value: Boolean) {
        _pendingSharedDispose.value = value
    }

    /** 标记悬浮窗服务开始/结束关闭。仅由 FloatingLive2dService 自身调用。 */
    fun setShuttingDown(value: Boolean) {
        _shuttingDown.value = value
    }

    /**
     * 主界面 GL 线程下一帧是否需要重建 shader 单例。
     *
     * 悬浮窗刚关闭（wasActive=true）且已不激活（overlayActive=false）时返回 true，
     * 并自动清除 wasActive（本请求只应消费一次）。在 object 内 [synchronized]，
     * 避免消费侧两次独立 `.value` 读取的 check-then-act 竞态。
     */
    @Synchronized
    fun consumeShaderResetRequest(): Boolean {
        if (_wasActive.value && !_overlayActive.value) {
            _wasActive.value = false
            return true
        }
        return false
    }
}
