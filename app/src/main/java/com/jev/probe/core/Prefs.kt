package com.jev.probe.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * App-private config store. Holds the three API routes (judge / reply / vision),
 * the relationship description used in Jev's state, the conversation whitelist,
 * plus the context (D stage) and OCR (B stage) switches.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Only key *lengths* are ever logged.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * Every write goes through here and is COMMITTED, not applied.
     *
     * `apply()` is asynchronous: it queues the write and returns. Swiping the
     * app out of Recents (or the process dying another way) can land before the
     * queue drains — which is exactly how a key the user typed and tested
     * vanished on the next launch. Config is small and written on user action,
     * so paying for a synchronous disk write is the right trade.
     */
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        val e = sp.edit()
        e.block()
        e.commit()
    }

    /**
     * Only the real config migrates — and only the real config logs it. The
     * throwaway instances behind the settings test buttons and the KB self-check
     * have nothing to carry over, and used to print one migration line per tap.
     */
    init { if (prefsName == PREFS_MAIN) migrateIfNeeded() }

    /**
     * v1.2 -> v1.3: the single `openrouter_key` becomes the judge route's key.
     * `reply_model` keeps its old storage key, so it carries over untouched.
     */
    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return   // runs exactly once
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.commit()
    }

    // ---------------------------------------------------------------- judge

    /**
     * Whether the judgment route is used at all. OFF by default: most gateways
     * (and every OpenAI-compatible relay) serve `/chat/completions` only and
     * have no `/decisions` endpoint, so calling it can only fail. With this off
     * the app drafts candidate replies and never touches the judge route —
     * neither for the 7 questions nor for ranking.
     */
    var judgeEnabled: Boolean
        get() = sp.getBoolean(K_JUDGE_ENABLED, false)
        set(v) = put { putBoolean(K_JUDGE_ENABLED, v) }

    /** "openrouter" | "typesafe" | "custom". */
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER
        set(v) = put { putString(K_JUDGE_PROVIDER, v.trim()) }

    /** Host root; the path is appended per provider (see [judgeEndpoint]). */
    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_OPENROUTER) ?: DEFAULT_JUDGE_BASE_OPENROUTER
        set(v) = put { putString(K_JUDGE_BASE, v.trim()) }

    var judgeKey: String
        get() = sp.getString(K_JUDGE_KEY, "") ?: ""
        set(v) = put { putString(K_JUDGE_KEY, v.trim()) }

    var judgeModel: String
        get() = sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER) ?: DEFAULT_JUDGE_MODEL_OPENROUTER
        set(v) = put { putString(K_JUDGE_MODEL, v.trim()) }

    /** Back-compat alias so older call sites keep compiling. */
    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    /** OpenAI-compatible base, up to and including `/v1`. */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE
        set(v) = put { putString(K_REPLY_BASE, v.trim()) }

    /** Blank = fall back to [judgeKey]. */
    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = put { putString(K_REPLY_KEY, v.trim()) }

    /** Generative model for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = put { putString(K_REPLY_MODEL, v.trim()) }

    // --------------------------------------------------------------- vision

    /**
     * Blank = the OpenRouter vision default. Deliberately does NOT follow
     * [replyBaseUrl]: a reply host like DeepSeek has no vision endpoint, so
     * inheriting it would silently break OCR.
     */
    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE
        set(v) = put { putString(K_VISION_BASE, v.trim()) }

    /** Blank = fall back to [replyKey] then [judgeKey]. */
    var visionKey: String
        get() = sp.getString(K_VISION_KEY, "") ?: ""
        set(v) = put { putString(K_VISION_KEY, v.trim()) }

    var visionModel: String
        get() = sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) = put { putString(K_VISION_MODEL, v.trim()) }

    // -------------------------------------------------------- context (D)

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = put { putBoolean(K_CTX_ENABLED, v) }

    /** How many recent history entries to inject. */
    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = put { putInt(K_CTX_COUNT, v) }

    /** Auto-summarize a contact once enough history accumulates. */
    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = put { putBoolean(K_AUTO_SUMMARY, v) }

    // ------------------------------------------------------------ OCR (B)

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = put { putString(K_OCR_ENGINE, v.trim()) }

    /** Run generic OCR capture on apps with no dedicated adapter. */
    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = put { putBoolean(K_OCR_UNKNOWN, v) }

    /** Fall back to OCR when an adapted app's node tree comes back empty. */
    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = put { putBoolean(K_OCR_FALLBACK, v) }

    /** Auto-analyze in OCR mode (default off: OCR costs a screenshot each time). */
    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = put { putBoolean(K_OCR_AUTO, v) }

    // ------------------------------------------------------------- existing

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = put { putString(K_REL, v) }

    /**
     * Tone preset for the drafted replies. One of the names in [REPLY_STYLES];
     * an unknown or blank value behaves like 默认风格 (no extra instruction).
     */
    var replyStyle: String
        get() = sp.getString(K_STYLE, DEFAULT_STYLE) ?: DEFAULT_STYLE
        set(v) = put { putString(K_STYLE, v) }

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = put { putBoolean(K_ENABLED, v) }

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = put { putStringSet(K_WHITELIST, v) }

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = put { putInt(K_OPACITY, v.coerceIn(60, 100)) }

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = put { putInt(K_BUBBLE_Y, v) }

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = put { putInt(K_BUBBLE_X, v) }

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = put { putBoolean(K_AUTO, v) }

    // ------------------------------------------------------- overlay sizing

    /** Panel width in px, as dragged by the user. -1 = use the default. */
    var overlayWidth: Int
        get() = sp.getInt(K_OVL_W, -1)
        set(v) = put { putInt(K_OVL_W, v) }

    /** Panel content height in px, as dragged by the user. -1 = use the default. */
    var overlayHeight: Int
        get() = sp.getInt(K_OVL_H, -1)
        set(v) = put { putInt(K_OVL_H, v) }

    /** Forget the dragged size and go back to the default panel. */
    fun resetOverlaySize() = put { putInt(K_OVL_W, -1); putInt(K_OVL_H, -1) }

    // ------------------------------------------------------------- helpers

    /** Reply route key, falling back to the judge key. */
    fun effectiveReplyKey(): String = replyKey.ifBlank { judgeKey }

    /**
     * The tone instruction for the current [replyStyle], ready to drop into a
     * system prompt. Empty for 默认风格, so the default prompt is unchanged.
     */
    fun styleLine(): String {
        val hint = styleInstruction(replyStyle)
        return if (hint.isBlank()) "" else "本轮回复风格：${replyStyle}——$hint"
    }

    /** Vision route key, falling back to reply then judge. */
    fun effectiveVisionKey(): String = visionKey.ifBlank { effectiveReplyKey() }

    /** Full POST URL for the Jev decisions call, per provider. */
    fun judgeEndpoint(): String {
        val base = judgeBaseUrl.trim().trimEnd('/')
        return when (judgeProvider) {
            PROVIDER_TYPESAFE -> "$base/v1/systemone"
            PROVIDER_CUSTOM -> judgeBaseUrl.trim()   // user supplies the full URL
            else -> "$base/alpha/decisions"
        }
    }

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    fun replyEndpoint(): String = "${replyBaseUrl.trim().trimEnd('/')}/chat/completions"

    /** Same shape as [replyEndpoint]; blank falls back to the OpenRouter default. */
    fun visionEndpoint(): String {
        val base = visionBaseUrl.trim().ifBlank { DEFAULT_VISION_BASE }
        return "${base.trimEnd('/')}/chat/completions"
    }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: the judge route is the one that must be configured. */
    /** Whatever route we will actually call needs a key — the judge route when
     *  judgment is on, otherwise the reply route (which falls back to it). */
    fun hasKey(): Boolean =
        if (judgeEnabled) judgeKey.isNotBlank() else effectiveReplyKey().isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "jev_assistant"

        /**
         * Broadcast sent the moment settings are saved, so the live service and
         * the overlay pick the change up immediately. Without it the bubble kept
         * showing a state the user had just turned off in Settings — the two
         * only converged after the service happened to be restarted.
         */
        const val ACTION_SETTINGS_CHANGED = "com.jev.probe.SETTINGS_CHANGED"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_JUDGE_ENABLED = "judge_enabled"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_STYLE = "reply_style"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_OVL_W = "overlay_width"
        private const val K_OVL_H = "overlay_height"

        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_CUSTOM = "custom"

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // Judge route presets.
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"

        // Reply route presets (OpenAI-compatible chat completions).
        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        // Vision route preset (OpenRouter region-available; user may change).
        const val DEFAULT_VISION_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct"
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        // ---------------------------------------------------------------------
        // Built-in model pickers. Typing a model id by hand is the single most
        // common setup mistake, so the settings page offers chips per provider.
        // These are suggestions only — whatever is typed is still sent as-is.
        // ---------------------------------------------------------------------
        val JUDGE_MODELS_OPENROUTER = listOf(DEFAULT_JUDGE_MODEL_OPENROUTER, "typesafe/jev-1.5")
        val JUDGE_MODELS_TYPESAFE = listOf(DEFAULT_JUDGE_MODEL_TYPESAFE, "jev-2")

        val REPLY_MODELS_OPENROUTER = listOf(
            DEFAULT_REPLY_MODEL,
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-sonnet",
            "google/gemini-2.0-flash-001",
            "qwen/qwen-2.5-72b-instruct",
            "meta-llama/llama-3.3-70b-instruct"
        )
        val REPLY_MODELS_DEEPSEEK = listOf("deepseek-chat", "deepseek-reasoner")
        val REPLY_MODELS_DASHSCOPE = listOf("qwen-plus", "qwen-max", "qwen-turbo", "qwen-long")
        /**
         * The operator's own gateway ids. Shown at the top of the reply dropdown
         * under every provider, labelled, because these are what actually gets
         * used — a relay serves its own names, not the upstream ones.
         */
        val MY_GATEWAY_MODELS = listOf("gpt-5.6-terra", "gpt-5.5")

        /** Relay / self-hosted OpenAI-compatible gateways: the usual suspects. */
        val REPLY_MODELS_GENERIC = listOf(
            "gpt-4o-mini", "gpt-4o", "deepseek-chat", "deepseek-reasoner",
            "qwen-plus", "qwen-max", "glm-4-flash", "moonshot-v1-8k",
            "doubao-pro-32k", "claude-3-5-sonnet-20241022", "gemini-1.5-flash"
        )

        val VISION_MODELS_OPENROUTER = listOf(
            DEFAULT_VISION_MODEL,
            "openai/gpt-4o-mini",
            "google/gemini-2.0-flash-001",
            "anthropic/claude-3.5-sonnet"
        )
        val VISION_MODELS_DASHSCOPE = listOf("qwen-vl-max", "qwen-vl-plus")

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"

        // ---------------------------------------------------------------------
        // Reply tone presets. Same idea as the style picker Soul ships on its
        // own "灵感回复" panel: the user picks a voice once and every drafted
        // candidate is written in it. Name -> extra instruction appended to the
        // draft system prompt; the first entry carries no instruction on
        // purpose, so 默认风格 keeps the plain "3 varied strategies" behaviour.
        //
        // Keep the names short: they are rendered as horizontal pills.
        // ---------------------------------------------------------------------
        const val DEFAULT_STYLE = "默认风格"

        val REPLY_STYLES: List<Pair<String, String>> = listOf(
            DEFAULT_STYLE to "",
            "高情商" to "语气高情商：会接话、给对方台阶下，先认可再推进，不冷场也不说教，避免敷衍套话。",
            "温柔暖男" to "语气温柔：句子短而有温度，主动关心对方的状态和感受，像一个靠谱的人在照顾人，不要油腻。",
            "幽默风趣" to "语气幽默：轻松、俏皮，可以适度自嘲或用生活化的比喻，但不要硬抖包袱，不要冒犯对方。",
            "暧昧拉扯" to "语气暧昧：若有若无的试探和留白，暗示多于明说，保留悬念，绝不越界或低俗。",
            "夸夸大师" to "语气真诚夸赞：夸到具体细节上，说明为什么值得夸，不空泛、不夸张、不谄媚。",
            "发疯文学" to "语气夸张戏剧化：短句、重复、感叹号、网络口吻，情绪拉满，但仍然让人看得懂在说什么。",
            "成熟小叔" to "语气稳重：有分寸、给建议但不居高临下，少用感叹号和表情，像比对方年长几岁的过来人。",
            "高智商绅士" to "语气讲究：措辞文雅克制，切入角度要有见识或新意，可以轻轻掉书袋，礼貌而不疏远。"
        )

        /** The instruction for a stored style name; blank for 默认风格 / unknown. */
        fun styleInstruction(style: String): String =
            REPLY_STYLES.firstOrNull { it.first == style }?.second ?: ""
    }
}
