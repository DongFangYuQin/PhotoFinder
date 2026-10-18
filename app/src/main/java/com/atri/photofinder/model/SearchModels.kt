package com.atri.photofinder.model

/**
 * 清晰度档位。
 *
 * 判定标准按图片"长边"（宽和高里更大的那个）来算。
 *
 * ⚠️ **这个现在只用来"显示"了**（在缩略图和文件信息里标一下清晰度），
 *    **不再是筛选条件** —— 用户 2026-10 要求把"清晰度"筛选换成 [FileKind]（文件格式）。
 *    所以搜索和浏览页的筛选里已经没有清晰度了。
 */
enum class ResolutionTier(val label: String) {
    LOW("低清"),
    MEDIUM("中等"),
    HIGH("高清");

    companion object {
        /** 低清的上限：长边小于这个数算低清 */
        const val MEDIUM_MIN = 1000

        /** 高清的下限：长边大于等于这个数算高清 */
        const val HIGH_MIN = 2000

        /** 根据长边像素判断档位 */
        fun fromLongSide(longSide: Int): ResolutionTier = when {
            longSide < MEDIUM_MIN -> LOW
            longSide < HIGH_MIN -> MEDIUM
            else -> HIGH
        }
    }
}

/**
 * 文件格式分类（用户 2026-10 要求，用来替代原来的"清晰度"筛选）。
 *
 * 只分两类，简单直接：
 *  - **GIF**：会动的图
 *  - **普通图片**：JPG / PNG / WebP / BMP 等静态图片
 *
 * 为什么只有两类就够：用户明确说"一种是 GIF，一种是图片格式"。
 * 分太细（JPG/PNG/WebP…）对"找图"这件事没什么帮助。
 */
enum class FileKind(val label: String) {
    /** 会动的图 */
    GIF("GIF"),

    /** 普通静态图片（JPG / PNG / WebP / BMP…） */
    IMAGE("图片");

    companion object {
        /**
         * 按文件名猜分类。
         *
         * 只看扩展名，够用了 —— 相册里的文件名都是系统给的，扩展名可靠。
         */
        fun fromName(name: String): FileKind =
            if (name.substringAfterLast('.', "").equals("gif", ignoreCase = true)) GIF else IMAGE

        /** 界面上"不限"时用的全部选项 */
        val ALL = listOf(GIF, IMAGE)
    }
}

// 说明：早期这里还放着 SearchQuery / TimeRange / SearchHit 三个类
// （搜索条件、时间范围、带分数的搜索结果）。
// 后来搜索改成"按空格分词 + 不排序"（见 data/TextMatcher.kt），
// 打分和 SearchHit 都不需要了，所以删掉了。
