# MeaPet —— 梅尔桌宠

Android 上的 Live2D AI 桌宠：在主页与系统悬浮窗里渲染 Live2D 模型，接入 OpenAI 兼容 API 做多轮聊天，并带本地记忆与 Material You 主题。

本项目由 [suan-11/mea-pet-public](https://github.com/suan-11/mea-pet-public) 衍生。

---

## 功能

- **Live2D 模型** — Cubism SDK 渲染，支持触摸交互、视角跟随与分区语音反馈
- **AI 聊天** — OpenAI 兼容 API（可自建中转），多轮对话、System Prompt、记忆上下文注入；聊天记录本地持久化，重启不丢
- **本地记忆** — 由大模型在对话中自主判断该记什么（事实 / 特质 / 短期），并按设定轮次自动摘要为长期记忆；事实与特质永久保留，可在首页菜单「查看记忆」中查看与删除
- **多主题配色** — Material You 动态取色 + 多套预设色板，支持浅色 / 深色 / 跟随系统
- **悬浮窗模式** — 前台 Service 浮窗常驻，拖拽 / 捏合缩放；双击唤起悬浮菜单（关闭悬浮窗 / 唤起输入），快速三击直接关闭；悬浮窗内可直接输入聊天，AI 回复以带尾巴的气泡显示在人物旁
- **检测更新** — 启动静默检查 GitHub Releases；关于页可手动检测

## 开始使用

### 前置要求

| 项目 | 要求 |
|------|------|
| **Android 版本** | 8.0（API 26）或更高 |
| **API 端点** | 一个 OpenAI 兼容的 API 端点（可自部署或使用第三方服务） |
| **Live2D Cubism Core** | 需自行下载（见下方说明） |
| **构建工具**（手动编译需要） | JDK 21+、Android SDK 36+、Gradle（可使用 wrapper） |

### 获取方式

#### 方式一：下载发行版（推荐）

从 [Releases](https://github.com/llz121517/mea-pet-mobile/releases) 页面下载最新的 APK 直接安装，无需自行编译。

```bash
adb install MeaPet-v1.4.0.apk
```

#### 方式二：手动编译

**1. 克隆仓库**

```bash
git clone https://github.com/llz121517/mea-pet-mobile.git
cd mea-pet-mobile
```

**2. 下载 Live2D Cubism Core**

MeaPet 依赖 Live2D Cubism Core 原生库，受 Live2D 专有软件许可协议保护，**不随仓库分发**。你需要手动下载并放入项目：

1. 前往 [Live2D 官方下载页](https://www.live2d.com/download/cubism-sdk/download-java/) 下载 **Cubism 5 Java SDK**
2. 解压后找到 `Core/android/Live2DCubismCore.aar`
3. 将 `Live2DCubismCore.aar` 复制到本项目的 `app/libs/` 目录

```
MeaPet/
└── app/
    └── libs/
        └── Live2DCubismCore.aar   ← 手动放入
```

**3. 编译 APK**

```bash
./gradlew assembleDebug
```

**4. 安装**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 配置

应用安装后，在设置页填入以下信息：

| 字段 | 说明 |
|------|------|
| **API Key** | API 密钥。**可留空**——本地模型（Ollama / LM Studio 等）无需鉴权；云端服务缺 Key 会在请求时提示填写 |
| **API 地址** | OpenAI 兼容的 API 基础 URL（可带或不带 `/v1`，客户端会自动规范化） |
| **模型** | 使用的模型名称（如 `gpt-4o-mini`）；也可点「获取模型列表」从端点拉取后点选 |
| **Temperature** | 生成温度 (0.0–2.0) |
| **最大 Token** | 单次响应最大 Token 数 |

设置页「记忆系统」分区还可调整：

| 选项 | 说明 |
|------|------|
| **启用记忆** | 总开关。关闭后不再记录、不注入记忆上下文，对话也不会外发给摘要模型 |
| **自动摘要** | 是否定期把短期记忆压缩为长期记忆 |
| **摘要轮次** | 每隔多少轮对话触发一次摘要（3–30，默认 10） |

> 记忆由大模型在回复时自行判断并创建，会额外占用少量输出 Token。使用指令遵循能力较弱的小模型时，可能出现偶尔不创建记忆的情况——这不影响正常聊天。

## 技术栈

```
Live2D Cubism  ·  Jetpack Compose  ·  Ktor  ·  Coroutines  ·  GLSurfaceView
```

## Live2D 模型来源

应用内展示的梅尔 Live2D 模型资源来自社区作品，原始出处：

- [Live2D模型分享 - 梅娅 / Bilibili](https://www.bilibili.com/video/BV1AoX7BXEaN)

使用该模型时请遵循原作者的发布说明与授权要求。模型版权归原作者所有，与本仓库 MIT 许可证无关。

## 许可证

本项目基于 [MIT](LICENSE) 许可证开源。

本项目包含 Live2D 第三方组件，其许可证条款详见 [NOTICE.md](NOTICE.md)。使用 Live2D Cubism Core 需要单独下载并接受 Live2D 专有软件许可协议。应用内 Live2D 角色模型来源见上文「Live2D 模型来源」。
