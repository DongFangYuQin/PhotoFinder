package com.atri.photofinder.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 识别结果的**长期缓存**。
 *
 * ## 为什么需要单独存一份
 *
 * 用户要求"目录里一次只装一个相册"，所以**换相册时旧相册的记录会被删掉**。
 * 如果识别结果只存在照片记录上，那换回旧相册就得**重新识别一遍** ——
 * 而识别是最费时间的操作，白做太浪费。
 *
 * 所以把识别结果按**图片的地址**单独缓存起来：
 *  - 换相册、重扫相册都不影响它
 *  - 重扫时自动把缓存**填回**照片记录里，用户看到的就是"已经识别过了"
 *  - 只对"从没见过"的图片做识别
 *
 * ## 为什么用地址当主键
 *
 * 图片在系统里的地址（`content://media/external/images/media/12345`）在
 * 图片没被删除的情况下是**稳定**的，换个相册重扫也是同一个地址。
 */
@Entity(tableName = "ocr_cache")
data class OcrCacheEntity(

    /** 图片地址，同时作为主键 */
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,

    /** 识别出来的文字；null 表示识别过但没文字 */
    @ColumnInfo(name = "ocr_text")
    val ocrText: String?,

    /** 识别状态，含义见 [OcrState] */
    @ColumnInfo(name = "ocr_state")
    val ocrState: Int,

    /** 这张图属于哪个相册（记下来才能在界面上按相册统计） */
    @ColumnInfo(name = "bucket_name", defaultValue = "")
    val bucketName: String = "",

    /** 缓存时间（毫秒） */
    @ColumnInfo(name = "cached_at")
    val cachedAt: Long,
)

/** 按相册汇总的识别情况（界面弹窗用） */
data class AlbumOcrStat(
    /** 相册名 */
    val bucketName: String,
    /** 这个相册里识别出有文字的图片数 */
    val recognized: Int,
    /** 这个相册里识别过但没有文字的图片数 */
    val noText: Int,
) {
    /** 识别过的总数 */
    val total: Int get() = recognized + noText
}

/**
 * 识别缓存的读写。
 */
@Dao
interface OcrCacheDao {

    /** 存一条识别结果（已存在就覆盖） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: OcrCacheEntity)

    /** 批量存 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<OcrCacheEntity>)

    /** 查一张图有没有缓存过 */
    @Query("SELECT * FROM ocr_cache WHERE uri = :uri")
    suspend fun get(uri: String): OcrCacheEntity?

    /** 批量查（重扫相册后把结果填回照片记录时用） */
    @Query("SELECT * FROM ocr_cache WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<OcrCacheEntity>

    /** 缓存了多少条 */
    @Query("SELECT COUNT(*) FROM ocr_cache")
    fun countFlow(): Flow<Int>

    /**
     * 按相册统计识别情况。
     *
     * 「有文字」= ocr_state 1；「没认出文字」= ocr_state 2（跳过的不算，那不是识别结果）。
     */
    @Query(
        """
        SELECT bucket_name AS bucketName,
               SUM(CASE WHEN ocr_state = 1 THEN 1 ELSE 0 END) AS recognized,
               SUM(CASE WHEN ocr_state = 2 THEN 1 ELSE 0 END) AS noText
        FROM ocr_cache
        WHERE bucket_name != ''
        GROUP BY bucket_name
        ORDER BY COUNT(*) DESC
        """
    )
    fun albumStatsFlow(): Flow<List<AlbumOcrStat>>

    /** 全部清掉（用户点"重新识别"时用） */
    @Query("DELETE FROM ocr_cache")
    suspend fun clearAll()
}
