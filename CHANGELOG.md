# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

该项目的所有重大更改都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，
本项目遵循 [语义化版本控制](https://semver.org/spec/v2.0.0.html)。

---

## [1.4.0] - 2026-08-17

### Added

- **悬浮窗交互重构** — 双击人物唤起独立菜单悬浮窗（关闭悬浮窗 / 唤起输入框），点空白处或 5s 无操作自动隐藏，带中心缩放 + 淡入淡出动画；快速三击人物直接关闭整个悬浮窗。
- **悬浮窗输入框** — 独立可拖动输入条（左侧抓手），默认出现在人物正下方；发送走与主界面同一 `chat` 包（共享会话历史），发送后保持打开便于连续交流。
- **悬浮窗气泡回复** — AI 回复以聊天软件式带小尾巴气泡显示在人物左右侧（离屏幕较远的一侧），自动换行、按回复长度决定停留时间，多条气泡向上挤压、旧气泡渐隐消失，无气泡后延迟销毁窗口。
- **悬浮窗主题跟随** — 悬浮窗配色读取用户设置（颜色预设 / 动态取色 / 明暗模式），不再固定默认紫。
- **回到前台热加载聊天** — 后台切前台固定刷新一次会话历史，悬浮窗期间的聊天回到主界面即时可见（非破坏合并，不覆盖内存状态）。
- **本地模型免 API Key** — API Key 留空时不发送 `Authorization` 头，可直接对接 Ollama / LM Studio 等本地 OpenAI 兼容端点；云端缺 Key 返回 401 时给出「请填写 API Key」友好提示。
- **独特性标识信息注入** — 开发者名、GitHub 仓库地址、交流 QQ 群、友盟隐私政策链接从 `local.properties` 读取，经 `BuildConfig` 注入（新增 `AppInfo` 统一访问入口），源码不再硬编码，开源分叉可直接替换。
- **`LinkItem` 共享组件** — 关于浮层与隐私政策复用的可点击超链接组件，抽出为公共组件。
- **隐私政策头部信息** — 新增版本、生效时间、修订时间字段。

### Changed

- **悬浮窗双击行为** — 双击关闭 → 双击开菜单；三击关闭悬浮窗。
- **`SettingsManager` 新增主题同步 getter** — `getThemeMode()` / `isDynamicColorEnabled()` / `getColorPreset()`，供悬浮窗读取用户主题。
- **取消数据采集授权后直接退出** — 设置页确认取消后立即结束进程，保证 SDK 在本次进程内不再上报，无需等待重启。
- **隐私政策内容修订** — 增加政策主体（开发者名）、披露统计 SDK 预初始化、补充「你的权利」章节；设备标识符披露细化（OAID、Android ID、设备型号、操作系统版本等）；删除未实际集成的崩溃信息采集承诺；「匿名」统一改为「去标识化」；友盟隐私权政策链接改为可点击跳转。
- **更新检测接口地址** — 由注入的仓库地址动态拼装，跟随 `app.gitRepoUrl` 变化。

### Fixed

- **悬浮窗聊天回主界面不刷新** — 原先需要杀掉重启才能看到悬浮窗期间聊的内容，现在回到前台即重载共享会话。

### Notes

- 本版本含悬浮窗交互重构与多项目前【待定】的收尾，按语义化版本升 **MINOR**（versionCode 8）。
- 相对 1.3.0 无破坏性变更，applicationId 不变，可覆盖安装。

---

## [1.3.0] - 2026-07-29

### Added

- **友盟+ 统计 SDK 集成** — 接入友盟 U-APP 统计 SDK（common 9.9.2 + asms 1.8.7.2），采集匿名使用数据（启动次数、使用时长等）。AppKey 通过 `local.properties` 配置，与代码隔离。
- **隐私协议弹窗** — 首次启动展示隐私授权弹窗，用户可选择同意或不同意。不同意时 App 所有功能正常使用，仅不初始化统计 SDK。弹窗内可查看完整隐私政策。弹窗使用 `Dialog + Card` 风格，与关于弹窗统一。
- **设置页隐私入口** — 设置页底部新增「隐私与数据」分区，可跳转查看完整隐私政策，并支持取消数据采集授权。
- **隐私政策内容统一** — 抽取 `PrivacyPolicyContent` 共享组件，弹窗全文和设置页共用同一份文本，修改一处即可同步。
- **语音分区子目录管理** — `assets/voice/` 下新增 `upper/`、`lower_left/`、`lower_right/` 三个子目录，按触摸分区存放语音。`VoicePlayer` 新增 `listVoices()` 自动扫描目录内 `.wav` 文件。新增语音只需丢进文件夹即生效，无需改代码。

### Changed

- `build.gradle.kts` 开启 `buildConfig = true`，通过 `BuildConfig.UMENG_APP_KEY` 注入 AppKey。
- `AndroidManifest.xml` 新增 `ACCESS_WIFI_STATE` 权限与友盟集成测试 intent-filter。
- 新增 `PrivacyConsentManager` 管理用户授权状态的持久化。
- 语音随机选择从 `java.util.Random` 改为 `SecureRandom`，降低连续点击重复感。
- 语音播放改为互斥模式：新触摸触发时先停止所有在播语音再播放。

### Fixed

- **触摸气泡生命周期** — 每条气泡独立 7 秒倒计时。新气泡触发后按位置自动扣减旧气泡剩余寿命：position 4-5 扣 2 秒，position 6+ 共扣 4 秒，实现旧气泡加速消失。
- **VoicePlayer 资源释放** — `MediaPlayer` 回调完成后正确 `release()`。

### Notes

- 本版本含友盟 SDK 接入（功能增量）、隐私 UI 重构与语音系统改进，按语义化版本升 **MINOR**。
- 旧版本升级到 1.3.0 无破坏性变更，applicationId 不变，可覆盖安装。

---

## [1.2.1] - 2026-07-27

### Added

- **模型知道当前时间** — 每轮请求注入设备本地时间、星期与时区（`TimeContext`），问「几点了 / 今天几号」不再瞎猜。时间每轮都变，不写入会话历史。

### Fixed

- **自动摘要几乎从不触发** — 轮次计数器原先在内存里，冷启动即归零，默认 10 轮间隔实际上走不满。改存 DataStore 跨进程延续，并改为「攒够即清零」。
- **摘要可能反而丢信息** — 摘要未返回 `keywords` 时生成的长期记忆永远匹配不上检索。现关键词为空则放弃本次摘要，短期记忆原样保留。

### Changed

- **默认系统提示词更新** — 梅尔人设改为更完整的角色协议。仅影响未自定义过 system prompt 的新装 / 重置用户；已保存的不会被覆盖。
- **记忆协议块回贴历史** — 助手消息剥离协议块后写入历史，模型会照着「过去都没输出块」继续漏。现保留块原文并贴回最近几条助手消息当正例。
- **记忆记录门槛与查找词** — 优先记可复用信息，禁止把助手提议写成用户事实；`keywords` 改为查找词（实体 + 话题/类别），摘要侧同步。
- **请求分层与历史批量裁剪** — 稳定内容放首条 system，每轮易变内容压到历史之后，便于 prefix cache；超限一次裁 8 条，历史窗口 40→35。
- **【用户人设】注入封顶** — 事实 / 特质按重要性最多注入 30 条（只影响注入，不影响存储与「查看记忆」）。
- **摘要最小条数门槛** — 短期记忆不足 3 条时跳过本次摘要。
- **版本号升至 `1.2.1`**（versionCode 6）。

### Notes

- 相对 1.2.0 无破坏性 API / 存储格式变更，主要是记忆链路修复与行为打磨 + 默认人设更新，按语义化版本升 **PATCH**。
- `ChatMessage` 新增 `memoryOpsBlock`。旧会话文件没有该字段时为 null，升级前的历史不参与协议块回贴，其余不受影响。
- 请求现在可能包含两条 system 消息（首条与尾部）。OpenAI 兼容端点普遍支持；若中转要求 system 唯一或必须在首位，需自行把尾部块改为 `user`。

---

## [1.2.0] - 2026-07-26

### Added

- **聊天记录持久化** — 新增 `ConversationStore`，会话历史随消息变更异步落盘（合并写 + 原子替换 + 损坏文件 `.corrupt` 备份），启动时恢复到界面。此前是纯内存的，强杀进程重开必然清空。
- **「查看记忆」界面** — 首页三点菜单新增入口，展示记忆统计与条目列表（内容、类型、重要性、关键词），支持单条删除与「清除全部」（二次确认）。
- **摘要轮次可调** — 设置页「记忆系统」新增滑杆，可设定每隔多少轮触发一次摘要（3~30，默认 10）；关闭「自动摘要」时置灰。此前硬编码 10 轮。

### Changed

- **记忆创建改由大模型自主决定** — 移除程序侧启发式提取（按字数阈值 + 关键词表打分，中文日常聊天几乎触发不到）。新增 `MemoryOpsProtocol`：模型在回复末尾附加 ` ```memory-ops ` 代码块，声明本轮 `create` / `update` / `delete` 哪些记忆及其类型、重要性、关键词；该块解析后从回复中剥离，用户不可见。全程容错，块缺失 / JSON 畸形 / 围栏未闭合一律静默跳过，不影响聊天回复。未采用 function calling——不少 OpenAI 兼容中转对其支持不稳定，且要多一次往返。
- **记忆检索改为匹配模型给出的关键词** — 移除程序侧切词 / CJK 二元组匹配。原实现按空白与标点切词，中文整句被当成单个关键词，`contains` 几乎永不命中，磁盘上的记忆从来没被注入过上下文。
- **事实与特质永不自动淘汰** — `FACTUAL` / `CORE_TRAIT` 排除出容量淘汰池（`maxItems` 仅约束短期 + 长期），并每轮全量注入 system prompt（带 id 供模型引用）；短期 / 长期仍按关键词匹配注入。手动删除不受影响。
- **摘要改为消费短期记忆** — 不再发送原始对话文本，改为把已攒下的短期记忆压缩成一条长期记忆并删除参与摘要的短期条目；失败时原样保留，下次再试。
- **`MemoryItem.tags` → `keywords`** — 标签字段替换为检索关键词，按标签分组的相似度去重（`consolidate`）一并移除——模型每轮能看到全部事实与特质，重复时会自己走 `update`。
- **记忆 id 改为短 id** — 完整 UUID 改为 `mem_` + 8 位随机字符，省 token 且模型抄写更不易出错。
- **记忆链路日志** — 各决策点补充 Logcat（是否注入协议、是否解析到块、失败原因、落库结果），`adb logcat -s MemoryOpsProtocol MemoryManager MemoryService` 即可排查；仅记长度与计数，对话内容不入日志。
- **死代码清理** — 移除 `ChatService` / `ConversationManager` 中已无调用方的 `getRecentExchanges()`、`getContextText()`，以及 `MemoryService` 的 `extractFromExchange` / `calculateImportance` / `extractTags` / `consolidate` / `isSimilar`。
- **版本号升至 `1.2.0`**（versionCode 5）。

### Fixed

- **启动加载与首次写入竞态导致记忆被整体覆盖** — 异步加载未完成前若先发生一次保存，会把只含新条目的内存列表写回文件，旧记忆全丢。改为惰性加载兜底，读盘必定先于首次写盘。这是「记忆大退就没了」中真正丢数据的一环。
- **ViewModel 早于异步加载完成导致界面空白** — `AppContainer` 暴露 `warmUpJob`，`ChatViewModel` 等待完成后刷新消息列表，并按 id 去重保留加载期间的新消息。
- **`ConversationManager` 线程安全** — 启动恢复在 IO 线程执行，与发送链路并发访问消息列表，所有读写方法改为加锁串行化。
- **`MemoryRepository` 可测试性** — 构造参数由 `Context` 改为 `filesDir: File`，可在纯 JVM 单测中验证持久化、淘汰与检索。

### Notes

- **老数据会有降级**：已存在的旧记忆没有 `keywords`（旧 `tags` 静默丢弃），因此旧的短期 / 长期记忆无法被检索到；事实类不受影响，仍全量注入。建议升级后在「查看记忆」中清空重来。
- 本版本含用户可见新功能与记忆系统行为重构，聊天与 API 配置流程向后兼容，按语义化版本升 **MINOR**。
- 新增测试依赖 `mockito-kotlin`（仅 `testImplementation`，不进入 APK）。

---

## [1.1.0] - 2026-07-25

### Added

- **检测新版本** — 启动时静默请求 GitHub `releases/latest`，有正式新版本时在聊天页底部 Snackbar 轻提示（可点「查看」打开发布页）；关于卡片「检查更新」绑定同一逻辑，手动检测会反馈有更新 / 已最新 / 失败。网络异常启动路径静默失败，不打扰。Snackbar 动作色跟随主题 `primary`。
- **获取模型列表** — 设置页模型输入框下增加「获取模型列表」：用当前 API Key / 地址请求 `/v1/models`，解析 id 列表后点选写回；兼容 `data[]` 与顶层数组两种响应格式，并补充解析单测。
- **关于页可点击外链** — 关于卡片内新增主题色（`primary`）下划线链接：Live2D 模型来源、GitHub 仓库、交流 QQ 群。
- **Live2D 模型来源** — 关于页补充模型来源入口（Bilibili）。

### Fixed

- **记忆链路** — 启动时加载持久化记忆；手写 JSON 改为 `kotlinx.serialization` + 原子写；记忆总开关真正关闭提取 / 摘要 / 注入；访问统计落盘。
- **聊天链路** — 清空会话时取消在途请求，避免回复回写；记忆后处理异步化，摘要不再卡住发送。
- **API 客户端** — `reloadClient` 可热重建；`HttpTimeout` 适配 LLM 长回复；`baseUrl` 规范化（用户可带或不带 `/v1`，客户端统一补齐后拼 `models` / `chat/completions`）；`max_tokens` 入请求；取消与空响应处理。
- **悬浮窗 / GL** — `START_NOT_STICKY`、失败自停、native 模型释放、捏合后拖动锚点与屏幕边界钳制；Activity 与 Service 的 GL 线程串行化，避免共享 shader 单例竞态；单例改用 application context，避免持有已销毁 Activity。
- **设置保存** — 输入框失焦保存、Slider 结束写盘；`SettingsManager` 增加内存快照缓存；DataStore 备份排除敏感项。
- **「变态」语音** — 触摸语音文件原先只有 “hen”，已更换为正确的 “hentai” 资源。

### Changed

- **关于卡片** — 从设置页挪到聊天页三点菜单入口；改为带动画的悬浮对话框，展示应用介绍、版本号与技术栈，系统返回键可关闭；设置页原关于模块移除。
- **包名迁移** — `com.llz121517.meapet` → `com.meapet.mobile`（namespace / applicationId 同步；注意：applicationId 变更后与 1.0.x 安装包不连续升级，需重新安装）。
- **targetSdk 36** — 补充 `POST_NOTIFICATIONS`、前台服务 `specialUse` 声明。
- **死代码清理** — 移除 `Live2dActivity`、`TouchManager` 等未使用组件。
- **版本号升至 `1.1.0`**（versionCode 4）。

### Notes

- 本版本相对 1.0.2 含用户可见新功能（更新检测 / 模型列表 / 关于外链）与多项修复，按语义化版本升 **MINOR**；未升 MAJOR，因 API 配置与聊天流程仍向后兼容。包名变更对旧安装是例外，见上。

---

## [1.0.2] - 2026-07-23

### Fixed

- **悬浮窗未加载完返回应用崩溃** — `SurfaceView` 的延迟绘制回调（`performDrawFinished` → `requestTransparentRegion`）在 View 已被 `removeView` 摘除后触发，`getParent()` 为 null 导致 NPE。将 `removeView` 通过 `Handler.post` 延迟到当前消息队列末尾执行，确保所有 pending 回调先完成。
- **主题模式切换框选择菜单不稳定** — `ExposedDropdownMenuBox` 内 `menuAnchor` 的触摸处理与 `OutlinedTextField` 内部手势产生冲突，偶发点击不展开。改为独立透明点击覆盖层 + 手动 `Popup`，彻底解决。

### Changed

- **颜色预设系统重构** — 从每套预设手工编写完整 `ColorScheme`（24 个对象），改为单 seed 主色 + 工具函数（`lighten`/`darken`/`desaturate`/`hueShift`）自动生成全套浅/深色方案。新增 `seed` 字段，预览色块直接使用主色。
- **首页菜单重做** — 从 `DropdownMenu` 改为 `Popup + Surface + Animatable`，宽度缩至 130dp，菜单项间添加分隔线，弹出位置固定在三点按钮正下方，增加淡入 + 右上角缩放入场/退场动画。
- **主题模式选择器动画** — 弹出菜单增加淡入 + 缩放动画，宽度与输入框精确对齐。
- **浅色模式滑动条底色** — 未选中区域底色从白色 60% 透明度改为 35% 透明度（`Color.White.copy(alpha = 0.35f)`），呈现更浅白的半透明效果。
- **动态颜色开关** — Android 12 以下设备开关置灰不可操作，提示文字更新为"当前系统不支持动态颜色"。
- **Switch 组件** — 统一设置页 Switch 颜色，与主题背景色协调。
- **版本号升至 `1.0.2`**（versionCode 3）。

### Added

- **关于部分** — 在设置页面的关于部分添加了累计Token消耗量显示

---

## [1.0.1] - 2026-07-22

### Fixed

- **LifecycleManager 递归栈溢出导致切后台崩溃** — 构造参数 `onTrimMemory` 与 override 方法同名，导致无限递归调用自身而非 lambda。重命名为 `trimMemoryCallback`。
- **悬浮窗关闭时 GL 上下文跨域崩溃** — 主 Activity 试图释放悬浮窗 GL 上下文的 shader 程序，跨上下文 GL 操作导致原生崩溃。跳过 `releaseInvalidShaderProgram()`，直接 `deleteInstance()` 重建。同时修复 service `onDestroy()` 未先暂停 GL 线程就直接 `removeView` 的竞态问题。

### Changed

- API Key 输入框键盘类型从 `Password` 改为 `Uri`，允许使用剪贴板粘贴。
- 版本号升至 `1.0.1`（versionCode 2）。
- 关于页版本号改为从 `PackageManager` 动态读取 `versionName`，不再硬编码。

### Added

- API 配置区提示文字："需要一个 OpenAI 兼容的 API 端点"。
- 设置页关于介绍更新。

---

## [1.0.0] - 2026-07-21

### Added

- **Live2D 模型渲染** — 基于 Live2D Cubism SDK 的主页模型展示与悬浮窗模式。
- **AI 聊天** — OpenAI 兼容 API 客户端，支持对话管理、System Prompt 与记忆上下文注入。
- **记忆系统** — 短期/长期记忆提取、AI 摘要、相关性检索与文件持久化。
- **多主题配色** — Material You 动态取色 + 12 套预设色板，支持浅色/深色模式。
- **触摸分区反馈** — 模型区域分三区，点击触发随机语音播放与气泡文字。
- **视角跟随** — 触摸时模型头部与视线跟随手指方向。
- **悬浮窗** — 前台 Service 浮窗模式，支持拖拽、缩放、双击关闭。
- **设置页** — API 配置、模型参数、System Prompt、记忆开关、主题选择。
- **全屏沉浸** — 隐藏系统状态栏/导航栏，GLSurfaceView + ComposeView 混合渲染。

### Fixed

- 修复 AndroidManifest 缺失 `INTERNET` 权限导致的网络请求 `EPERM` 崩溃。
