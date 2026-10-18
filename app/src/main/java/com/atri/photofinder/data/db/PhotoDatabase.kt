package com.atri.photofinder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 手机本地的数据库。
 *
 * 说明：
 *  - 数据只存在你手机里，不上传任何地方。
 *  - version 变化说明：
 *      1 → 最初的版本，时间是"加入相册的时间"
 *      2 → 改成"图片自己记录的拍摄时间"（EXIF）
 *      3 → 改成"文件到本机的时间"（用户最终确定的标准：不看拍摄时间）
 *      4 → 新增 ocr_cache 表：识别结果单独长期缓存，换相册不用重新识别
 *      5 → ocr_cache 增加"相册名"字段，可以在界面上按相册统计识别情况
 *    版本不一致时会把旧数据清掉重建，所以升级后需要重新扫一遍相册。
 *    （识别缓存也会一起清掉，需要重新识别一次）
 */
@Database(
    entities = [PhotoEntity::class, OcrCacheEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class PhotoDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    abstract fun ocrCacheDao(): OcrCacheDao

    companion object {
        private const val DB_NAME = "photofinder.db"

        @Volatile
        private var instance: PhotoDatabase? = null

        /** 拿到数据库（整个 App 共用一个，避免重复打开） */
        fun get(context: Context): PhotoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoDatabase::class.java,
                    DB_NAME,
                )
                    // 新手友好：万一结构对不上，直接重建，不让 App 崩
                    // （代价是目录要重新扫一遍）
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
