package com.atri.photofinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 对"照片目录"这张表的读写操作。
 *
 * 说明：阶段 1 先只做"存进去"和"读出来"，
 * 真正的搜索逻辑在阶段 3 写（那时候会加更聪明的匹配）。
 */
@Dao
interface PhotoDao {

    /** 把扫到的照片存进去；已经有的就覆盖更新。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<PhotoEntity>)

    /** 目录里一共有多少张 */
    @Query("SELECT COUNT(*) FROM photos")
    fun countFlow(): Flow<Int>

    /** 一共多少张（一次性取值） */
    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    /** 列出全部照片，新拍/新做出来的排前面 */
    @Query("SELECT * FROM photos ORDER BY date_millis DESC")
    fun allFlow(): Flow<List<PhotoEntity>>

    /** 目录里最早那张图的时间（用来显示"覆盖范围"） */
    @Query("SELECT MIN(date_millis) FROM photos")
    fun oldestDateFlow(): Flow<Long?>

    /** 目录里最晚那张图的时间 */
    @Query("SELECT MAX(date_millis) FROM photos")
    fun newestDateFlow(): Flow<Long?>

    /** 按编号取若干张（搜索结果要靠它把详情取出来） */
    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PhotoEntity>

    /**
     * 删除目录里已经不在相册中的记录（照片被你删了就同步掉）
     */
    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 清空整个目录（重新扫描前用） */
    @Query("DELETE FROM photos")
    suspend fun clearAll()

    /**
     * 只保留某个相册的照片，把别的相册的记录删掉。
     *
     * 用途：用户换一个相册扫描时，目录里不应该堆着上一个相册的图。
     * 按用户要求：**一次只装一个相册的东西**。
     *
     * 注意：`bucket_name IS NULL` 要单独处理 —— SQL 里 NULL 不能用 `!=` 比较。
     */
    @Query("DELETE FROM photos WHERE bucket_name IS NULL OR bucket_name != :bucketName")
    suspend fun deleteOtherAlbums(bucketName: String)

    /** 目录里现在装着的是哪个相册 */
    @Query("SELECT bucket_name FROM photos LIMIT 1")
    fun currentAlbumFlow(): Flow<String?>

    /** 找出还没认过字的照片，进度条要用 */
    @Query("SELECT * FROM photos WHERE ocr_state = 0 LIMIT :limit")
    suspend fun photosNeedingOcr(limit: Int): List<PhotoEntity>
    /**
     * "还没真正识别过"的照片数量。
     *
     * ⚠️ 这里包含两类：
     *   ocr_state = 0  从没识别过
     *   ocr_state = 3  用户取消时被"跳过"的
     *
     * 为什么把"跳过"也算进来：
     *   取消识别之后，如果只统计状态 0，待识别数会变成 0，
     *   界面就会显示"已全部识别"、按钮消失，用户只能点"重新识别"（重跑全部）。
     *   把跳过的算进去，界面才能正确地提示"还有 N 张没识别"、并给出「继续识别」。
     */
    @Query("SELECT COUNT(*) FROM photos WHERE ocr_state = 0 OR ocr_state = 3")
    fun ocrPendingCountFlow(): Flow<Int>

    /** 只统计"从没识别过"的（用来判断是不是首次识别） */
    @Query("SELECT COUNT(*) FROM photos WHERE ocr_state = 0")
    suspend fun freshPendingCount(): Int

    /** 被用户取消、跳过的照片数量 */
    @Query("SELECT COUNT(*) FROM photos WHERE ocr_state = 3")
    fun ocrSkippedCountFlow(): Flow<Int>

    /** 取出待识别的照片：从没识别的 + 被跳过的 */
    @Query("SELECT * FROM photos WHERE ocr_state = 0 OR ocr_state = 3")
    suspend fun photosToOcr(): List<PhotoEntity>

    /** 取前几张照片，做识别自检用 */
    @Query("SELECT * FROM photos ORDER BY date_millis DESC LIMIT :limit")
    suspend fun firstPhotos(limit: Int): List<PhotoEntity>

    /**
     * 把所有照片的认字状态清空，准备重新认一遍。
     *
     * 用途：识别设置改了（比如识别精度提高）之后，旧结果就不准了，要重跑。
     * ⚠️ 只会清掉"机器认出来的文字"，用户手动补的文字（user_text）不动。
     */
    @Query("UPDATE photos SET ocr_state = 0, ocr_text = NULL")
    suspend fun resetAllOcr()

    /**
     * 把还没识别的照片标记成"已跳过"（状态 3）。
     * 用户主动停止识别时用，这样下次不会又把这些跑一遍。
     */
    @Query("UPDATE photos SET ocr_state = 3 WHERE ocr_state = 0")
    suspend fun markRemainingSkipped()

    /** 认出来有文字的照片数量 */
    @Query("SELECT COUNT(*) FROM photos WHERE ocr_state = 1")
    fun ocrHitCountFlow(): Flow<Int>

    /** 认过、但没认出文字的照片数量 */
    @Query("SELECT COUNT(*) FROM photos WHERE ocr_state = 2")
    fun ocrMissCountFlow(): Flow<Int>

    /** 把认字结果写回去 */
    @Query("UPDATE photos SET ocr_text = :text, ocr_state = :state WHERE id = :id")
    suspend fun updateOcr(id: Long, text: String?, state: Int)

    /**
     * 用识别缓存填回结果（按图片地址匹配）。
     * **只填从没识别过的那些**（`ocr_state = 0`），不覆盖已有结果。
     */
    @Query(
        """
        UPDATE photos SET ocr_text = :text, ocr_state = :state
        WHERE uri = :uri AND ocr_state = 0
        """
    )
    suspend fun fillOcrIfNotRun(uri: String, text: String?, state: Int)

    /** 把用户手动补的文字写回去 */
    @Query("UPDATE photos SET user_text = :text WHERE id = :id")
    suspend fun updateUserText(id: Long, text: String?)

    /** 把 AI 写的描述写回去（阶段 5 用） */
    @Query("UPDATE photos SET ai_description = :description, ai_state = :state WHERE id = :id")
    suspend fun updateAiDescription(id: Long, description: String?, state: Int)
}
