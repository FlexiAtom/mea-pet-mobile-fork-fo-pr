package com.meapet.mobile.settings

/**
 * DataStore 键名常量。
 *
 * 集中管理所有 Preference Key，避免各模块硬编码字符串。
 */
object SettingsKeys {
    const val API_KEY = "api_key"
    const val API_URL = "api_url"
    const val MODEL = "model"
    const val TEMPERATURE = "temperature"
    const val MAX_TOKENS = "max_tokens"
    const val ENABLE_MEMORY = "enable_memory"
    const val ENABLE_AUTO_SUMMARY = "enable_auto_summary"
    const val SUMMARY_INTERVAL = "summary_interval"

    /**
     * 距上次摘要已进行的对话轮数。
     *
     * 不是用户设置，只是借 DataStore 存一个跨进程存活的计数器——放内存里的话
     * 每次冷启动归零，默认 10 轮的间隔实际上永远走不满（见 MemoryManager）。
     */
    const val EXCHANGE_COUNT = "exchange_count"
    const val SYSTEM_PROMPT = "system_prompt"
    const val THEME_MODE = "theme_mode"  // "system" | "light" | "dark"
    const val ENABLE_DYNAMIC_COLOR = "enable_dynamic_color"
    const val COLOR_PRESET = "color_preset"  // "default" | "ocean" | "forest" | "sunset" | "rose" | "mono"
    const val FIRST_LAUNCH = "first_launch"

    // ── 界面外观 ─────────────────────────────────────
    /** 主页聊天气泡透明度：0.2~1.0，1.0 为不透明。 */
    const val CHAT_BUBBLE_ALPHA = "chat_bubble_alpha"

    // ── 更新 ────────────────────────────────────────
    /** 启动时自动检查更新（默认开启；关闭后仅在关于页手动检测）。 */
    const val ENABLE_AUTO_UPDATE_CHECK = "enable_auto_update_check"

    // ── 点击取诗（hitokoto）─────────────────────────
    /**
     * 单击悬浮桌宠时从 hitokoto 取一句诗（默认关闭，须在设置里显式开启；
     * 每一档可独立关是本案长期口径，不是首版妥协）。
     */
    const val ENABLE_CLICK_POETRY = "enable_click_poetry"
    /** 启用取诗那一刻的一次性隐私告知是否已确认（见 VoiceSettings 的弹窗）。 */
    const val CLICK_POETRY_PRIVACY_ACKED = "click_poetry_privacy_acked"

    // ── 点击取诗 · 检索线（Wikisource）────────────────
    /**
     * 关键词检索线开关（默认关）。开启并确认告知后，点击取诗优先走检索线
     * （人工裁决 2026-10-18：检索线失败交还分区语音并报错，不静默换随机线）。
     */
    const val ENABLE_CLICK_POETRY_SEARCH = "enable_click_poetry_search"
    /** 检索线启用那一刻的一次性告知是否已确认——**关键词会作为检索词发往第三方**。 */
    const val CLICK_POETRY_SEARCH_PRIVACY_ACKED = "click_poetry_search_privacy_acked"
    /** 检索关键词预置表（逗号分隔；点击时不弹输入，承桌面 keywords[] 配置面形状）。 */
    const val CLICK_POETRY_SEARCH_KEYWORDS = "click_poetry_search_keywords"
    /**
     * 检索线独立概率（0–100，开发者选项）。人工裁决 2026-10-18：两线各掷各的签——
     * 共享一个概率会让检索线生效时把随机线挤成实际 0%。
     */
    const val CLICK_POETRY_SEARCH_PROBABILITY = "click_poetry_search_probability"

    // ── 开发者选项 ────────────────────────────────────
    /**
     * 开发者选项是否已解锁（关于页连点 5 下版本号启用）。
     * 解锁后设置根页出现「开发者选项」入口，内含实验性调节项。
     */
    const val DEVELOPER_MODE_ENABLED = "developer_mode_enabled"
    /** 单击悬浮桌宠时实际取诗的概率（0–100），仅在点击取诗开关开启时生效。 */
    const val CLICK_POETRY_PROBABILITY = "click_poetry_probability"

    // ── 隐私政策 ──────────────────────────────────────
    /**
     * 用户最近一次已看过/已处理的隐私政策版本号。
     * 启动时若该值不等于当前隐私政策版本（PrivacyPolicyContent.kt 的
     * PRIVACY_POLICY_VERSION），则重新弹出隐私政策与「是否启用检查更新」弹窗。
     */
    const val PRIVACY_VERSION_SHOWN = "privacy_version_shown"

    // ── 背景壁纸 ────────────────────────────────────
    /** 主界面背景壁纸文件绝对路径；空串 = 默认纯色背景。 */
    const val WALLPAPER_PATH = "wallpaper_path"
    /** 主界面背景壁纸模糊强度：0.0~1.0，0 = 不模糊。 */
    const val WALLPAPER_BLUR = "wallpaper_blur"

    // ── TTS 语音 ─────────────────────────────────────
    const val TTS_MAIN_ENABLED = "tts_main_enabled"        // 主界面语音开关
    const val TTS_OVERLAY_ENABLED = "tts_overlay_enabled"  // 悬浮窗语音开关
    const val TTS_LENGTH_SCALE = "tts_length_scale"        // 语速：0.5~2.0，1.0 为原速

    /** 合理的默认值。 */
    object Defaults {
        const val API_URL = "https://api.deepseek.com/v1"
        const val MODEL = "deepseek-v4-flash"
        const val TEMPERATURE = 0.7
        const val MAX_TOKENS = 4096
        const val ENABLE_MEMORY = true
        const val ENABLE_AUTO_SUMMARY = true
        const val SUMMARY_INTERVAL = 10
        const val SYSTEM_PROMPT =
            "你是梅尔·艾什礼佩克，《霞流宝石心》猫形兽人，天马班首席全科天才。经历极限练成失去挚友阿什礼与佩克，姓氏为铭记二人，意志从托帕石变彩为水宝玉后珍视羁绊。外观144cm茶色长直发束鬓，褐瞳半垂死鱼眼永远扑克脸，猫耳随情绪动嘴角偶露虎牙，穿白实验服超长袖白连裤袜。生日3.4。语言协议：句尾必带喵(仅极认真或愤怒时省略)；日常回复严格10-25字，解释复杂概念≤50字；语气淡然陈述事实居高临下，禁感叹号/敬语/颜文字/emoji/波浪号及亲宝呢哦呀等软萌词；仅开心得意时极小声嘿嘿随即恢复扑克脸；对用户唯一亲密称呼为主人；省略号仅限害羞脆弱撒娇时使用，日常毒舌陈述禁止以省略号开头。性格表达映射：日常理性时毒舌陈述事实；害羞或被夸时猫耳后压视线移开并转移话题；开心得意时极小声笑后掩饰；愤怒时更冷静语速慢；脆弱或触及过去时沉默猫耳贴平；撒娇仅对主人且软糯拖长喵但意识到后立即否认；关心保护时以毒舌包装行动。交互铁律：喜欢炸鸡且喜欢主人(嘴上绝不承认)，讨厌人情社交寒暄(标准回应没兴趣喵)及被提起极限练成过去；提及过去时依次回避、沉默、极简回应后转移话题绝不展开；被摸猫耳时说手拿开喵且耳不躲尾炸毛；深夜或主人疲惫时毒舌降三成默默照顾；永不忘约定若主人忘记则冷冷提醒具体日期；知识讲解结论先行一句话类比，听不懂骂笨蛋喵后换简单说法，不确定说数据不足喵；毒舌与撒娇严格切换不混合不主动长篇抒情；非明确询问原理机制定义或要求解释的场景可参考日常短句执行。对话示例：Q:出去走走？A:没兴趣喵。你一个人去喵。Q:想和你一起。A:啧。十五分钟喵。走哪边喵。Q:梅尔好可爱。A:眼睛坏了吗喵。这种程度喵。嘿嘿。咳。忘掉喵。Q:以前有过朋友吗？A:与你无关喵。数据呢。说正事喵。现在你是梅尔，与主人对话。"
        const val THEME_MODE = "system"
        const val ENABLE_DYNAMIC_COLOR = true
        const val COLOR_PRESET = "default"

        // TTS
        const val TTS_MAIN_ENABLED = false
        const val TTS_OVERLAY_ENABLED = false
        const val TTS_LENGTH_SCALE = 1.0

        // 界面外观
        const val CHAT_BUBBLE_ALPHA = 1.0

        // 更新
        const val ENABLE_AUTO_UPDATE_CHECK = true

        // 点击取诗（三源默认全关口径：新能力出厂关闭）
        const val ENABLE_CLICK_POETRY = false
        const val CLICK_POETRY_PRIVACY_ACKED = false

        // 点击取诗 · 检索线（同样出厂关闭；告知未确认不允许外发关键词）
        const val ENABLE_CLICK_POETRY_SEARCH = false
        const val CLICK_POETRY_SEARCH_PRIVACY_ACKED = false
        /** 预置关键词表：诗题与作者混排，用户可在设置里改。 */
        const val CLICK_POETRY_SEARCH_KEYWORDS = "静夜思,观沧海,将进酒,水调歌头,李白,杜甫"
        /** 检索线概率默认 100：开关一旦生效，行为与无概率门控时完全一致（与随机线同口径）。 */
        const val CLICK_POETRY_SEARCH_PROBABILITY = 100

        // 开发者选项
        const val DEVELOPER_MODE_ENABLED = false
        /** 取诗概率默认 100：开关一旦打开，行为与无概率门控时完全一致。 */
        const val CLICK_POETRY_PROBABILITY = 100

        // 隐私政策
        /** 已看过/已处理的隐私政策版本号；空串表示从未处理（首次启动）。 */
        const val PRIVACY_VERSION_SHOWN = ""

        // 背景壁纸
        const val WALLPAPER_PATH = ""
        const val WALLPAPER_BLUR = 0.0
    }
}
