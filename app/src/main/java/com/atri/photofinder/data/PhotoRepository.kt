package com.atri.photofinder.data

import android.content.Context
import com.atri.photofinder.data.db.AlbumOcrStat
import com.atri.photofinder.data.db.OcrCacheDao
import com.atri.photofinder.data.db.OcrCacheEntity
import com.atri.photofinder.data.db.OcrState
import com.atri.photofinder.data.db.PhotoDao
import com.atri.photofinder.data.db.PhotoEntity
import com.atri.photofinder.data.media.MediaStoreReader
import com.atri.photofinder.data.ocr.TextRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * 数据总管。
 *
 * 界面上要数据，都问它要；它负责决定"从相册读"还是"从本地目录读"。
 * 这样界面代码就不用关心这些细节。
 */
class PhotoRepository(
    private val context: Context,
    private val dao: PhotoDao,
    private val cacheDao: OcrCacheDao,
) {

    /** 扫描的进度，界面上显示进度条用 */
    data class ScanProgress(
        val isScanning: Boolean = false,
        val scanned: Int = 0,
        val message: String? = null,
    )

    /** 认字的进度 */
    data class OcrProgress(
        val done: Int = 0,
        val total: Int = 0,
        val withText: Int = 0,
        val isPaused: Boolean = false,
        val finished: Boolean = false,
    ) {
        /** 是不是正在跑（包括暂停中） */
        val isRunning: Boolean get() = total > 0 && done < total
    }

    /** 列出手机里有照片的相册 */
    suspend fun listAlbums(): List<MediaStoreReader.AlbumInfo> =
        MediaStoreReader.listAlbums(context)

    /** 排查用：系统到底交出了哪些图片信息 */
    suspend fun diagnose(): MediaStoreReader.Diagnostic =
        MediaStoreReader.diagnose(context)

    /**
     * 扫描一个相册，把结果存进本地目录。
     *
     * ⚠️ 重要：扫描前会**先清掉别的相册的记录**。
     * 用户明确要求"一次只装一个相册的图片"，换相册扫描时不能把两边的图混在一起。
     *
     * 注意：这一步**只读文件信息**（尺寸、大小、文件时间），
     * 不会认字、不会看图片内容（那是阶段 2、5 的事）。
     * 所以它很快，3000 张也就几秒。
     */
    suspend fun scanAlbum(
        album: MediaStoreReader.AlbumInfo,
        onProgress: (ScanProgress) -> Unit,
    ) {
        onProgress(ScanProgress(isScanning = true, scanned = 0, message = "正在清掉上个相册的记录…"))

        // 先清掉别的相册，保证目录里只有当前这一个相册
        dao.deleteOtherAlbums(album.bucketName)

        onProgress(ScanProgress(isScanning = true, scanned = 0, message = "正在读取相册…"))

        val photos = MediaStoreReader.readAlbum(
            context = context,
            bucketId = album.bucketId,
            onProgress = { scanned ->
                onProgress(
                    ScanProgress(
                        isScanning = true,
                        scanned = scanned,
                        message = "已读取 $scanned 张…",
                    )
                )
            },
        )

        onProgress(
            ScanProgress(
                isScanning = true,
                scanned = photos.size,
                message = "正在保存到本地目录…",
            )
        )

        // 分批存，避免一次塞太多
        photos.chunked(500).forEach { dao.upsertAll(it) }

        // 把以前识别过的结果填回来，这样换回旧相册时不用重新识别
        restoreOcrFromCache(photos.map { it.uri })

        onProgress(
            ScanProgress(
                isScanning = false,
                scanned = photos.size,
                message = "完成，共 ${photos.size} 张",
            )
        )
    }

    /** 目录里现在装着的是哪个相册（换相册前要提醒用户会被清掉） */
    fun currentAlbumName(): Flow<String?> = dao.currentAlbumFlow()

    /**
     * 识别自检：拿前几张照片试一下，把每一步的真实结果报出来。
     *
     * 用途：万一"认不出文字"，用它判断问题出在哪一步
     *（图片没读到？尺寸为 0？识别器返回空？）。
     */
    suspend fun ocrSelfTest(sampleCount: Int = 3): String {
        val samples = dao.firstPhotos(sampleCount)
        if (samples.isEmpty()) return "目录里没有照片，先扫描相册。"

        val lines = StringBuilder()
        samples.forEachIndexed { i, photo ->
            lines.appendLine("【第 ${i + 1} 张】${photo.name}")
            lines.appendLine("  记录尺寸: ${photo.width}×${photo.height}")
            lines.appendLine("  " + TextRecognizer.selfTest(context, photo.uri))
            lines.appendLine()
        }
        return lines.toString()
    }

    /** 把某个相册的记录从目录里清掉 */
    suspend fun removeAlbum(albumName: String) = dao.deleteOtherAlbums(albumName)

    /**
     * 清空所有识别结果，准备重新识别一遍。
     * **不会动用户手动补的文字**。
     * 同时把长期缓存也清掉（否则重扫时又会被填回去）。
     */
    suspend fun resetOcr() {
        dao.resetAllOcr()
        cacheDao.clearAll()
    }

    /**
     * 把还没识别的照片标记成"跳过"（用户主动停止时用）。
     * 这样下次点「开始识别」不会又把剩下的从头跑一遍。
     */
    suspend fun skipRemainingOcr() = dao.markRemainingSkipped()

    // ---------------- 识别图里的文字（阶段 2） ----------------

    /** 还没识别的照片数量（含被取消跳过的） */
    fun ocrPendingCount(): Flow<Int> = dao.ocrPendingCountFlow()

    /** 被取消跳过的照片数量 */
    fun ocrSkippedCount(): Flow<Int> = dao.ocrSkippedCountFlow()

    /** 认出来有文字的照片数量 */
    fun ocrHitCount(): Flow<Int> = dao.ocrHitCountFlow()

    /** 认过但没认出文字的照片数量 */
    fun ocrMissCount(): Flow<Int> = dao.ocrMissCountFlow()

    /** 长期缓存里存了多少张的识别结果（换相册也不会丢） */
    fun ocrCacheCount(): Flow<Int> = cacheDao.countFlow()

    /** 按相册统计识别情况（弹窗里展示用） */
    fun albumOcrStats(): Flow<List<AlbumOcrStat>> = cacheDao.albumStatsFlow()

    /**
     * 把长期缓存里的识别结果填回照片记录。
     *
     * 用在**扫描完相册之后**：新扫进来的照片如果以前识别过，
     * 直接把结果写上，界面上就显示"已识别"，不用再跑一遍。
     */
    private suspend fun restoreOcrFromCache(uris: List<String>) {
        if (uris.isEmpty()) return
        uris.chunked(500).forEach { chunk ->
            val cached = cacheDao.getByUris(chunk)
            cached.forEach { c ->
                // 只填"从没识别过"的那些，不覆盖当前的结果
                dao.fillOcrIfNotRun(c.uri, c.ocrText, c.ocrState)
            }
        }
    }

    /**
     * 把目录里所有还没认过字的照片，挨个认一遍。
     *
     * 特点：
     *  - **完全在手机上跑，不联网、不上传、不花钱**
     *  - **可以中途暂停**（调用 [pauseOcr]），暂停期间不会占用手机资源
     *  - **跑到一半被打断也不怕**：每认完一张立刻存进数据库，
     *    下次再点"开始识别"会**接着没认过的继续**，不会从头再来
     *
     * @param onProgress 每认完一张回调一次，界面上显示进度
     * @return 一句给用户看的总结
     */
    suspend fun runOcr(onProgress: (OcrProgress) -> Unit): String {
        val pending = dao.photosToOcr()
        val total = pending.size
        if (total == 0) {
            onProgress(OcrProgress(done = 0, total = 0, withText = 0, finished = true))
            return "没有需要识别的照片。"
        }

        ocrPaused = false
        ocrCancelled = false
        ocrAbortQuietly = false
        var done = 0
        var withText = 0

        pending.forEach { photo ->
            // ---- 取消检查：用户点了"取消"就直接收工 ----
            if (ocrCancelled) {
                // ⚠️ "悄悄中止"（换相册）时**不要**标记跳过。
                //
                //    因为旧相册的照片马上就被删了，标了没意义；
                //    更糟的是如果这一步晚于新相册写入，
                //    会把**新相册的照片**全标成"跳过"，
                //    界面就会莫名显示"还有 N 张未识别（上次取消了）"。
                val quiet = ocrAbortQuietly
                if (!quiet) {
                    dao.markRemainingSkipped()
                }
                ocrCancelled = false
                ocrAbortQuietly = false
                return if (quiet) {
                    "识别已停止（相册已切换）。"
                } else {
                    "已取消识别。已经识别的结果都保留了，随时可以点「继续识别」。"
                }
            }

            // ---- 暂停检查：暂停时在这里原地等待，不占用 CPU ----
            while (ocrPaused) {
                onProgress(
                    OcrProgress(done = done, total = total, withText = withText, isPaused = true)
                )
                delay(300)
            }

            val text = runCatching {
                TextRecognizer.recognize(context, photo.uri)
            }.getOrNull()

            if (text != null) {
                dao.updateOcr(photo.id, text, OcrState.SUCCESS)
                withText++
            } else {
                // 认过但没文字（可能是纯图，也可能是认失败），标记一下不再重复认
                dao.updateOcr(photo.id, null, OcrState.NO_TEXT)
            }

            // 存进长期缓存 —— 这样换相册、重扫都不用重新识别
            cacheDao.put(
                OcrCacheEntity(
                    uri = photo.uri,
                    ocrText = text,
                    ocrState = if (text != null) OcrState.SUCCESS else OcrState.NO_TEXT,
                    bucketName = photo.bucketName ?: "",
                    cachedAt = System.currentTimeMillis(),
                )
            )

            done++
            onProgress(
                OcrProgress(
                    done = done,
                    total = total,
                    withText = withText,
                    isPaused = false,
                    finished = done == total,
                )
            )
        }

        val noText = total - withText
        // 弹窗标题已经写了"识别完成"，正文别再重复一遍，只给数字和一句提示。
        return buildString {
            append("共 $total 张　·　有文字 $withText 张　·　没文字 $noText 张")
            if (noText > 0) {
                append("\n\n没认出来的，可以点开图片手动补上。")
            }
        }
    }

    /** 请求暂停识别（当前这一张会认完，然后停在下一张之前） */
    fun pauseOcr() {
        ocrPaused = true
    }

    /** 继续识别 */
    fun resumeOcr() {
        ocrPaused = false
    }

    /**
     * 请求取消识别：跑到当前这张结束后就收工，
     * 并把还没处理的照片标记成"已跳过"（这样下次不会又跑一遍）。
     * **已经识别的结果全部保留**。
     */
    fun cancelOcr() {
        ocrCancelled = true
        ocrPaused = false
    }

    /**
     * 请求**悄悄中止**识别（换相册时用）。
     *
     * 和 [cancelOcr] 的区别：收尾时**不把剩下的标记成"跳过"**。
     * 因为旧相册的照片马上就要被删掉，标记它们没意义；
     * 而且万一这一步晚于新相册写入，会把**新相册的照片**全标成"跳过"。
     */
    fun abortOcrQuietly() {
        ocrAbortQuietly = true
        ocrCancelled = true
        ocrPaused = false
    }

    /** 现在是不是暂停中 */
    fun isOcrPaused(): Boolean = ocrPaused

    /**
     * 暂停/取消标志。
     * 用 @Volatile 是因为它会被不同线程读写（识别在后台线程，按钮在主线程）。
     */
    @Volatile
    private var ocrPaused = false

    @Volatile
    private var ocrCancelled = false

    /** 取消时是不是"悄悄中止"（换相册）—— 是的话不标记跳过 */
    @Volatile
    private var ocrAbortQuietly = false

    /** 目录里一共有多少张（会自动通知界面刷新） */
    fun photoCount(): Flow<Int> = dao.countFlow()

    /** 目录里的全部照片，按图片生成时间倒序（界面网格用） */
    fun allPhotos(): Flow<List<PhotoEntity>> = dao.allFlow()

    /** 目录里最早那张图的时间（用来给用户看"覆盖到哪一年了"） */
    fun oldestDate(): Flow<Long?> = dao.oldestDateFlow()

    /** 目录里最晚那张图的时间 */
    fun newestDate(): Flow<Long?> = dao.newestDateFlow()

    /** 按编号取若干张 */
    suspend fun photosByIds(ids: List<Long>) = dao.byIds(ids)

    /**
     * 保存用户补/改的文字。
     *
     * ⚠️ **空串要原样存下来，不能转成 null**（这里踩过 bug）：
     *    - `null` = 没动过 → 回退用机器识别的文字
     *    - `""`   = **用户明确删空了** → 这张图就是没字
     *
     * 如果把空串转成 null，用户就**永远删不掉**机器认错的文字 ——
     * 删完又变回机器文字了。
     */
    suspend fun saveUserText(photoId: Long, text: String) {
        dao.updateUserText(photoId, text.trim())
    }

    /** 恢复机器识别的文字（把人工补录清掉，回到"没动过"的状态） */
    suspend fun restoreMachineText(photoId: Long) {
        dao.updateUserText(photoId, null)
    }

    /** 清空本地目录，准备重扫 */
    suspend fun clearCatalog() = dao.clearAll()
}
