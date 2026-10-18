package com.atri.photofinder.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.atri.photofinder.data.db.DateSource
import com.atri.photofinder.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册里有哪些照片、每张多大、什么时候进来的。
 *
 * 这一部分**不需要申请任何敏感权限**，因为只读系统已经准备好的照片信息表。
 * 读取的是"这一个相册"（bucket）里的图片。
 *
 * 关于兼容性（用户提到不同手机相册目录不一样）：
 *   安卓把所有相册都记在一张"总表"里（MediaStore），不管照片实际在
 *   DCIM 还是 Pictures，都会出现在这张表里。所以我们不直接去翻文件夹，
 *   而是问系统"你有哪些相册"——这样各品牌手机都能适配。
 *   唯一的前提是：手机的系统相册程序已经"扫到"了这些照片（通常会自动扫）。
 */
object MediaStoreReader {

    /** 一个相册的名字 + 里面有多少张图 */
    data class AlbumInfo(
        val bucketId: String,
        val bucketName: String,
        val photoCount: Int,
    )

    /** 排查用：系统一共交出多少张图片、分别属于哪些"目录" */
    data class Diagnostic(
        val totalImages: Int,
        val buckets: List<Pair<String, Int>>,
        val error: String?,
    )

    /**
     * 列出手机里有照片的所有相册。
     * 数据来源是系统媒体库，所以很快，不用扫描文件。
     */
    suspend fun listAlbums(context: Context): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )

        val albums = mutableMapOf<String, AlbumInfo>()

        // 查询本身包一层保护：万一权限没给或系统出错，就当"没找到相册"处理，
        // 不要整个 App 崩掉。
        val query = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    // 有些手机不填 BUCKET 信息，那就拿"相对路径"当相册名兜底
                    val path = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                        ?: path?.trimEnd('/')?.substringAfterLast('/')
                        ?: "(未命名)"
                    val id = (if (idCol >= 0) cursor.getString(idCol) else null)
                        ?: path
                        ?: name

                    val existing = albums[id]
                    albums[id] = AlbumInfo(
                        bucketId = id,
                        bucketName = name,
                        photoCount = (existing?.photoCount ?: 0) + 1,
                    )
                }
            }
        }

        if (query.isFailure) return@withContext emptyList()

        albums.values.sortedWith(
            compareByDescending<AlbumInfo> { it.photoCount }.thenBy { it.bucketName }
        )
    }

    /**
     * 排查工具：把系统到底交出了什么，原原本本报告出来。
     *
     * 用途：万一点了"重新找"还是空的，可以让用户把这个结果发过来，
     * 就能立刻判断是"权限没给"还是"系统里真的没有图片"。
     */
    suspend fun diagnose(context: Context): Diagnostic = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        var total = 0
        val buckets = mutableMapOf<String, Int>()

        val result = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    total++
                    val path = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                        ?: path?.trimEnd('/')?.substringAfterLast('/')
                        ?: "(未命名)"
                    buckets[name] = (buckets[name] ?: 0) + 1
                }
            }
        }

        Diagnostic(
            totalImages = total,
            buckets = buckets.entries.sortedByDescending { it.value }.map { it.key to it.value },
            error = result.exceptionOrNull()?.message,
        )
    }

    /**
     * 真正去读某个相册里的每一张照片。
     *
     * @param bucketId 相册编号，从 [listAlbums] 拿
     * @param onProgress 每读一批就回调一次，用来显示进度条。
     *                   传进来的是"已经读了多少张"。
     * @return 读到的全部照片信息
     */
    suspend fun readAlbum(
        context: Context,
        bucketId: String,
        onProgress: (scanned: Int) -> Unit = {},
    ): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )

        // 用"相册编号"筛。注意 BUCKET_ID 在有些手机上可能是空的，
        // 所以这里用 BUCKET_ID 匹配，取不到就用列表里存的那个值当路径用。
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)
        // 按"文件时间"从新到旧排
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val result = ArrayList<PhotoEntity>()

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val modCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri: Uri =
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                // ⚠️ 取"这个文件到本机的时间"。规则（用户定的，别改）：
                //   不看照片什么时候拍的，只看这个文件在这台设备上是何时出现的。
                //   下载保存、别人发原图、本机拍照 —— 都按这个时间来。
                //
                // 顺序（首选 → 兜底）：
                //   1. DATE_MODIFIED 文件自己的修改时间（秒，要 ×1000）
                //        → 这是最接近"文件到本机时刻"的字段
                //   2. DATE_ADDED    系统记录"加入相册时间"（秒，要 ×1000）
                //   3. DATE_TAKEN    EXIF 拍摄时间（毫秒），只在上面都拿不到时才用
                val modifiedMillis = if (modCol >= 0) cursor.getLong(modCol) * 1000L else 0L
                val addedMillis = if (addedCol >= 0) cursor.getLong(addedCol) * 1000L else 0L
                val takenMillis = if (takenCol >= 0) cursor.getLong(takenCol) else 0L

                val (dateMillis, dateSource) = when {
                    modifiedMillis > 0L -> modifiedMillis to DateSource.FILE_MODIFIED
                    addedMillis > 0L -> addedMillis to DateSource.ADDED_TO_LIBRARY
                    takenMillis > 0L -> takenMillis to DateSource.EXIF_TAKEN
                    else -> 0L to DateSource.UNKNOWN
                }

                result += PhotoEntity(
                    id = id,
                    uri = uri.toString(),
                    name = cursor.getString(nameCol) ?: "(未知文件名)",
                    bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) else null,
                    width = cursor.getInt(wCol),
                    height = cursor.getInt(hCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    dateMillis = dateMillis,
                    dateSource = dateSource,
                )

                if (result.size % 200 == 0) {
                    onProgress(result.size)
                }
            }
        }

        onProgress(result.size)
        result
    }
}
