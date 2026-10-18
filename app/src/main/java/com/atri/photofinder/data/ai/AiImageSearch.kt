package com.atri.photofinder.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.atri.photofinder.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 「按图片内容搜索」用的 AI 客户端。
 *
 * ## 干什么
 *
 * 用户用一句话描述图长什么样（比如"一只橘猫趴在键盘上"），
 * 我们把这批**已经筛好的候选图**连同描述一起发给云端 AI，
 * 让它挑出最像的几张。
 *
 * ## 关键设计（用户拍板的方案）
 *
 * **不要把整个相册发给 AI。** 流程是：
 * ```
 * ① 用户描述
 * ② 本地按"确定条件"（清晰度/年份/有没有文字）先筛出一小批候选
 * ③ 只把这一小批发给云端 AI
 * ④ AI 返回最接近的几张
 * ```
 * 好处：上传少、花钱少、**不用把 AI 的描述存进数据库**（也就没有"结果过期"的问题）。
 *
 * ## 省钱细节
 *
 * - 图片先缩到长边 [MAX_SIDE] 再发（原图又大又慢又贵）
 * - 请求里带 `detail: "low"`，一张图最多算 1024 个计量单位
 * - 候选数量由用户在设置页控制（默认 20 张）
 */
object AiImageSearch {

    /**
     * 发给 AI 前把图缩到这个长边（像素）。
     *
     * 官方的换算规则是"总像素缩到约 1300×1300"，而且**每张图最多算 1024 个 token**
     * （2000×2000 和 5000×5000 的图消耗是一样的）。
     * 所以缩到 1024 完全够用，还能省上传时间。
     *
     * ⚠️ 官方还有个限制：**一个请求里有 15 张以上图片时，单边最长降到 4096 像素**。
     *    我们缩到 1024，安全。
     */
    private const val MAX_SIDE = 1024

    /** 图片压缩质量 */
    private const val JPEG_QUALITY = 80

    /**
     * 官方的请求体上限是 48 MiB。
     *
     * 我们一张图大概 100~200 KB，50 张也就 10 MB，远低于上限。
     * 这里留个保险：所有图的 base64 加起来超过这个数就停止加图。
     */
    private const val MAX_TOTAL_BASE64_BYTES = 30 * 1024 * 1024

    /** 超时时间：AI 看图比较慢，给足时间 */
    private const val TIMEOUT_SECONDS = 120L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 一笔 AI 花销的估算（给用户看得懂的数字） */
    data class CostEstimate(
        /** 这次要发多少张图 */
        val imageCount: Int,
        /** 大概多少钱（元） */
        val yuan: Double,
    ) {
        val text: String get() = "约 ${String.format("%.3f", yuan)} 元"
    }

    /**
     * 估算花销。
     *
     * 官方文档给的规则：每张图**最多算 1024 个 token**，
     * 不管原图多大（2000×2000 和 5000×5000 消耗一样）。
     * DeepSeek 闲时约 ¥0.0005 / 千 token，高峰翻倍。
     * 这里按**高峰价**估，宁可高估不低估。
     *
     * @param imageCount 这次要发给 AI 看几张图
     * @param withUnderstanding 要不要先做一次"理解搜索意图"（多一次文字请求，很便宜）
     * @param withWebSearch 要不要联网查资料（额外 ¥0.005/次）
     * @param withVerify 要不要复核（**会把初选出来的图再看一遍**，所以看图量大约翻倍）
     */
    fun estimate(
        imageCount: Int,
        withUnderstanding: Boolean = true,
        withWebSearch: Boolean = true,
        withVerify: Boolean = true,
    ): CostEstimate {
        // 初选：一张约 1024 token，按高峰价 ¥0.001 估
        var yuan = imageCount * 0.001
        // 复核：把初选出来的再看一遍。经验上初选会挑出约一半多，这里按 60% 估
        if (withVerify) yuan += imageCount * 0.6 * 0.001
        // 理解意图 + 最终比对：算两次纯文字请求，共约 ¥0.001
        if (withUnderstanding) yuan += 0.001
        // 联网搜索：官方 ¥5 / 1000 次 = 每次 ¥0.005
        if (withWebSearch) yuan += 0.005
        return CostEstimate(imageCount = imageCount, yuan = yuan)
    }

    /** 联网搜索一次的价格（官方 ¥5 / 1000 次成功请求） */
    const val WEB_SEARCH_UNIT_COST = 0.005

    /**
     * 联网搜索一次网页，拿到跟描述相关的资料。
     *
     * 依据：[DeepSeek 网页搜索文档](https://api-docs.deepseek.com/zh-cn/guides/search/)
     *  - `POST {baseUrl}/v1/tools/search`
     *  - 计费：每 1000 次**成功且有结果**的请求 5 元（= 每次 0.005 元）
     *  - 搜索无结果、失败都不计费
     *  - 限速：每秒 5 次
     *
     * @return 搜到的资料摘要；搜不到就返回空串（这时候**不该**当成错误）
     */
    private fun webSearch(settings: AppSettings, query: String): String {
        if (query.isBlank()) return ""
        return runCatching {
            val payload = JSONObject().apply {
                put("query", query.take(128))   // 官方限制：1~128 个字符
                put("count", 5)
                // 要长摘要（600~800 字），它是"适合给模型读"的字段；正文太长了没必要
                put("need_content", false)
            }

            val request = Request.Builder()
                .url(settings.effectiveBaseUrl + "/v1/tools/search")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching ""
                val json = JSONObject(response.body.string())
                val results = json.optJSONArray("results") ?: return@runCatching ""

                buildString {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val summary = item.optString("summary").ifBlank {
                            item.optString("snippet")
                        }
                        if (summary.isBlank()) continue
                        append("· ").append(item.optString("title")).append("：")
                        appendLine(summary)
                    }
                }.trim()
            }
        }.getOrDefault("")
    }

    /**
     * 让 AI 先"想一下"用户到底想找什么。
     *
     * 这一步解决用户提的两个问题：
     *  1. **大范围描述**（"一个人""一辆车"）→ 直接搜图就行，不用查资料
     *  2. **专有名词**（"某个动漫角色""某个作品"）→ AI 可能不认识，
     *     这时让它给出一个**搜索关键词**，我们去联网查清楚这个角色长什么样
     *
     * @return 理解结果：搜索关键词（空 = 不用搜）、给用户看的计划说明、用到的资料
     */
    private fun understand(
        settings: AppSettings,
        description: String,
    ): Understanding {
        val prompt = buildString {
            appendLine("我要在手机相册里找照片。我的描述是：")
            appendLine("「$description」")
            appendLine()
            appendLine("请帮我做两件事：")
            appendLine()
            appendLine("【第一件】把这个描述翻译成一份**外形特征清单**，用来在照片里比对。")
            appendLine("要求：")
            appendLine("· 写成**看得见的具体特征**，比如发色发型、服装样式颜色、")
            appendLine("  明显的配饰、体型、性别、大致年龄段、场景。")
            appendLine("· 一条一行，每条尽量短、尽量具体。")
            appendLine("· 后面比对的人**不认识**我描述的东西，只能靠你这份清单。")
            appendLine("  所以清单要能让人**只看照片就能对上**。")
            appendLine()
            appendLine("【第二件】判断你需不需要联网查资料。")
            appendLine("· 如果是常见事物（一个人、一辆车、一只猫、日落）→ 不用搜，search_query 留空。")
            appendLine("· 如果是**具体的人名/角色名/作品名/产品名**，而你**不确定它长什么样**")
            appendLine("  → 必须搜，search_query 写成容易搜到外貌的查询词。")
            appendLine("  例：「原神 雷电将军 外貌 服装 发色」")
            appendLine("· 如果你本来就清楚它长什么样 → 不用搜，直接把特征写进上面的清单。")
            appendLine()
            appendLine("只输出 JSON，不要写任何别的字，也不要加 ``` 包裹：")
            appendLine("""{"features":"特征清单，一条一行","search_query":"搜索词或空字符串","plan":"一句话说明你打算怎么找"}""")
        }

        val content = runCatching {
            val payload = JSONObject().apply {
                put("model", settings.effectiveModelName)
                put("max_tokens", 4000)
                // 关掉思考模式（默认是开的！）
                // 开着的话 token 会被思维链吃掉，content 可能空白，而且更贵更慢。
                // 我们要的是"输出一份特征清单 JSON"，不需要长篇思考。
                put("thinking", JSONObject().put("type", "disabled"))
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }
                    )
                )
            }

            val request = Request.Builder()
                .url(settings.effectiveBaseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                extractContent(JSONObject(response.body.string()).toString())
            }
        }.getOrNull() ?: return Understanding("", "", "", "", "")

        // 从回复里抠 JSON（AI 有时会包在 ```json 里，或者多说几个字）
        val json = runCatching {
            val cleaned = content
                .replace("```json", "")
                .replace("```", "")
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) null
            else JSONObject(cleaned.substring(start, end + 1))
        }.getOrNull()

        // JSON 解析不出来也别放弃 —— 有些模型会直接写成大白话，那就把整段当特征用
        if (json == null) {
            val fallback = content.trim()
            return if (fallback.isBlank()) Understanding("", "", "", "", content)
            else Understanding("", "", "", features = fallback, raw = content)
        }

        val features = json.optString("features").trim()
        val query = json.optString("search_query").trim()
        val plan = json.optString("plan").trim()

        // 需要联网才搜（省钱：不需要就不调搜索接口）
        val reference = if (query.isBlank()) "" else webSearch(settings, query)

        return Understanding(
            searchQuery = query,
            plan = plan,
            reference = reference,
            features = features,
            raw = content,
        )
    }

    /**
     * "理解搜索意图"这一步的结果。
     *
     * ⚠️ **这个对象会被缓存起来，跨批次复用**（用户要求）。
     *
     * 为什么必须复用：
     *   用户点「继续搜下一批」时，如果每次都重新理解一遍，
     *   模型每次给出的"特征清单"会有**细微差异**（同样的描述，这次说"浅栗色"、
     *   下次说"浅棕色"），于是**判断标准飘了** ——
     *   第一批用标准 A 挑、第二批用标准 B 挑，结果自然不一致。
     *
     *   所以：**只要描述没变，理解只做一次**，
     *   后面的批次都拿同一份特征清单去比对。这样标准统一，结果才可比。
     */
    data class Understanding(
        /** 要去搜什么（空 = 不需要联网） */
        val searchQuery: String,
        /** 给用户看的计划说明 */
        val plan: String,
        /** 联网查到的资料（空 = 没搜或没搜到） */
        val reference: String,
        /**
         * ★ **把描述翻译成"外形特征清单"**（这一步是成败关键）。
         *
         * 为什么必须有它：
         *   直接问 AI「这张图是不是【某个角色】」是**识别题** ——
         *   模型不认识就只能答"不是"，于是**一张都返不回来**。
         *   换成「这几张里哪张最符合【黑色长发、紫色和服、头上有一个发饰】」，
         *   就变成**比对题** —— 模型不需要认识角色，只要看图找特征，效果好得多。
         */
        val features: String,
        /** 原始回复（诊断用，让用户能看到 AI 到底说了什么） */
        val raw: String,
        /** 这份理解是基于哪条描述算出来的 —— 描述变了就得重算 */
        val forDescription: String = "",
    )

    /**
     * 只做"理解"这一步（不比对图片）。
     *
     * 给 ViewModel 用：它可以把结果缓存起来，后续批次直接复用，
     * 不必每批都重新理解（避免单次理解结果波动导致搜索标准飘）。
     */
    suspend fun understandOnly(
        settings: AppSettings,
        description: String,
        onStage: (String) -> Unit = {},
    ): Understanding = withContext(Dispatchers.IO) {
        onStage("正在理解你的描述…")
        val u = understand(settings, description)
        onStage(
            when {
                u.searchQuery.isNotBlank() && u.reference.isNotBlank() -> "已联网查到资料，正在比对…"
                u.searchQuery.isNotBlank() -> "联网没查到资料，正在比对…"
                else -> "已理解描述，正在比对…"
            }
        )
        u.copy(forDescription = description)
    }

    /**
     * 让 AI 从候选图里挑出最符合描述的。
     *
     * @param context 用来读图片
     * @param settings 用户设置（密钥、服务商、模型名、候选上限）
     * @param description 用户的描述，比如"一只橘猫趴在键盘上"
     * @param candidates 候选图：`(照片编号, 图片地址)` 的列表
     * @param useUnderstanding 要不要先"理解搜索意图"（必要时联网查资料）。
     *        关掉的话就是单纯拿描述去比对，更快更便宜，但认不出专有名词。
     * @param cachedUnderstanding **上一批次已经算好的理解结果**。
     *        传了就**直接复用、不再重新理解**（用户明确要求）。
     *        为什么：每批都重新理解会得到略微不同的特征清单，
     *        判断标准就飘了 —— 第一批一个标准、第二批另一个标准，结果自然不一致。
     *        只有描述变了才该重算。
     * @param verify 要不要**复核**（逐张确认，剔掉不像的）。
     *        开着精度高很多，但会多花一点钱（只处理初选出来的那些）。
     * @param onStage 进度回调，让界面能显示"正在干什么"
     * @return 命中的照片编号（**按 AI 认为的相似度从高到低**）和一段给用户看的说明
     */
    suspend fun search(
        context: Context,
        settings: AppSettings,
        description: String,
        candidates: List<Pair<Long, String>>,
        useUnderstanding: Boolean = true,
        cachedUnderstanding: Understanding? = null,
        verify: Boolean = true,
        onStage: (String) -> Unit = {},
    ): Result {
        if (candidates.isEmpty()) return Result(emptyList(), "没有候选图片。")
        if (description.isBlank()) return Result(emptyList(), "请先描述一下图长什么样。")

        return withContext(Dispatchers.IO) {
            runCatching {
                // ---- 0. 先理解"用户到底想找什么"（必要时联网查资料）----
                var reference = ""
                var plan = ""
                var features = ""
                var searched = false
                var understandRaw = ""
                var searchQueryUsed = ""

                // ⚠️ 优先复用**缓存的理解结果**（继续搜下一批时）。
                //    只有当缓存为空（第一批）或缓存是基于别的描述算的，才重新理解。
                //
                // ⚠️ 注意判断顺序：`useUnderstanding` 必须放最前面 ——
                //    用户如果把"理解"开关关掉，就不该还拿缓存去比对。
                val reusable = cachedUnderstanding?.takeIf {
                    useUnderstanding &&
                        it.forDescription == description &&
                        it.features.isNotBlank()
                }

                if (reusable != null) {
                    // 复用：不再调接口，直接用上次的清单
                    plan = reusable.plan
                    reference = reusable.reference
                    features = reusable.features
                    understandRaw = reusable.raw
                    searchQueryUsed = reusable.searchQuery
                    searched = reusable.searchQuery.isNotBlank() && reference.isNotBlank()
                    onStage("沿用上次的理解结果，正在比对…")
                } else if (useUnderstanding) {
                    onStage("正在理解你的描述…")
                    val u = understand(settings, description)
                    plan = u.plan
                    reference = u.reference
                    features = u.features
                    understandRaw = u.raw
                    searchQueryUsed = u.searchQuery
                    searched = u.searchQuery.isNotBlank() && reference.isNotBlank()
                    onStage(
                        when {
                            searched -> "已联网查到资料，正在比对…"
                            u.searchQuery.isNotBlank() -> "联网没查到资料，正在比对…"
                            else -> "已理解描述，正在比对…"
                        }
                    )
                }

                // ---- 1. 把候选图缩好、转成 base64 ----
                // 顺便盯着总体积，别超过官方 48 MiB 的请求体上限
                onStage("正在准备 ${candidates.size} 张候选图…")
                val prepared = ArrayList<Pair<Long, String>>(candidates.size)
                var totalBytes = 0
                var skippedForSize = 0
                candidates.forEach { (id, uri) ->
                    val base64 = loadScaledBase64(context, uri) ?: return@forEach
                    if (totalBytes + base64.length > MAX_TOTAL_BASE64_BYTES) {
                        // ⚠️ 撞上体积保险闸：**记下来，最后要告诉用户**。
                        //    不然用户填了 500 张、实际只比了 250 张，会莫名其妙。
                        skippedForSize++
                        return@forEach
                    }
                    totalBytes += base64.length
                    prepared += id to base64
                }
                if (prepared.isEmpty()) {
                    return@runCatching Result(emptyList(), "候选图片都读不出来，没办法比对。")
                }
                // 把"因为体积限制被砍掉多少张"记下来，拼进最后的说明里
                val sizeNote = if (skippedForSize > 0) {
                    "⚠️ 有 $skippedForSize 张因为单次请求体积限制没能一起发出去，" +
                        "下次点「继续搜下一批」会优先比它们。"
                } else ""

                // ---- 2. 拼请求 ----
                onStage("正在让 AI 比对这 ${prepared.size} 张图…")
                val body = buildRequestBody(settings, description, prepared, features, reference)
                val url = settings.effectiveBaseUrl + "/v1/chat/completions"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${settings.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                // ---- 3. 发出去 ----
                client.newCall(request).execute().use { response ->
                    val raw = response.body.string()
                    if (!response.isSuccessful) {
                        return@runCatching Result(
                            emptyList(),
                            "AI 服务返回错误（${response.code}）：${shorten(raw)}",
                        )
                    }

                    // ---- 4. 解析 AI 说的话 ----
                    val described = describeResponse(raw)
                    val content = extractContent(raw)

                    if (content.isNullOrBlank()) {
                        // 空回复是最难查的情况 → 把结构原样报出来
                        return@runCatching Result(
                            emptyList(),
                            "AI 返回了**空内容**（正文和思维链都是空的）。\n" +
                                "可能是 max_tokens 被思考过程吃光了，或模型名不对。",
                            raw = "【比对阶段的完整返回】\n$described",
                        )
                    }

                    val picked = parsePickedIds(content, prepared.map { it.first })

                    // ---- 5. 复核：逐张确认，剔掉不像的 ----
                    // 用户反馈"49 张里只有 26 张对" → 初选太宽松，加这一步收紧。
                    //
                    // ⚠️ 被剔掉的**也要留着给用户看** ——
                    //    复核是"宁可错杀"，万一杀错了，用户要能看到并找回来。
                    val byId = prepared.toMap()
                    val outcome = if (verify && picked.isNotEmpty()) {
                        val pics = picked.mapNotNull { id -> byId[id]?.let { id to it } }
                        verifyPicked(
                            settings = settings,
                            description = description,
                            features = features,
                            reference = reference,
                            pictures = pics,
                            onStage = onStage,
                        )
                    } else {
                        VerifyOutcome(passed = picked, rejected = emptyList())
                    }
                    val finalIds = outcome.passed
                    val rejectedIds = outcome.rejected
                    val removed = rejectedIds.size

                    // 把"计划"和"有没有联网"附在说明里，让用户知道 AI 干了什么
                    val note = buildString {
                        if (plan.isNotBlank()) append(plan)
                        if (searched) {
                            if (isNotEmpty()) append("\n")
                            append("（已联网查过资料）")
                        }
                    }

                    val diagnose = "【理解阶段】\n" +
                        understandRaw.ifBlank { "（没有做这一步）" } +
                        "\n\n【比对阶段】\n" +
                        "AI 回复的正文：\n${content.ifBlank { "（空白）" }}\n\n" +
                        "初选出的编号：${if (picked.isEmpty()) "（无）" else picked.joinToString(",")}" +
                        "\n\n【复核阶段】\n" +
                        (if (!verify) "（没有做这一步）"
                        else "复核后保留：${if (finalIds.isEmpty()) "（无）" else finalIds.joinToString(",")}" +
                            "\n剔掉了 $removed 张") +
                        (if (sizeNote.isNotBlank()) "\n\n【体积限制】\n$sizeNote" else "") +
                        "\n\n【比对阶段的原始信息】\n$described"

                    // 说明 = 挑选结果 + 体积提示 + 计划 + 是否联网
                    val explain = buildString {
                        append(
                            "AI 从 ${prepared.size} 张候选里挑出 ${picked.size} 张" +
                                (if (verify && removed > 0) "，复核后保留 ${finalIds.size} 张" else "") +
                                "。"
                        )
                        if (sizeNote.isNotBlank()) {
                            append("\n")
                            append(sizeNote)
                        }
                        if (note.isNotBlank()) {
                            append("\n")
                            append(note)
                        }
                    }

                    // 把这次用的理解结果一起带回去，ViewModel 会缓存它给下一批复用
                    val usedUnderstanding = Understanding(
                        searchQuery = searchQueryUsed,
                        plan = plan,
                        reference = reference,
                        features = features,
                        raw = understandRaw,
                        forDescription = description,
                    )

                    if (finalIds.isEmpty()) {
                        Result(
                            photoIds = emptyList(),
                            rejectedIds = rejectedIds,
                            message = "AI 判定这批里没有符合的。\n$explain",
                            raw = diagnose,
                            understanding = usedUnderstanding,
                        )
                    } else {
                        Result(
                            photoIds = finalIds,
                            rejectedIds = rejectedIds,
                            message = explain,
                            raw = diagnose,
                            understanding = usedUnderstanding,
                        )
                    }
                }
            }.getOrElse { e ->
                Result(emptyList(), "调用 AI 失败：${e.message ?: e::class.simpleName}")
            }
        }
    }

    /**
     * 测试连接：用最小的请求确认"地址 + 密钥 + 模型名"三样都对。
     * 在设置页点"测试连接"时用。
     */
    suspend fun testConnection(settings: AppSettings): String = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("model", settings.effectiveModelName)
                put("max_tokens", 64)
                // 测试连接也要关思考：只回两个字，开着思考会白花 token
                put("thinking", JSONObject().put("type", "disabled"))
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", "回复两个字：可以")
                        }
                    )
                )
            }

            val request = Request.Builder()
                .url(settings.effectiveBaseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body.string()
                if (response.isSuccessful) {
                    val reply = extractContent(raw).orEmpty().trim()
                    "✅ 连接成功！模型回复：${reply.ifBlank { "(空)" }}"
                } else {
                    "❌ 失败（${response.code}）\n\n" +
                        "常见原因：\n" +
                        "· 密钥填错了（去服务商网站重新复制一次）\n" +
                        "· 账户余额不足\n" +
                        "· 模型名写错了（现在是「${settings.effectiveModelName}」）\n" +
                        "· 服务地址写错了（现在是「${settings.effectiveBaseUrl}」）\n\n" +
                        "服务返回：${shorten(raw)}"
                }
            }
        }.getOrElse { e ->
            "❌ 网络不通：${e.message ?: e::class.simpleName}\n\n" +
                "检查一下：手机有没有联网？服务地址对不对？"
        }
    }

    // ---------------- 内部实现 ----------------

    /**
     * 把候选图和"特征清单"拼成请求体。
     *
     * ⚠️ **这里的设计是这次修 bug 的核心**。
     *
     * 之前是让 AI 回答「这张图是不是【某个角色】」—— 那是**识别题**，
     * 模型不认识这个角色就只能答"不是"，于是**一张都返不回来**。
     *
     * 现在改成「这几张里哪张最符合【这些外形特征】」—— 那是**比对题**，
     * 模型不需要认识角色，只要照着清单在图里找就行，效果好得多。
     */
    private fun buildRequestBody(
        settings: AppSettings,
        description: String,
        prepared: List<Pair<Long, String>>,
        features: String,
        reference: String,
    ): String {
        val content = JSONArray()

        content.put(
            JSONObject().apply {
                put("type", "text")
                put(
                    "text",
                    buildString {
                        appendLine("我在手机相册里找照片，我的描述是：$description")
                        appendLine()

                        // ★ 特征清单 —— 让 AI 按"看得见的特征"去比对，而不是去认它认不出的东西
                        if (features.isNotBlank()) {
                            appendLine("我先把要找的东西拆成了这些**外形特征**：")
                            appendLine(features)
                            appendLine()
                        }

                        // 联网查到的资料（如果有）
                        if (reference.isNotBlank()) {
                            appendLine("另外，我查到的相关资料是：")
                            appendLine(reference)
                            appendLine()
                        }

                        appendLine("下面有 ${prepared.size} 张照片，编号从 1 到 ${prepared.size}。")
                        appendLine()
                        appendLine("请按下面的方式做（**这一步是「比对」，不是让你「认」出这是什么**）：")
                        appendLine("1. 一张一张看，把每张照片里**能对上的特征**数一数。")
                        appendLine("2. 挑出对上的特征**最多的那些**，按符合程度从高到低排列。")
                        appendLine()
                        appendLine("判断标准要**宽松**：")
                        appendLine("· 只要求**大致符合**。角度不同、光线不同、清晰度低、")
                        appendLine("  只是侧脸或背影、是真人翻拍或cosplay、是截图或同人图 ——")
                        appendLine("  **这些都不影响入选**，只要主要特征对得上就算。")
                        appendLine("· 宁可选得**多一点**，也不要把可能的漏掉。")
                        appendLine("· 哪怕只有一半特征对上，也要列出来（排在后面）。")
                        appendLine("· **只有当这张图明显完全不相干时，才不要它。**")
                        appendLine()
                        appendLine("⚠️ 只返回编号，用英文逗号分隔，从最像到最不像，不要写任何别的字。")
                        appendLine("例如：3,1,7,12")
                        appendLine("如果**真的**一张都不沾边，才回复：无")
                    }
                )
            }
        )

        // 再逐张附图，每张前面标一个编号
        prepared.forEachIndexed { index, (_, base64) ->
            content.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", "第 ${index + 1} 张：")
                }
            )
            content.put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64")
                            // 省钱：低细节模式，一张图最多 1024 计量单位
                            put("detail", "low")
                        }
                    )
                }
            )
        }

        return JSONObject().apply {
            put("model", settings.effectiveModelName)
            put("max_tokens", 2000)
            /*
             * ⚠️ **必须关掉思考模式**（默认是开的）。
             *
             * 不关的后果（真实踩过）：
             *   模型看完图以后，把整段"我在看什么、这张像不像…"的推理
             *   写进了 `reasoning_content`，而我们要的编号在 `content` 里 ——
             *   但 token 被思考过程吃光了，`content` 就成了**空白**。
             *   表现就是：钱花了、请求成功了、**一张图都没搜出来**。
             *
             * 这个任务是"照着特征清单挑图"，不需要长篇思考，
             * 关掉之后又快又省，而且**回答一定会出现在 content 里**。
             */
            put("thinking", JSONObject().put("type", "disabled"))
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    }
                )
            )
        }.toString()
    }

    /**
     * 从 OpenAI 格式的回复里取出正文。
     *
     * ⚠️ **这里有坑，踩过**：
     * DeepSeek 的**思考模式默认是打开的**（[文档](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/)），
     * 此时模型会把内容写进 **`reasoning_content`（思维链）**，
     * 而 `content` 可能是**空的** —— 尤其当 `max_tokens` 被思考过程吃光时。
     *
     * 只读 `content` 的后果：**拿到空白字符串**，
     * 表现就是"AI 的原始回复"弹窗里一片空白、一张图都搜不出来。
     *
     * 所以这里**先读 content，空了就回退读 reasoning_content**。
     */
    private fun extractContent(raw: String): String? = runCatching {
        val message = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        val content = message.optString("content").trim()
        if (content.isNotEmpty()) return@runCatching content

        // content 空了 → 退回读思维链（至少能看出模型在说什么）
        message.optString("reasoning_content").trim().ifEmpty { null }
    }.getOrNull()

    /**
     * 把回复里的正文和思维链都取出来，拼成一段给人看的文字。
     *
     * 诊断弹窗用 —— 让用户（和我）能看出**模型到底说了什么**。
     * 如果两个都是空的，就把原始 JSON 截一段出来，这样至少知道结构长什么样。
     */
    private fun describeResponse(raw: String): String = runCatching {
        val message = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        val content = message.optString("content").trim()
        val reasoning = message.optString("reasoning_content").trim()

        buildString {
            if (content.isNotEmpty()) {
                appendLine("【正文 content】")
                appendLine(content)
            }
            if (reasoning.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("【思维链 reasoning_content】")
                appendLine(reasoning)
            }
            if (isEmpty()) {
                appendLine("（正文和思维链都是空的，下面是原始返回的片段）")
                appendLine(shorten(raw, 800))
            }
        }.trim()
    }.getOrElse { shorten(raw, 800) }

    /**
     * 解析 AI 返回的编号。
     *
     * 容错：AI 有时会多写几个字（比如"3,1,7"变成"答案：3,1,7"），
     * 所以这里只把**数字**抠出来，再按顺序对应回照片编号。
     */
    private fun parsePickedIds(content: String, candidateIds: List<Long>): List<Long> {
        // "无" 表示没有符合的
        if (content.contains("无") && !content.any { it.isDigit() }) return emptyList()

        val numbers = Regex("\\d+").findAll(content)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..candidateIds.size }
            .distinct()
            .toList()

        return numbers.map { candidateIds[it - 1] }
    }

    /**
     * 复核结果：哪些留下了、哪些被剔掉了。
     *
     * 为什么要连"剔掉的"一起返回：
     *   用户反馈"搜索准确度 100%，但我不清楚被排除的图片里有没有我想要的角色"。
     *   复核是"宁可错杀"的，所以**必须让用户能检查被杀掉的那些**，
     *   否则漏掉的东西用户永远发现不了。
     */
    private data class VerifyOutcome(
        /** 复核通过、保留的编号 */
        val passed: List<Long>,
        /** 复核剔掉的编号（等于"初选上了、但复核说不像"） */
        val rejected: List<Long>,
    )

    /**
     * ★ **复核**：逐张确认初选出来的图到底对不对。
     *
     * ## 为什么加这一步（用户实测反馈）
     *
     * 用户反馈：**"返回了 49 张图，但只有 26 张是正确的"**（约 47% 是误报）。
     *
     * 原因是我把初选的提示词写得**很宽松**（怕漏掉，所以"哪怕只有一半特征对上也要列出来"），
     * 结果太松了。
     *
     * ## 为什么"复核"比"初选"准
     *
     * 这是两种**难度完全不同**的任务：
     *
     * | 任务 | 题干 | 难度 |
     * |---|---|---|
     * | 初选 | "这 50 张里**哪些**像？" | 难 —— 要同时扫 50 张还要排序 |
     * | **复核** | "这**一张**是不是？" | **简单** —— 只看一张，可以仔细看 |
     *
     * 所以让模型**分两步走**：先宽松地捞一批，再严格地逐张筛掉不确定的。
     *
     * ## 成本
     *
     * 只处理"已经初选出来的那几十张"，不是全库，所以增量有限。
     */
    private fun verifyPicked(
        settings: AppSettings,
        description: String,
        features: String,
        reference: String,
        pictures: List<Pair<Long, String>>,
        onStage: (String) -> Unit,
    ): VerifyOutcome {
        if (pictures.isEmpty()) return VerifyOutcome(emptyList(), emptyList())

        val allIds = pictures.map { it.first }

        onStage("正在复核这 ${pictures.size} 张（剔掉不像的）…")

        val content = JSONArray()

        content.put(
            JSONObject().apply {
                put("type", "text")
                put(
                    "text",
                    buildString {
                        appendLine("我要找的东西是这样的：$description")
                        appendLine()
                        if (features.isNotBlank()) {
                            appendLine("它的**外形特征**是：")
                            appendLine(features)
                            appendLine()
                        }
                        if (reference.isNotBlank()) {
                            appendLine("参考资料：")
                            appendLine(reference)
                            appendLine()
                        }
                        appendLine("下面是 ${pictures.size} 张照片，编号 1 到 ${pictures.size}。")
                        appendLine()
                        appendLine("请**一张一张**判断：这一张**是不是**（或是不是这个角色）？")
                        appendLine()
                        appendLine("⚠️ 用户反馈过：上一版判得太严，**把对的也剔掉了**（6 张里误杀 4 张）。")
                        appendLine("   所以这一版请**偏宽松**。原则是：")
                        appendLine("   **「像」就留下，只有「明显不相干」才剔掉。**")
                        appendLine()
                        appendLine("具体怎么判断：")
                        appendLine("· 先看这几样**关键特征**：发型发色、眼睛颜色、标志性配饰、")
                        appendLine("  服装款式，以及角色的**特有标志**（马耳、兽耳、翅膀、角这种）。")
                        appendLine("· **只要其中有 2 项以上对得上，就算「是」**（留下）。")
                        appendLine("· 下列情况**一律算「是」**（都不影响判断）：")
                        appendLine("  - 侧脸、背影、半身、只露一部分")
                        appendLine("  - 角度不同、光线暗、清晰度低、有遮挡")
                        appendLine("  - 截图、插画、同人图、真人 cosplay、手办照片")
                        appendLine("  - 换了衣服、换了场景、不同画风")
                        appendLine("  - **拿不准的**（宁可留下让用户自己看）")
                        appendLine("· **只有下面这种情况才算「不是」**：")
                        appendLine("  画面里**明显是另一个东西** —— 比如风景、食物、")
                        appendLine("  或者**性别/发色/整体形象都完全对不上的人**。")
                        appendLine("· 同一个作品里的其他角色：**如果只是发色不同、但整体很像，算「是」**；")
                        appendLine("  只有在**关键特征明显矛盾**时（比如特征要求马耳、但这人没有耳朵）才算「不是」。")
                        appendLine()
                        appendLine("⚠️ 只输出 JSON 数组，每项是「编号」和「是/不是」，")
                        appendLine("不要写任何别的字，也不要加 ``` 包裹。格式：")
                        appendLine("""[{"i":1,"y":true},{"i":2,"y":false}]""")
                    }
                )
            }
        )

        pictures.forEachIndexed { index, (_, base64) ->
            content.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", "第 ${index + 1} 张：")
                }
            )
            content.put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64")
                            put("detail", "low")
                        }
                    )
                }
            )
        }

        val payload = JSONObject().apply {
            put("model", settings.effectiveModelName)
            put("max_tokens", 2000)
            put("thinking", JSONObject().put("type", "disabled"))
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    }
                )
            )
        }

        val request = Request.Builder()
            .url(settings.effectiveBaseUrl + "/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val reply = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching ""
                extractContent(response.body.string()).orEmpty()
            }
        }.getOrDefault("")

        // 复核失败 → 不剔，全部保留初选结果（宁可留着让用户自己看）
        if (reply.isBlank()) return VerifyOutcome(allIds, emptyList())

        val passed = parseVerifyResult(reply, allIds)
        // 剔掉的 = 全部 - 保留的
        val rejected = allIds.filter { it !in passed }
        return VerifyOutcome(passed = passed, rejected = rejected)
    }

    /**
     * 解析复核结果。
     *
     * 容错：AI 可能写成 `[{"i":1,"y":true},...]`，
     * 也可能写成 `1:是 2:不是` 这种大白话。
     * 所以两种格式都试。
     */
    private fun parseVerifyResult(reply: String, ids: List<Long>): List<Long> {
        val cleaned = reply.replace("```json", "").replace("```", "")

        // 先试标准 JSON 数组
        val json = runCatching {
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start < 0 || end <= start) null
            else JSONArray(cleaned.substring(start, end + 1))
        }.getOrNull()

        if (json != null && json.length() > 0) {
            val passed = ArrayList<Long>(ids.size)
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val idx = item.optInt("i", -1)
                val yes = item.optBoolean("y", false)
                val id = ids.getOrNull(idx - 1) ?: continue
                if (yes) passed += id
            }
            if (passed.isNotEmpty() || json.length() > 0) return passed
        }

        // 退路：把"是"周围的数字抠出来
        val lines = cleaned.split('\n', '，', ',', ';', '；')
        val passed = ArrayList<Long>()
        lines.forEach { line ->
            val isYes = line.contains("是") && !line.contains("不是")
            if (!isYes) return@forEach
            val n = Regex("\\d+").find(line)?.value?.toIntOrNull() ?: return@forEach
            ids.getOrNull(n - 1)?.let { passed += it }
        }
        return if (passed.isEmpty()) ids else passed
    }

    /**
     * 读一张图、缩到 [MAX_SIDE]、压成 JPEG、转 base64。
     *
     * 读不出来就返回 null（那张图跳过，不影响其他图）。
     */
    private fun loadScaledBase64(context: Context, uri: String): String? = runCatching {
        val parsed = android.net.Uri.parse(uri)

        // 先只读尺寸，算缩放比例
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = generateSequence(1) { it * 2 }
            .takeWhile { longSide / it >= MAX_SIDE }
            .last()

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null

        // 如果采样后还是太大，再精缩一次
        val scaled = scaleDown(bitmap, MAX_SIDE)

        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /** 把图按长边缩到不超过 maxSide */
    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= maxSide) return src

        val ratio = maxSide.toFloat() / longSide.toFloat()
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 把长文本截短，避免把一大坨 JSON 塞进界面 */
    private fun shorten(s: String, max: Int = 300): String =
        if (s.length <= max) s else s.take(max) + "…"

    /** 一次搜索的结果 */
    data class Result(
        /** 命中的照片编号，按相似度从高到低 */
        val photoIds: List<Long>,
        /** 给用户看的一句话说明 */
        val message: String,
        /**
         * AI 的**原始回复**（理解阶段 + 比对阶段 + 复核阶段）。
         *
         * 留着它是因为"一张都没搜出来"时，光看一句"没有符合的"根本查不出原因 ——
         * 可能是模型不认识、可能是提示词没听懂、可能是返回格式没解析对。
         * 有这个原文，用户（和我）就能直接看出问题在哪。
         */
        val raw: String = "",
        /**
         * **被复核剔掉的**照片编号。
         *
         * 为什么要返回它：用户说"搜索准确度 100%，但我不清楚被排除的图片里
         * 有没有我想要的角色"。复核是"宁可错杀"的策略，
         * 所以**必须让用户能翻看被杀掉的那些** —— 否则漏掉的东西永远发现不了。
         */
        val rejectedIds: List<Long> = emptyList(),
        /**
         * 这一次用的**理解结果**。
         *
         * ViewModel 把它缓存起来，下一批直接复用 ——
         * 避免每批重新理解导致"特征清单"有细微差异、判断标准飘。
         */
        val understanding: Understanding? = null,
    )
}
