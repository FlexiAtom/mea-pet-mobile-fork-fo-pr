package com.meapet.mobile.viewmodel

import com.meapet.mobile.framework.AppContainer
import com.meapet.mobile.framework.MeaPetApplication
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * [SettingsViewModel] 交互测试。
 *
 * 静态依赖（PrivacyConsentManager / AppInfo）经 [FakeSettingsViewModel]
 * 覆写的 protected 方法隔离（避免单测触碰 DataStore / PackageManager）；
 * `application` mock 为 [MeaPetApplication]，让 `MeaPetApplication.from` 真实执行，
 * `container` 经 doReturn 注入。验证「值有变化才落盘」与输入校验逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    /** 屏蔽静态依赖的测试替身。 */
    private class FakeSettingsViewModel(application: MeaPetApplication) : SettingsViewModel(application) {
        override fun privacyAgreedFlow() = MutableStateFlow(false)
        override fun isPrivacyAgreed() = false
        override fun readAppVersion() = "1.0.0"
    }

    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var application: MeaPetApplication
    private lateinit var container: AppContainer
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = mock()
        container = mock()
        settingsManager = mock()

        // container 经 doReturn 注入（by lazy 属性）
        Mockito.doReturn(container).`when`(application).container
        Mockito.doReturn(settingsManager).`when`(container).settingsManager

        // 设置 getter（initialState 同步读取）
        whenever(settingsManager.getApiKey()).thenReturn("")
        whenever(settingsManager.getApiUrl()).thenReturn("https://api.openai.com/v1")
        whenever(settingsManager.getModel()).thenReturn("gpt-4o-mini")
        whenever(settingsManager.getTemperature()).thenReturn(0.7)
        whenever(settingsManager.getMaxTokens()).thenReturn(1024)
        whenever(settingsManager.getSystemPrompt()).thenReturn("")
        whenever(settingsManager.isMemoryEnabled()).thenReturn(true)
        whenever(settingsManager.isAutoSummaryEnabled()).thenReturn(true)
        whenever(settingsManager.getSummaryInterval()).thenReturn(10)

        // 全部设置 Flow（init 订阅）
        whenever(settingsManager.apiKeyFlow).thenReturn(MutableStateFlow(""))
        whenever(settingsManager.apiUrlFlow).thenReturn(MutableStateFlow("https://api.openai.com/v1"))
        whenever(settingsManager.modelFlow).thenReturn(MutableStateFlow("gpt-4o-mini"))
        whenever(settingsManager.temperatureFlow).thenReturn(MutableStateFlow(0.7))
        whenever(settingsManager.maxTokensFlow).thenReturn(MutableStateFlow(1024))
        whenever(settingsManager.systemPromptFlow).thenReturn(MutableStateFlow(""))
        whenever(settingsManager.enableMemoryFlow).thenReturn(MutableStateFlow(true))
        whenever(settingsManager.enableAutoSummaryFlow).thenReturn(MutableStateFlow(true))
        whenever(settingsManager.summaryIntervalFlow).thenReturn(MutableStateFlow(10))
        whenever(settingsManager.themeModeFlow).thenReturn(MutableStateFlow("system"))
        whenever(settingsManager.enableDynamicColorFlow).thenReturn(MutableStateFlow(true))
        whenever(settingsManager.colorPresetFlow).thenReturn(MutableStateFlow("default"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `保存不同 Key 落盘并重建客户端`() = runTest(dispatcher.scheduler) {
        val vm = FakeSettingsViewModel(application)
        vm.saveApiKey("new-key")
        dispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(settingsManager) { setApiKey("new-key") }
        verify(container).reloadClient()
    }

    @Test
    fun `相同 Key 不重复落盘`() = runTest(dispatcher.scheduler) {
        whenever(settingsManager.getApiKey()).thenReturn("same")
        val vm = FakeSettingsViewModel(application)

        vm.saveApiKey("same")
        dispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(settingsManager, never()) { setApiKey(any()) }
    }

    @Test
    fun `空 API 地址拉取模型报错`() = runTest(dispatcher.scheduler) {
        val vm = FakeSettingsViewModel(application)

        vm.fetchModels("key", "")

        assertEquals("请先填写 API 地址", vm.state.value.modelsError)
    }
}
