package com.atri.photofinder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.atri.photofinder.model.FileKind
import com.atri.photofinder.model.ResolutionTier

/**
 * 照片目录里的一条记录。
 *
 * 注意：这里存的**不是照片本身**，只是关于这张照片的信息。
 * 照片一直在你手机相册的原处，App 不复制、不移动、不删除它。
 */
@Entity(tableName = "photos")
data class PhotoEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    /** 照片的位置标识。靠它取缩略图、打开原图。 */
    @ColumnInfo(name = "uri")
    val uri: String,

    /** 文件名，比如 IMG_20240101_123456.jpg */
    @ColumnInfo(name = "name")
    val name: String,

    /** 所在相册的名字（就是相册文件夹名） */
    @ColumnInfo(name = "bucket_name")
    val bucketName: String?,

    /** 宽（像素） */
    @ColumnInfo(name = "width")
    val width: Int,

    /** 高（像素） */
    @ColumnInfo(name = "height")
    val height: Int,

    /** 文件大小（字节） */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    /**
     * 这张照片的**文件创建时间**（毫秒时间戳）。
     *
     * 规则（用户 2026-10 明确要求，别再改回去）：
     *   不看这张照片"什么时候拍的"，只看**这个文件在这台设备上是何时创建的**。
     *   不管它是下载来的、别人用社交软件原图发来的、还是本机拍的，
     *   只要文件到了这台设备，就按文件自己的这个时间来算。
     *
     * 例子：别人 2024 年拍的照片，2025 年用社交软件发原图给我，
     *       我保存后这张图的文件时间就是 2025 年 —— 那就显示 2025 年。
     *
     * ⚠️ 注意别再用 EXIF 的"拍摄时间"当主标准了：
     *   用它的话，2021 年拍的老照片搬家后还是显示 2021，
     *   和用户要的"文件到本机的时间"不符。
     *   EXIF 拍摄时间只作为最后兜底。
     *
     * 具体从哪取来的见 [dateSource]。
     */
    @ColumnInfo(name = "date_millis")
    val dateMillis: Long,

    /**
     * 上面那个时间是从哪取的，含义见 [DateSource]。
     * 用它来决定界面上要不要加个"约"字。
     */
    @ColumnInfo(name = "date_source")
    val dateSource: Int = DateSource.UNKNOWN,

    // ---------- 以下是后面阶段才会填的字段 ----------

    /** 机器认出来的图中文字；null 表示还没认过 */
    @ColumnInfo(name = "ocr_text")
    val ocrText: String? = null,

    /**
     * 认字的状态：
     *   0 = 还没认过
     *   1 = 认出来了（文字存在 ocrText）
     *   2 = 认过，但图里确实没有文字
     *   3 = 认失败了（图太大、损坏等）
     */
    @ColumnInfo(name = "ocr_state")
    val ocrState: Int = OcrState.NOT_RUN,

    /** 用户手动补的文字（认错了或者没认出来时自己填的），最可信 */
    @ColumnInfo(name = "user_text")
    val userText: String? = null,

    /** AI 给这张图写的一句话描述（"预扫全部"模式用），null 表示还没看 */
    @ColumnInfo(name = "ai_description")
    val aiDescription: String? = null,

    /** AI 处理状态：0 = 没处理，1 = 处理好了，2 = 失败 */
    @ColumnInfo(name = "ai_state")
    val aiState: Int = 0,
) {

    /** 长边像素 */
    val longSide: Int get() = maxOf(width, height)

    /** 短边像素 */
    val shortSide: Int get() = minOf(width, height)

    /**
     * 属于哪个清晰度档位。
     *
     * ⚠️ 现在**只用来显示**（缩略图和文件信息里标一下），
     *    **不再是筛选条件** —— 用户要求把"清晰度"筛选换成了 [FileKind]。
     */
    val resolutionTier: ResolutionTier get() = ResolutionTier.fromLongSide(longSide)

    /** 文件格式分类（GIF / 普通图片）。筛选条件用这个。 */
    val fileKind: FileKind get() = FileKind.fromName(name)

    /** 文件名去掉后缀的部分，搜索时也能用来匹配 */
    val nameWithoutExtension: String
        get() = name.substringBeforeLast('.', name)

    /** 这个时间是不是"不准/退而求其次"的（不是文件自己的修改时间时） */
    val isDateApproximate: Boolean get() = dateSource != DateSource.FILE_MODIFIED

    /**
     * 搜索时实际拿来比对的文字。
     *
     * ⚠️ 这个属性**包含文件名**，但那只是为了别的地方（比如显示）方便。
     *    **搜索不用它** —— 用户明确要求搜索时排除文件名：
     *    没人会去记手机相册里那串 `IMG_20240101_120000`。
     *    搜索走 [com.atri.photofinder.data.TextMatcher]，那里只比对 [userText] 和 [ocrText]。
     */
    val searchableText: String
        get() = listOfNotNull(userText, ocrText, nameWithoutExtension)
            .filter { it.isNotBlank() }
            .joinToString(" ")

    /**
     * 这张图**真正被认出来或人工补录的文字**（**不含文件名**）。
     *
     * 用途：界面上打"有文字"的标记、做"有字/无字"筛选。
     * ⚠️ 千万不要让文件名参与判断 —— 那会让每张图都被认为有文字。
     */
    val hasRecognizedText: Boolean
        get() = effectiveText.isNotBlank()

    /**
     * **最终生效的文字**。
     *
     * [userText] 有**三种状态**，必须分清（这里踩过 bug）：
     *
     * | userText | 含义 | 生效的文字 |
     * |---|---|---|
     * | `null` | **没动过** —— 用机器识别的 | [ocrText] |
     * | `""`（空串） | **用户明确删空了** —— 这张图就是没字 | **空**（不再回退） |
     * | 有内容 | 用户补/改过 | 用户写的 |
     *
     * ⚠️ 之前把"空串"当成"没动过"，导致**用户想把机器认错的文字删干净时，
     *    删完又变回机器文字**，根本删不掉。所以必须区分 null 和 ""。
     *
     * 想恢复机器文字：在编辑界面点「恢复机器识别」（会把 userText 设回 null）。
     */
    val effectiveText: String
        get() = if (userText != null) userText else ocrText.orEmpty()

    /** 用户有没有动过文字（改过、或者明确删空，都算动过） */
    val hasUserText: Boolean get() = userText != null

    /** 用户是不是**明确把文字删空了** */
    val isTextClearedByUser: Boolean get() = userText != null && userText.isEmpty()

    /**
     * 打开"补充/修正文字"界面时，输入框里该**预填**的内容。
     *
     *  - 用户动过 → 显示他当前的内容（改成空的话就是空）
     *  - 没动过但有机器识别结果 → 显示机器识别的（方便他直接在上面改）
     *  - 都没有 → 空白
     */
    val textForEditing: String
        get() = effectiveText

    /** 真正认出来/补录的文字内容（不含文件名），用来显示给用户看 */
    val recognizedText: String
        get() = effectiveText

    /** 这张图的文字是不是还没认过 */
    val needsOcr: Boolean get() = ocrState == OcrState.NOT_RUN
}

/**
 * 照片的时间是从哪儿取来的，按可信度从高到低。
 *
 * 用户定的标准是"文件到本机的时间"，所以 [FILE_MODIFIED] 是第一优先。
 */
object DateSource {
    /** 不知道（理论上不该出现） */
    const val UNKNOWN = 0

    /**
     * 文件自己的创建/修改时间（**首选**）。
     * 含义：这个文件是何时出现在这台设备上的。
     * 下载保存、别人发原图、本机拍照，都会让这个时间等于"到本机的时刻"。
     */
    const val FILE_MODIFIED = 1

    /** 图片 EXIF 里记录的拍摄时间（兜底用；会保留"拍摄地/拍摄时"的老时间） */
    const val EXIF_TAKEN = 2

    /** 系统记录的"加入相册时间"（最后的兜底） */
    const val ADDED_TO_LIBRARY = 3
}

/** 认字状态的具体数字，避免代码里到处写 0/1/2/3 看不懂 */
object OcrState {
    const val NOT_RUN = 0
    const val SUCCESS = 1
    const val NO_TEXT = 2
    const val FAILED = 3
}

/** AI 处理状态 */
object AiState {
    const val NOT_RUN = 0
    const val DONE = 1
    const val FAILED = 2
}
