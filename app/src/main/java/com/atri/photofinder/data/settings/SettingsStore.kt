package com.atri.photofinder.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 让整个 App 共用一个设置存储 */
private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 一个 AI 服务商的配置。
 *
 * @param id 内部代号，比如 "deepseek"
 * @param displayName 显示给用户的名字
 * @param baseUrl 服务地址
 * @param modelName 模型名字
 * @param note 一句话说明，显示在选项下面
 */
data class AiProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val note: String = "",
)

/**
 * 用户设置。
 *
 * ⚠️ 重要：API 密钥**只存在这台手机上**，绝不写进代码、绝不上传到 GitHub。
 * 这样别人下载这个 App，也要自己填自己的密钥。
 */
data class AppSettings(
    /** 当前选中的服务商 */
    val provider: AiProviderConfig = AiProviders.DEEPSEEK,

    /** 用户的密钥。空字符串表示还没填。 */
    val apiKey: String = "",

    /** 用户自定义的服务地址；空表示用服务商默认的 */
    val customBaseUrl: String = "",

    /** 用户自定义的模型名；空表示用服务商默认的 */
    val customModelName: String = "",

    /**
     * 一次搜索最多给 AI 看多少张候选图。
     *
     * ⚠️ **上限 500**（原来卡在 50）。用户要求"数量可以自定义，
     *    不超过待搜索的图片数量"。留个上限是因为：一次请求塞太多图会
     *    **很慢、很贵、还容易触发接口的图片数量限制**。
     *
     * ⚠️ **特殊值 `ALL`（-1）表示"全部"**。
     *
     *    为什么要这个特殊值（踩过的 bug）：
     *      用户选「全部 300 张」时，如果把 **300 这个数字**存下来，
     *      后来筛选条件变了、候选涨到 350，存的还是 300 ——
     *      按钮上写着"本次 300"，**既不是全部、还比候选少**。
     *      所以要存"**要全部**"这个**意图**，用的时候才按当时的候选数换算。
     *
     * ⚠️ **不要直接读这个值来显示！** 界面一律用 [effectiveBatch] / [batchLabel]。
     */
    val maxCandidates: Int = 20,
) {
    /** 实际要用的服务地址（去掉末尾多余的斜杠） */
    val effectiveBaseUrl: String
        get() = (customBaseUrl.ifBlank { provider.baseUrl }).trimEnd('/')

    /** 实际要用的模型名 */
    val effectiveModelName: String
        get() = customModelName.ifBlank { provider.modelName }

    /** 密钥填了没有 */
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /** 能不能开始用 AI（密钥是唯一的硬条件） */
    val aiReady: Boolean get() = hasApiKey

    /** 打码后的密钥，显示在界面上用；比如 sk-1234****abcd */
    val maskedApiKey: String
        get() = when {
            apiKey.isBlank() -> "未填写"
            apiKey.length <= 8 -> "****"
            else -> apiKey.take(4) + "****" + apiKey.takeLast(4)
        }

    /** 用户选的是不是"全部" */
    val wantsAll: Boolean get() = maxCandidates == ALL

    /**
     * 在"还剩 [remaining] 张没比对"的情况下，**本次实际会比对多少张**。
     *
     * 这是唯一该拿来显示的算法 —— 界面**不要**直接读 [maxCandidates]，
     * 否则会出现"显示 300、实际只有 200"这种自相矛盾。
     */
    fun effectiveBatch(remaining: Int): Int {
        val r = remaining.coerceAtLeast(0)
        return if (wantsAll) r else r.coerceAtMost(maxCandidates)
    }

    /** 界面上那个按钮该显示的字 */
    fun batchLabel(remaining: Int): String =
        if (wantsAll) "全部 ${effectiveBatch(remaining)} 张"
        else "本次 ${effectiveBatch(remaining)} 张"

    companion object {
        /** `maxCandidates` 取这个值表示"全部" */
        const val ALL = -1

        /** 具体的上限（超过这个数的"具体张数"会被夹住） */
        const val MAX_SPECIFIC = 500
    }
}

/**
 * 内置的几家服务商。DeepSeek 放第一个，作为默认。
 *
 * 都是**兼容 OpenAI 接口格式**的，所以代码只需要写一套。
 */
object AiProviders {
    val DEEPSEEK = AiProviderConfig(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        // deepseek-flash 支持图片输入，一张图最多算 1024 个计量单位
        modelName = "deepseek-flash",
        note = "推荐。便宜，国内可直连。晚上和周末半价",
    )

    val OPENAI_COMPATIBLE = AiProviderConfig(
        id = "openai_compatible",
        displayName = "其它兼容 OpenAI 格式的服务",
        baseUrl = "https://api.openai.com",
        modelName = "gpt-4o-mini",
        note = "比如 OpenAI、通义千问、Kimi 等，需要自己填地址和模型名",
    )

    val ALL = listOf(DEEPSEEK, OPENAI_COMPATIBLE)
}

/** 读写用户的设置 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val API_KEY = stringPreferencesKey("api_key")
        val CUSTOM_BASE_URL = stringPreferencesKey("custom_base_url")
        val CUSTOM_MODEL_NAME = stringPreferencesKey("custom_model_name")
        val MAX_CANDIDATES = intPreferencesKey("max_candidates")
    }

    /** 设置变化时会自动通知界面 */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val providerId = prefs[Keys.PROVIDER_ID] ?: AiProviders.DEEPSEEK.id
        AppSettings(
            provider = AiProviders.ALL.firstOrNull { it.id == providerId } ?: AiProviders.DEEPSEEK,
            apiKey = prefs[Keys.API_KEY] ?: "",
            customBaseUrl = prefs[Keys.CUSTOM_BASE_URL] ?: "",
            customModelName = prefs[Keys.CUSTOM_MODEL_NAME] ?: "",
            // 允许 -1（= 全部）；其它值夹在 1..500
            maxCandidates = (prefs[Keys.MAX_CANDIDATES] ?: 20)
                .let { if (it == AppSettings.ALL) AppSettings.ALL else it.coerceIn(1, AppSettings.MAX_SPECIFIC) },
        )
    }

    /** 取一次当前设置 */
    suspend fun current(): AppSettings = settings.first()

    suspend fun setProvider(provider: AiProviderConfig) {
        context.dataStore.edit { it[Keys.PROVIDER_ID] = provider.id }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[Keys.API_KEY] = key.trim() }
    }

    suspend fun setCustomEndpoint(baseUrl: String, modelName: String) {
        context.dataStore.edit {
            it[Keys.CUSTOM_BASE_URL] = baseUrl.trim()
            it[Keys.CUSTOM_MODEL_NAME] = modelName.trim()
        }
    }

    /** 存"具体多少张"（1~500） */
    suspend fun setMaxCandidates(n: Int) {
        context.dataStore.edit {
            it[Keys.MAX_CANDIDATES] = n.coerceIn(1, AppSettings.MAX_SPECIFIC)
        }
    }

    /**
     * 存"**全部**"这个**意图**（而不是当时的数字）。
     *
     * ⚠️ 这是修 bug 的关键：存意图，用的时候才按当时的候选数换算，
     *    这样条件变了、候选变多，显示也会跟着变。
     */
    suspend fun setMaxCandidatesAll() {
        context.dataStore.edit { it[Keys.MAX_CANDIDATES] = AppSettings.ALL }
    }
}

// 说明：早期这里还有一个 AiMode（"预扫全部" / "搜索时才看"两种模式）。
// 后来用户否决了"预先全扫"的方案（理由：识别结果没法保存，不现实），
// 改成"搜索时只把筛出来的候选图发给 AI"，所以不再需要模式开关，已删除。
