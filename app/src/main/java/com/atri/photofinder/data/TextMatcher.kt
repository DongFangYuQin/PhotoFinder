package com.atri.photofinder.data

import com.atri.photofinder.data.db.PhotoEntity

/**
 * 搜索时的**分词**与**匹配**。
 *
 * ## 分词规则（用户 2026-10 明确要求）
 *
 * **按空格把输入切开**，每一段就是一个"词"，**分开的词之间是并列的，全部都要满足**。
 *
 * | 输入 | 切出来的词 | 命中条件 |
 * |---|---|---|
 * | `ABC` | `["ABC"]` | 图里的文字要含 `ABC`（连着出现） |
 * | `AB C` | `["AB", "C"]` | 要同时含 `AB` **和** `C` |
 * | `A B C` | `["A", "B", "C"]` | 要同时含 `A`、`B`、`C` 三个字 |
 *
 * **词之间不要求相邻、也不要求顺序** —— 只要图里的文字**分别能找到**每个词就算命中。
 *
 * ## 不做排序（用户要求）
 *
 * 用户原话：
 * > 分词之后搜索不需要排序，只要图片内文字有匹配到的单字，就算搜索到
 *
 * 所以命中的图片按**时间倒序**（新的在前）返回，不做相似度打分。
 * 之前那套"完整匹配 100 分 / 部分匹配 40 分"的打分逻辑**已经废弃**。
 *
 * ## 不匹配文件名（用户要求）
 *
 * 只比对**图中识别出的文字**和**用户手动补的文字**。
 * 理由：没人会去记手机相册里那串 `IMG_20240101_120000`。
 */
object TextMatcher {

    /**
     * 把输入按空格切成一个个词。
     *
     * 空格支持：半角空格、全角空格、制表符、换行，以及中英文逗号
     * （用户可能顺手打逗号当分隔符）。
     *
     * 每个词都做标准化（去空白 + 转小写），空词丢掉。
     */
    fun splitTerms(input: String): List<String> =
        input.split(' ', '　', '\t', '\n', '\r', ',', '，')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    /**
     * 从这批照片里挑出**符合所有词**的那些。
     *
     * @param photos 候选照片（已经按"确定条件"筛过的）
     * @param input  用户输入的关键词（可以带空格）
     * @return 命中的照片，**按时间倒序**（新的在前）
     */
    fun filter(photos: List<PhotoEntity>, input: String): List<PhotoEntity> {
        val terms = splitTerms(input)

        // 没输入关键词 → 全部返回（调用方一般是"只用条件筛选"）
        if (terms.isEmpty()) return photos

        return photos
            .filter { photo -> matchesAll(photo, terms) }
            .sortedByDescending { it.dateMillis }
    }

    /** 这张图的文字是不是**包含全部**词 */
    private fun matchesAll(photo: PhotoEntity, terms: List<String>): Boolean {
        // ⚠️ 用 effectiveText（用户补录的优先），不是把两份文字合起来比。
        //
        //   为什么：用户手动改文字的场景就是"机器认错了"。
        //   如果两份都参与匹配，错的那些字依然能被搜出来，
        //   用户会觉得"我明明改了怎么还这样"。
        val haystack = normalize(photo.effectiveText)
        if (haystack.isEmpty()) return false

        // 每个词都要能在里面找到（顺序、位置都不限）
        return terms.all { haystack.contains(it) }
    }

    /**
     * 标准化：**去掉所有空白** + **转小写**。
     *
     * 去空白是为了让"发 票"也能匹配到图里的"发票"
     * （图里识别出来的文字可能带奇怪的换行/空格）。
     * 转小写是为了英文大小写不敏感。
     */
    fun normalize(raw: String): String =
        raw.filterNot { it.isWhitespace() }.lowercase()
}
