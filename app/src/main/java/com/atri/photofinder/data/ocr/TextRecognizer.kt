package com.atri.photofinder.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 认出图片里的文字。
 *
 * 特点：**完全在手机上跑，不联网、不上传、不花钱**。
 * 用的是 Google 的 ML Kit 中文识别包（中文、英文、数字都认）。
 *
 * 工作流程：
 *   图片 → 缩小到合适大小 → 交给识别器 → 拿到文字
 *
 * ⚠️ 读图片这一步踩过大坑（详见 loadScaledBitmap 的注释）：
 *    直接用 BitmapFactory 读相册的图片会**解码失败**，
 *    所以这里用三种方式**逐个尝试**，哪个能用就用哪个。
 */
object TextRecognizer {

    /**
     * 专门的识别线程（单线程）。
     *
     * ⚠️ 为什么要单独开一个线程，而不是用 Dispatchers.IO：
     *   用户的实测症状是"**切到别的 App 后，识别进度会跑一小段然后卡住，
     *   回到 App 又继续**"。
     *
     *   这是典型的"**主线程被冻结**"的表现：
     *   安卓在应用进入后台后会冻结它的**主线程**（looper 停止处理消息）。
     *   图片读取和识别如果最终依赖主线程的 Handler 回调，就会卡在那里，
     *   直到用户回到 App、主线程解冻。
     *
     *   所以这里用一个**独立的单线程调度器**，
     *   整条链路（读图片 → 识别 → 回调）都在这个线程上跑，
     *   和主线程完全无关，切后台也不会被冻住。
     */
    private val ocrDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ocr-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** 识别时用的最大边长（像素）。 */
    private const val MAX_OCR_SIDE = 3000

    /** 认出来的文字至少要有这么长才算"有文字"（避免把噪点当成一个字） */
    private const val MIN_TEXT_LENGTH = 2

    /**
     * 读到的图片至少要有一边达到这个尺寸，否则宁可换另一种读法。
     *
     * 原因：官方缩略图接口有时会返回一张很小的图（比如 300×200），
     * 那种尺寸根本认不出图里的小字。与其拿小图去认，不如用别的接口拿大图。
     */
    private const val MIN_USABLE_SIDE = 1400

    /** 懒加载的识别器；整个 App 共用一个，避免重复初始化 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别一张图片里的文字。
     *
     * @return 认出来的文字；如果图里没文字（或认出来的太短）就返回 null
     */
    suspend fun recognize(context: Context, uri: String): String? {
        val bitmap = loadScaledBitmap(context, uri) ?: return null
        return try {
            val raw = runRecognition(bitmap)
            val cleaned = clean(raw)
            cleaned.takeIf { it.length >= MIN_TEXT_LENGTH }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 把图片读进来并缩到合适大小。
     *
     * ⚠️ **这里踩过大坑**：一开始只用 `BitmapFactory.decodeStream`，
     *    结果相册里的图片**全部解码失败**（返回 null），
     *    但 Coil 用同一个地址却能正常显示 —— 说明是"读取方式"的问题，
     *    不是图片本身有问题。
     *
     * 所以改成**三种方式逐个尝试**，第一个成功的就用：
     *   1. `loadThumbnail`  —— 安卓官方为"缩略图"设计的接口，最省内存、最快
     *   2. `ImageDecoder`   —— 安卓 9 以上的新解码器，能直接指定目标尺寸
     *   3. `BitmapFactory`  —— 最老最通用的方式，兜底
     *
     * 每次成功的策略和尺寸都会记在 [lastStrategy] 里，方便自检时报告。
     */
    private suspend fun loadScaledBitmap(context: Context, uri: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)

            // 方式 1：官方缩略图接口（最省内存、最快）
            // 但如果它给的图太小（认不出字），就退回去用别的方式
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        parsed,
                        Size(MAX_OCR_SIDE, MAX_OCR_SIDE),
                        null,
                    )
                } else null
            }.getOrNull()?.let { thumb ->
                val longSide = maxOf(thumb.width, thumb.height)
                if (longSide >= MIN_USABLE_SIDE) {
                    lastStrategy = "loadThumbnail（${thumb.width}×${thumb.height}）"
                    return@withContext thumb
                }
                // 太小了，丢掉，继续试别的方式
                thumb.recycle()
            }

            // 方式 2：新解码器（可以指定目标尺寸，自动等比缩放）
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, parsed)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.isMutableRequired = false
                        val w = info.size.width
                        val h = info.size.height
                        val longSide = maxOf(w, h)
                        if (longSide > MAX_OCR_SIDE) {
                            // 按比例算出目标尺寸
                            val ratio = MAX_OCR_SIDE.toFloat() / longSide.toFloat()
                            decoder.setTargetSize(
                                (w * ratio).toInt().coerceAtLeast(1),
                                (h * ratio).toInt().coerceAtLeast(1),
                            )
                        }
                    }?.also { lastStrategy = "ImageDecoder" }
                } else null
            }.getOrNull()?.let { return@withContext it }

            // 方式 3：老的 BitmapFactory（兜底）
            runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

                var sample = 1
                var longSide = maxOf(bounds.outWidth, bounds.outHeight)
                while (longSide / 2 >= MAX_OCR_SIDE) {
                    sample *= 2
                    longSide /= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }?.also { lastStrategy = "BitmapFactory(采样 ${sample}倍)" }
            }.getOrNull()
        }

    /** 最近一次成功用到的读图方式，自检时报告用 */
    @Volatile
    private var lastStrategy: String = "（还没成功过）"

    /**
     * 真正调用识别器；把回调形式包成可以 await 的形式。
     *
     * 全程跑在 [ocrDispatcher] 这个独立线程上，不碰主线程
     *（主线程在后台会被系统冻结，一碰就卡住）。
     */
    private suspend fun runRecognition(bitmap: Bitmap): String =
        withContext(ocrDispatcher) {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result -> cont.resume(result.text) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        }

    /**
     * 整理认出来的文字：
     *  - 去掉多余的空白，把换行统一成一个空格（方便以后做搜索匹配）
     *  - 顺手干掉零宽字符之类的脏东西
     */
    private fun clean(raw: String): String =
        raw.replace(Regex("[\\u200B-\\u200F\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 自检：对一张图跑完整流程，把每一步的**真实数据**报出来。
     *
     * 用途：万一"认不出文字"，用它判断卡在哪一步
     *   - 三种读图方式都失败 → 报"三种方式都失败"
     *   - 读到了但识别返回空 → 报识别结果长度
     */
    suspend fun selfTest(context: Context, uri: String): String {
        lastStrategy = "（还没成功过）"
        val bitmap = loadScaledBitmap(context, uri)
            ?: return "① 读图：三种方式全部失败"

        val info = "① 读图成功（方式: $lastStrategy），尺寸: ${bitmap.width}×${bitmap.height}"
        return try {
            val raw = runRecognition(bitmap)
            val cleaned = clean(raw)
            """
            $info
            ② 识别原始结果长度: ${raw.length} 个字
            ③ 洗过之后的文字: ${if (cleaned.isBlank()) "（空）" else cleaned.take(80)}
            """.trimIndent()
        } catch (e: Exception) {
            "$info\n② 识别出错: ${e.message}"
        } finally {
            bitmap.recycle()
        }
    }
}
