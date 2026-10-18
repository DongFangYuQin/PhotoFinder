package com.atri.photofinder.ui

import android.app.Application
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.atri.photofinder.data.PhotoRepository
import com.atri.photofinder.data.TextMatcher
import com.atri.photofinder.data.ai.AiImageSearch
import com.atri.photofinder.data.db.AlbumOcrStat
import com.atri.photofinder.data.db.PhotoDatabase
import com.atri.photofinder.data.db.PhotoEntity
import com.atri.photofinder.data.media.MediaStoreReader
import com.atri.photofinder.data.settings.AiProviderConfig
import com.atri.photofinder.data.settings.AppSettings
import com.atri.photofinder.data.settings.SettingsStore
import com.atri.photofinder.model.FileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 取出某个时间戳的年份（0 表示时间无效） */
private fun yearOf(millis: Long): Int {
    if (millis <= 0L) return 0
    return java.util.Calendar.getInstance().apply { timeInMillis = millis }.get(java.util.Calendar.YEAR)
}

/**
 * 界面代数。用枚举而不是多个布尔值，避免出现"两个页面同时显示"的矛盾状态，
 * 也让"返回上一页"的逻辑变得简单明确。
 */
enum class Screen {
    /** 选择相册（App 启动页） */
    ALBUM,

    /** 识别分析（扫描完成后自动进入） */
    ANALYZE,

    /** 浏览图片 */
    PHOTOS,

    /** 搜索图片 */
    SEARCH,

    /** 按图片内容搜索（云端 AI，会联网） */
    AI,

    /**
     * AI 搜索的结果页（独立界面，专门用来完整看结果）。
     *
     * 为什么单独开一页：
     *   AI 搜索页上面有一大堆输入框和按钮，留给缩略图的只剩屏幕底下很窄一条，
     *   **根本看不清**。所以改成"AI 搜索页放一个按钮 → 点进来看完整的网格"。
     */
    AI_RESULTS,

    /** 设置（填 AI 密钥等） */
    SETTINGS,
}

/**
 * 界面的"大脑"。
 *
 * 界面自己不干活，只负责显示；要读相册、要扫描，都通过它。
 *
 * `@OptIn(FlowPreview::class)` 是因为用了 `debounce`（输入防抖，让搜索不要每敲一个字都算一遍），
 * 它被标记为"预览特性"但已经非常稳定，日常项目都在用。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class PhotoViewModel(app: Application) : AndroidViewModel(app) {

    private val database = PhotoDatabase.get(app.applicationContext)

    private val repository = PhotoRepository(
        context = app.applicationContext,
        dao = database.photoDao(),
        cacheDao = database.ocrCacheDao(),
    )

    /** 用户设置（AI 密钥、服务商等）。密钥只存在这台手机上。 */
    private val settingsStore = SettingsStore(app.applicationContext)

    // ---------------- AI 设置（阶段 5） ----------------

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun saveApiKey(key: String) {
        viewModelScope.launch { settingsStore.setApiKey(key) }
    }

    fun saveProvider(provider: AiProviderConfig) {
        viewModelScope.launch { settingsStore.setProvider(provider) }
    }

    fun saveCustomEndpoint(baseUrl: String, modelName: String) {
        viewModelScope.launch { settingsStore.setCustomEndpoint(baseUrl, modelName) }
    }

    fun saveMaxCandidates(n: Int) {
        viewModelScope.launch { settingsStore.setMaxCandidates(n) }
    }

    /**
     * 存"全部"这个**意图**（不是当时的数字）。
     *
     * ⚠️ 这是修 bug 的关键：如果存当时的数字（比如 300），
     *    后来候选涨到 350，"全部"就不再是全部了。
     */
    fun saveMaxCandidatesAll() {
        viewModelScope.launch { settingsStore.setMaxCandidatesAll() }
    }

    /** 相册列表 */
    private val _albums = MutableStateFlow<List<MediaStoreReader.AlbumInfo>>(emptyList())
    val albums: StateFlow<List<MediaStoreReader.AlbumInfo>> = _albums.asStateFlow()

    /** 用户选的相册 */
    private val _selectedAlbum = MutableStateFlow<MediaStoreReader.AlbumInfo?>(null)
    val selectedAlbum: StateFlow<MediaStoreReader.AlbumInfo?> = _selectedAlbum.asStateFlow()

    /** 扫描进度 */
    private val _scan = MutableStateFlow(PhotoRepository.ScanProgress())
    val scan: StateFlow<PhotoRepository.ScanProgress> = _scan.asStateFlow()

    /** 正在列相册 */
    private val _loadingAlbums = MutableStateFlow(false)
    val loadingAlbums: StateFlow<Boolean> = _loadingAlbums.asStateFlow()

    /** 出错信息（显示一次就清掉） */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 排查结果（"为什么找不到相册"的详细报告） */
    private val _diagnostic = MutableStateFlow<String?>(null)
    val diagnostic: StateFlow<String?> = _diagnostic.asStateFlow()

    /** 本地目录里已经有多少张 */
    val photoCount: StateFlow<Int> = repository.photoCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 本地目录里的全部照片，用来显示网格 */
    val allPhotos: StateFlow<List<PhotoEntity>> = repository.allPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 目录里最早那张图的时间（显示"覆盖范围"用） */
    val oldestDate: StateFlow<Long?> = repository.oldestDate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 目录里现在装着的是哪个相册（用来提醒"换相册会清掉旧的"） */
    val currentAlbumName: StateFlow<String?> = repository.currentAlbumName()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------------- 识别文字（阶段 2） ----------------

    /** 还没认过字的照片数量 */
    val ocrPending: StateFlow<Int> = repository.ocrPendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 认出来有文字的照片数量 */
    val ocrHit: StateFlow<Int> = repository.ocrHitCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 认过但没认出文字的照片数量（用户觉得这个比"总字数"有用） */
    val ocrMiss: StateFlow<Int> = repository.ocrMissCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---------------- 筛选条件（浏览页、搜索页、AI 搜索页共用） ----------------

    /**
     * 文件格式筛选：null = 不限（用户 2026-10 要求）。
     *
     * ⚠️ 这个**替代了原来的"清晰度"筛选** —— 用户明确说"以后不需要清晰度分类"。
     *    只分两类：GIF（会动的图）/ 普通图片。
     */
    private val _filterKind = MutableStateFlow<FileKind?>(null)
    val filterKind: StateFlow<FileKind?> = _filterKind.asStateFlow()

    /**
     * 年份筛选：**一个范围**（用户 2026-10 要求，原来是单个年份）。
     *
     * `null` = 不限。两端都是**闭区间**（含首尾），例如 `2024..2026` 表示
     * 2024、2025、2026 三年的都要。
     */
    private val _filterYearRange = MutableStateFlow<IntRange?>(null)
    val filterYearRange: StateFlow<IntRange?> = _filterYearRange.asStateFlow()

    /** 文字筛选：null = 不限；true = 有文字；false = 未识别到文字 */
    private val _filterHasText = MutableStateFlow<Boolean?>(null)
    val filterHasText: StateFlow<Boolean?> = _filterHasText.asStateFlow()

    fun setFilterKind(kind: FileKind?) {
        if (_filterKind.value == kind) return
        _filterKind.value = kind
        aiLastConditionSignature = ""
        forgetAiSearch()
    }

    /**
     * 设定年份范围。
     *
     * 会自动纠正：如果起始年 > 结束年，就把结束年拉到和起始年一样
     * （避免出现"负数区间"这种无意义的状态）。
     */
    fun setFilterYearRange(range: IntRange?) {
        val fixed = range?.let {
            if (it.first <= it.last) it else it.first..it.first
        }
        if (_filterYearRange.value == fixed) return
        _filterYearRange.value = fixed
        aiLastConditionSignature = ""
        forgetAiSearch()
    }

    fun setFilterHasText(hasText: Boolean?) {
        if (_filterHasText.value == hasText) return
        _filterHasText.value = hasText
        aiLastConditionSignature = ""
        forgetAiSearch()
    }

    /** 三个筛选一起重置 */
    fun resetFilters() {
        _filterKind.value = null
        _filterYearRange.value = null
        _filterHasText.value = null
        // 条件变了，AI 那边的进度和结果都要作废
        aiLastConditionSignature = ""
        forgetAiSearch()
    }

    /** 被取消跳过、还没识别的照片数量（只要有它，就该显示「继续识别」） */
    val ocrSkipped: StateFlow<Int> = repository.ocrSkippedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 长期缓存里存了多少张的识别结果。
     * 换相册、重扫相册都不会丢，所以换回旧相册不用重新识别。
     */
    val ocrCacheCount: StateFlow<Int> = repository.ocrCacheCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 按相册统计的识别情况（"已识别相册"弹窗用） */
    val albumOcrStats: StateFlow<List<AlbumOcrStat>> = repository.albumOcrStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 识别进度。
     *
     * ⚠️ 识别**只在前台进行**（不做后台服务）。
     *    原因：国产 ROM 会强行限制后台，连前台服务也被掐，代码层面绕不过去。
     *    用户想边识别边用手机，可以把 App 切成**小窗** —— 那时它仍算"前台"。
     */
    private val _ocr = MutableStateFlow(PhotoRepository.OcrProgress())
    val ocr: StateFlow<PhotoRepository.OcrProgress> = _ocr.asStateFlow()

    /**
     * 正在跑的识别任务。
     *
     * ⚠️ **必须用真实的 Job 来判断"有没有在跑"，不能看进度里的 done/total。**
     *
     * 之前踩过一个严重的坑：用 `done < total` 判断"是否正在跑"，
     * 结果**取消之后**（done=5、total=100）条件**永远为真**，
     * 于是点「继续」被误判成"已经在跑了"直接忽略 ——
     * 而协程还在暂停循环里死等，整个识别就卡死了。
     *
     * 现在改成一个真实的任务句柄：
     *  - 启动前检查它是否 `isActive`
     *  - 取消时**直接把它 cancel 掉**，协程立刻结束，不留悬挂
     */
    private var ocrJob: Job? = null

    /** 识别跑完之后的总结（弹窗提示用；null = 不需要提示） */
    private val _ocrSummary = MutableStateFlow<String?>(null)
    val ocrSummary: StateFlow<String?> = _ocrSummary.asStateFlow()

    fun clearOcrSummary() {
        _ocrSummary.value = null
    }

    /** 开始/继续识别（只在前台跑） */
    fun startOcr() {
        // 已经有一个活着的任务 → 说明是在跑或暂停中，不要再起一个
        if (ocrJob?.isActive == true) return

        ocrJob = viewModelScope.launch {
            try {
                val summary = repository.runOcr { p -> _ocr.value = p }
                _ocrSummary.value = summary
            } catch (e: CancellationException) {
                throw e // 取消是正常流程，不要当成错误
            } catch (e: Exception) {
                _error.value = "识别失败：${e.message}"
            } finally {
                // 收尾：把进度状态归位，避免界面一直显示"识别中/已暂停"
                _ocr.value = _ocr.value.copy(isPaused = false)
            }
        }
    }

    /** 暂停识别（当前这张会识别完，然后停下来） */
    fun pauseOcr() {
        repository.pauseOcr()
        _ocr.value = _ocr.value.copy(isPaused = true)
    }

    /** 继续识别 */
    fun resumeOcr() {
        repository.resumeOcr()
        _ocr.value = _ocr.value.copy(isPaused = false)
    }

    /**
     * 取消识别：**直接把任务停掉**（已识别的结果都在数据库里，不会丢），
     * 并把剩下没识别的标记成"跳过"，之后可以再点「继续识别」。
     *
     * ⚠️ 这里**不依赖识别循环自己收尾** ——
     *    因为我们是强行 cancel 协程的，循环里的清理代码不一定会执行到。
     *    所以"标记跳过"由这里单独跑一个协程去做，保证一定完成。
     */
    /**
     * **悄悄中止识别**（换相册时用）。
     *
     * 和 [cancelOcr] 的区别：**不把剩下的标记成"跳过"**。
     *
     * 为什么不能标记跳过：
     *   换相册时，旧相册的照片马上就要被**删掉**了，
     *   给它们标"跳过"毫无意义；更糟的是，
     *   如果这一步晚于新相册写入，会把**新相册的照片**全标成"跳过"，
     *   界面就会莫名其妙显示"还有 N 张未识别（上次取消了）"。
     *
     * 所以这里只做三件事：**叫停 → 停协程 → 进度归零**。
     */
    private fun abortOcrQuietly() {
        // 先置"悄悄中止"标志，让识别循环在收尾时**不要**标记跳过，
        // 再强停协程（双保险：标志保证逻辑正确，cancel 保证立刻停）
        repository.abortOcrQuietly()
        ocrJob?.cancel()
        ocrJob = null
        _ocr.value = PhotoRepository.OcrProgress()
    }

    fun cancelOcr() {
        repository.cancelOcr()
        ocrJob?.cancel()
        ocrJob = null
        _ocr.value = PhotoRepository.OcrProgress() // 界面回到"没在跑"的状态

        viewModelScope.launch {
            runCatching { repository.skipRemainingOcr() }
        }
    }

    /**
     * 清空识别结果并重新识别一遍。
     * 用在"识别精度提高了"或"想重新认一次"的时候。
     * ⚠️ 会连长期缓存一起清掉，所以下次扫描时需要重新识别。
     */
    fun resetOcrAndRestart() {
        if (ocrJob?.isActive == true) return
        ocrJob = viewModelScope.launch {
            try {
                _ocr.value = PhotoRepository.OcrProgress()
                repository.resetOcr()
                val summary = repository.runOcr { p -> _ocr.value = p }
                _ocrSummary.value = summary
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "重新识别失败：${e.message}"
            } finally {
                _ocr.value = _ocr.value.copy(isPaused = false)
            }
        }
    }

    /**
     * 识别自检：拿前几张照片试一下，把每一步的真实结果报出来。
     * 用来判断"认不出文字"到底卡在哪一步。
     */
    fun runOcrSelfTest() {
        viewModelScope.launch {
            _loadingAlbums.value = true
            runCatching { repository.ocrSelfTest(3) }
                .onSuccess { _diagnostic.value = it }
                .onFailure { _diagnostic.value = "自检失败：${it.message}" }
            _loadingAlbums.value = false
        }
    }

    // ---------------- 界面切换 ----------------
    //
    // 四个界面，用一个枚举表示"现在在哪一页"：
    //   ALBUM    选择相册（App 启动页）
    //   ANALYZE  识别分析（扫描完成后自动进入）
    //   PHOTOS   浏览图片
    //   SEARCH   搜索图片
    //
    // 用枚举而不是一堆布尔值，这样"返回上一页"的逻辑很简单，也不会出现
    // 两个页面同时为 true 这种矛盾状态。

    private val _screen = MutableStateFlow(Screen.ALBUM)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** 重新扫描时把"扫描完成"标记清掉，避免自动跳转重复触发 */
    private val _scanJustFinished = MutableStateFlow(false)
    val scanJustFinished: StateFlow<Boolean> = _scanJustFinished.asStateFlow()

    fun consumeScanFinished() {
        _scanJustFinished.value = false
    }

    /** 进入"识别分析"页 */
    fun openAnalyzeScreen() {
        _screen.value = Screen.ANALYZE
    }

    /**
     * 返回上一页。
     * 统一的返回逻辑，界面的返回按钮和手机返回键都调它。
     */
    fun goBack() {
        _screen.value = when (_screen.value) {
            Screen.ALBUM -> Screen.ALBUM
            Screen.ANALYZE -> Screen.ALBUM
            Screen.PHOTOS -> Screen.ANALYZE
            Screen.SEARCH -> Screen.ANALYZE
            // 设置页可能从"识别分析"或"AI 搜索"进来，统一回识别分析页最稳
            Screen.SETTINGS -> Screen.ANALYZE
            Screen.AI -> Screen.ANALYZE
            // 结果页是"AI 搜索页"的下级，返回就回搜索页（能继续搜下一批）
            Screen.AI_RESULTS -> Screen.AI
        }
    }

    /** 去看 AI 搜索找到的结果（独立整页展示） */
    fun openAiResultsScreen() {
        _screen.value = Screen.AI_RESULTS
    }

    fun openSearchScreen() {
        _searchText.value = ""
        _viewerIndex.value = null
        _screen.value = Screen.SEARCH
    }

    /**
     * 正在看大图的那张照片在列表里的位置；null = 没在看大图。
     *
     * ⚠️ 这个状态必须放在 ViewModel 里，不能放在界面里。
     *    放在界面里的话，一打开大图，图片列表那部分界面就被销毁重建了，
     *    连带网格的滚动位置一起丢失 —— 表现就是"看完大图回来，列表跳回顶端"。
     */
    private val _viewerIndex = MutableStateFlow<Int?>(null)
    val viewerIndex: StateFlow<Int?> = _viewerIndex.asStateFlow()

    /**
     * 两个网格的滚动状态（浏览图片页一个、搜索页一个）。
     *
     * ⚠️ **为什么存在这里，不用 rememberLazyGridState**：
     *    打开大图时，我们**整个网格那一层会被跳过**（直接 return 去渲染大图），
     *    网格会被销毁重建，`rememberLazyGridState` 记的位置就没了 ——
     *    表现就是"看完大图回来，列表跳回顶部"。
     *    放在 ViewModel 里，界面怎么重建都不会丢。
     */
    val browseGridState = LazyGridState()
    val searchGridState = LazyGridState()

    /** AI 搜索结果页的网格滚动状态（单独一份，免得和别的页面互相干扰） */
    val aiResultsGridState = LazyGridState()

    /**
     * 两个网格**各自**记住的"上次滚动到顶部时用的条件签名"。
     *
     * 用途：**判断要不要把列表滚回顶部**。
     *
     * ⚠️ 为什么不能只靠 `LaunchedEffect(签名)`：
     *    打开大图时网格会从界面上消失，关闭时**重新挂载**，
     *    而 `LaunchedEffect` 在**每次挂载时都会执行一遍** ——
     *    于是"关闭大图"就必然触发一次"回到顶部"，跟签名变没变无关。
     *
     *    所以要把"上次用的是哪个签名"记在 ViewModel 里（比界面活得久），
     *    只有签名**真的和上次不同**（= 用户改了搜索词或筛选条件）才回顶部。
     *
     * ⚠️ 两个页面各用一份，不能共用 ——
     *    否则在搜索页改了条件，会顺带把浏览页的签名也改掉，页面之间互相干扰。
     */
    private val _searchScrollKey = MutableStateFlow("")
    val searchScrollKey: StateFlow<String> = _searchScrollKey.asStateFlow()

    private val _browseScrollKey = MutableStateFlow("")
    val browseScrollKey: StateFlow<String> = _browseScrollKey.asStateFlow()

    fun setSearchScrollKey(key: String) {
        _searchScrollKey.value = key
    }

    fun setBrowseScrollKey(key: String) {
        _browseScrollKey.value = key
    }

    fun openViewer(index: Int) {
        _viewerIndex.value = index.coerceAtLeast(0)
    }

    fun closeViewer() {
        _viewerIndex.value = null
        // 大图关了，补文字界面也一起关掉，避免残留
        _editingPhoto.value = null
    }

    // ---------------- 手动补 / 改文字（阶段 4） ----------------

    /**
     * 正在补文字的这张照片；null = 没在编辑。
     *
     * 放在 ViewModel 里，因为**四个界面**（浏览 / 文字搜索 / AI 搜索 / AI 结果）
     * 都是从同一个全屏查看器进去的，编辑状态统一由这里管。
     */
    private val _editingPhoto = MutableStateFlow<PhotoEntity?>(null)
    val editingPhoto: StateFlow<PhotoEntity?> = _editingPhoto.asStateFlow()

    /** 打开"补充 / 修正文字" */
    fun startEditText(photo: PhotoEntity) {
        _editingPhoto.value = photo
    }

    fun cancelEditText() {
        _editingPhoto.value = null
    }

    /**
     * 保存用户补/改的文字。
     *
     * ⚠️ 空串表示"**这张图就是没字**"（明确删空），不会回退到机器识别结果。
     */
    fun saveEditedText(text: String) {
        val photo = _editingPhoto.value ?: return
        _editingPhoto.value = null
        viewModelScope.launch {
            runCatching { repository.saveUserText(photo.id, text) }
                .onFailure { _error.value = "保存文字失败：${it.message}" }
        }
    }

    /** 恢复机器识别的文字（清掉人工补录） */
    fun restoreMachineText() {
        val photo = _editingPhoto.value ?: return
        _editingPhoto.value = null
        viewModelScope.launch {
            runCatching { repository.restoreMachineText(photo.id) }
                .onFailure { _error.value = "恢复失败：${it.message}" }
        }
    }

    // ---------------- 搜索（阶段 3） ----------------

    /** 用户输入的关键词 */
    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    /** 输入停顿一下再搜，避免每敲一个字都算一遍 */
    private val debouncedQuery: Flow<String> = _searchText
        .debounce(250)
        .map { it.trim() }
        .distinctUntilChanged()

    /**
     * 搜索结果。
     *
     * ⚠️ **只搜当前相册**：数据源是本地目录，而目录按用户要求只装一个相册的东西，
     * 所以天然就是"只搜这一个相册"。
     *
     * **两步走**：
     *
     * 1. **先用"确定的条件"筛**（清晰度、年份、有没有文字）——
     *    用户提出这个需求的原因很实际：文字识别有随机误差，
     *    用已知的确定条件先把范围缩小，能有效排除识别错误带来的干扰项。
     * 2. **再做分词匹配**（见 [TextMatcher]）——
     *    输入按空格切成词，**每个词都要能在图里的文字中找到**；
     *    **不排序**，命中的按时间倒序（新的在前）。
     */
    val searchResults: StateFlow<List<PhotoEntity>> =
        combine(
            debouncedQuery,
            repository.allPhotos(),
            filterKind,
            filterYearRange,
            filterHasText,
        ) { query, photos, kind, yearRange, hasText ->
            // 第一步：按"确定的条件"筛
            val base = photos.filter { p ->
                (kind == null || p.fileKind == kind) &&
                    (yearRange == null || yearOf(p.dateMillis) in yearRange) &&
                    (hasText == null || p.hasRecognizedText == hasText)
            }

            // 第二步：分词匹配（不打字时就是纯条件筛选，返回全部）
            TextMatcher.filter(base, query)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun clearSearch() {
        _searchText.value = ""
    }

    // ---------------- 「按图片内容搜索」（阶段 5） ----------------

    /** 用户的描述，比如"一只橘猫趴在键盘上" */
    private val _aiDescription = MutableStateFlow("")
    val aiDescription: StateFlow<String> = _aiDescription.asStateFlow()

    /**
     * 用户选择"本次比对多少张"的弹窗开关。
     *
     * 这个数量直接决定花销，而且用户经常想"这次少比几张试试"，
     * 所以在 AI 搜索页上**直接能改**，不用跑去设置页。
     */
    private val _showBatchSizeDialog = MutableStateFlow(false)
    val showBatchSizeDialog: StateFlow<Boolean> = _showBatchSizeDialog.asStateFlow()

    fun openBatchSizeDialog() {
        _showBatchSizeDialog.value = true
    }

    fun closeBatchSizeDialog() {
        _showBatchSizeDialog.value = false
    }

    /** AI 正在比对 */
    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()

    /** AI 挑出来的照片（按相似度从高到低） */
    private val _aiResults = MutableStateFlow<List<PhotoEntity>>(emptyList())
    val aiResults: StateFlow<List<PhotoEntity>> = _aiResults.asStateFlow()

    /**
     * **被复核剔掉的**照片。
     *
     * 用户要求："我不清楚被排除的图片中有没有我想要搜索的角色，
     * 所以做一个查看排除的图片的按钮。"
     *
     * 复核用的是"宁可错杀"的策略，所以**必须让用户能翻看被杀掉的** ——
     * 否则漏掉的东西永远发现不了。
     */
    private val _aiRejected = MutableStateFlow<List<PhotoEntity>>(emptyList())
    val aiRejected: StateFlow<List<PhotoEntity>> = _aiRejected.asStateFlow()

    /** 结果页当前看的是哪一类：false = 找到的，true = 被排除的 */
    private val _aiShowRejected = MutableStateFlow(false)
    val aiShowRejected: StateFlow<Boolean> = _aiShowRejected.asStateFlow()

    fun setAiShowRejected(show: Boolean) {
        _aiShowRejected.value = show
    }

    /** 结果说明 / 出错信息 */
    private val _aiMessage = MutableStateFlow<String?>(null)
    val aiMessage: StateFlow<String?> = _aiMessage.asStateFlow()

    /** 是不是已经搜过一次（用来决定界面显示"提示"还是"结果"） */
    private val _aiHasSearched = MutableStateFlow(false)
    val aiHasSearched: StateFlow<Boolean> = _aiHasSearched.asStateFlow()

    /**
     * 用户设了几个"确定的条件"（文件格式 / 年份范围 / 文字）。
     *
     * ⚠️ **必须至少设一个才能开始 AI 搜索**（用户 2026-10 明确要求）。
     *    原因：AI 搜索是把候选图发到云端比对，候选图越多越贵越慢。
     *    先用确定条件把范围缩小，既省钱又更准。
     */
    val conditionCount: StateFlow<Int> = combine(
        filterKind, filterYearRange, filterHasText,
    ) { kind, year, text ->
        listOf(kind != null, year != null, text != null).count { it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 按当前"确定条件"筛出来的候选图 */
    fun aiCandidates(): List<PhotoEntity> {
        val kind = _filterKind.value
        val yearRange = _filterYearRange.value
        val hasText = _filterHasText.value
        return allPhotos.value.filter { p ->
            (kind == null || p.fileKind == kind) &&
                (yearRange == null || yearOf(p.dateMillis) in yearRange) &&
                (hasText == null || p.hasRecognizedText == hasText)
        }
    }

    fun setAiDescription(text: String) {
        _aiDescription.value = text
        // 描述改了 → 换了搜索目标 → **旧结果和已比对记录一起忘掉**
        if (text.trim() != _aiLastDescription) {
            forgetAiSearch()
            _aiLastDescription = text.trim()
        }
    }

    /** 上一次搜索用的描述（用来判断"是不是换了搜索目标"） */
    private var _aiLastDescription = ""

    /**
     * 忘掉 AI 搜索的进度和结果。
     *
     * **三种情况必须调它**（用户 2026-10 明确要求"要自动忘掉"）：
     *  1. **换了相册** —— 照片集合整个变了，旧的"已比对编号"完全对不上
     *  2. **改了筛选条件** —— 候选集合变了
     *  3. **改了搜索内容（描述）** —— 换了搜索目标
     *
     * 忘了之后：结果清空、"已比对"归零，下次点搜索就是从头开始。
     *
     * ⚠️ 不清的话会怎样：
     *    换相册后旧记录还在，于是新相册的图会被误跳过（因为编号可能撞上），
     *    表现就是"换了相册却搜不出东西"。
     */
    fun forgetAiSearch() {
        aiSearchedIds.clear()
        _aiResults.value = emptyList()
        _aiRejected.value = emptyList()
        _aiShowRejected.value = false
        _aiMessage.value = null
        _aiHasSearched.value = false
        _aiStage.value = null
        _aiRawResponse.value = ""
        refreshAiCounts()
    }

    /**
     * 是否先"理解搜索意图"（必要时联网查资料）。
     *
     * 开着能认出"动漫角色名""作品名"这类专有名词，但会多花一点点钱和时间。
     */
    private val _aiUseUnderstanding = MutableStateFlow(true)
    val aiUseUnderstanding: StateFlow<Boolean> = _aiUseUnderstanding.asStateFlow()

    fun setAiUseUnderstanding(on: Boolean) {
        _aiUseUnderstanding.value = on
    }

    /**
     * 要不要**复核**（逐张确认，剔掉不像的）。
     *
     * 用户实测"返回 49 张，只有 26 张对"（约一半是误报）——
     * 因为初选阶段我特意写得很宽松（怕漏掉）。复核能大幅提高精度。
     *
     * 代价：多一次请求（只处理初选出来的那些），花销增加有限。
     */
    private val _aiVerify = MutableStateFlow(true)
    val aiVerify: StateFlow<Boolean> = _aiVerify.asStateFlow()

    fun setAiVerify(on: Boolean) {
        _aiVerify.value = on
    }

    /** 当前在干什么（"正在理解你的描述…"这种），显示给用户看 */
    private val _aiStage = MutableStateFlow<String?>(null)
    val aiStage: StateFlow<String?> = _aiStage.asStateFlow()

    /**
     * **缓存的理解结果**（特征清单、联网资料等）。
     *
     * ⚠️ 用户要求："继续搜索剩下的图时，理解阶段应该不变，
     *    不要搜索一次就重新理解一遍，这样会导致理解误差让搜索不准确。"
     *
     * 为什么这个缓存很重要：
     *   理解阶段是让 AI 把"无声铃鹿"翻译成"浅栗色中长发、马耳、紫红眼睛…"。
     *   如果每批都重新理解，模型每次措辞会略有不同（"浅栗色" / "浅棕色"），
     *   **判断标准就飘了** —— 第一批用标准 A 挑、第二批用标准 B 挑，
     *   结果自然不一致，用户会觉得"同样的搜索怎么时好时坏"。
     *
     * 什么时候作废（[forDescription] 会判断）：
     *   - 改了描述 → 换了搜索目标 → 重算
     *   - `forgetAiSearch()`（换相册/改条件/重新开始）→ 清空
     */
    private var aiUnderstanding: AiImageSearch.Understanding? = null

    /**
     * AI 的原始回复（诊断用）。
     *
     * "一张都搜不出来"时，这段文字是**唯一能查出原因的东西** ——
     * 能看出是模型不认识、还是没听懂提示词、还是返回格式没解析对。
     */
    private val _aiRawResponse = MutableStateFlow("")
    val aiRawResponse: StateFlow<String> = _aiRawResponse.asStateFlow()

    fun clearAiRawResponse() {
        _aiRawResponse.value = ""
    }

    /**
     * 已经发给 AI 比对过的照片编号。
     *
     * ⚠️ **这是"分批搜索不重复"的关键**：
     *    用户点"继续搜下一批"时，会把已经比过的**跳过**，
     *    只发还没比过的那一批。这样来回搜几次也不会重复花钱。
     *
     * 什么时候清空：
     *  - 改了描述（换了搜索目标）
     *  - 改了筛选条件（候选集合变了）
     *  - 用户主动点"重新开始"
     */
    private val aiSearchedIds = mutableSetOf<Long>()

    /** 本轮已经比对过多少张 */
    private val _aiSearchedCount = MutableStateFlow(0)
    val aiSearchedCount: StateFlow<Int> = _aiSearchedCount.asStateFlow()

    /** 还有多少张没比对过 */
    private val _aiRemainingCount = MutableStateFlow(0)
    val aiRemainingCount: StateFlow<Int> = _aiRemainingCount.asStateFlow()

    /**
     * **本次实际会比对多少张**。
     *
     * ⚠️ 这是个 bug 的修复（用户反馈）：
     *    用户先选了"全部 300 张"，然后改筛选条件，候选只剩 200 张，
     *    但按钮上还写着"本次 300 张" —— 显示的数字**大于实际存在的图**。
     *
     * 原因是 `maxCandidates`（用户设的值，300）和"还剩多少张没比"（200）
     * 是两回事，界面直接显示了前者。所以这里派生一个**取两者较小值**的字段，
     * 界面一律用它，就不会再出现"300 > 200"这种自相矛盾。
     */
    val aiEffectiveBatch: StateFlow<Int> = combine(
        settings.map { it.maxCandidates },
        _aiRemainingCount,
    ) { maxCandidates, remaining ->
        // -1 表示"全部" → 就是剩下的全部
        val cap = if (maxCandidates == AppSettings.ALL) Int.MAX_VALUE else maxCandidates
        remaining.coerceAtMost(cap).coerceAtLeast(0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 界面上那个按钮该显示的字（"本次 200 张" / "全部 350 张"） */
    val aiBatchLabel: StateFlow<String> = combine(
        settings.map { it.maxCandidates },
        _aiRemainingCount,
    ) { maxCandidates, remaining ->
        val r = remaining.coerceAtLeast(0)
        val n = if (maxCandidates == AppSettings.ALL) r else r.coerceAtMost(maxCandidates)
        if (maxCandidates == AppSettings.ALL) "全部 $n 张" else "本次 $n 张"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "本次 0 张")

    /** 用户选的是不是"全部" */
    val aiWantsAll: StateFlow<Boolean> = settings
        .map { it.maxCandidates == AppSettings.ALL }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 筛选条件变了 → 候选集合变了 → 之前的"已比对"记录不再适用，清掉重来。
     * 在每次搜索时会自动检查（比较条件签名），不用界面手动调。
     */
    private var aiLastConditionSignature = ""

    private fun currentConditionSignature(): String =
        "${_filterKind.value}|${_filterYearRange.value}|${_filterHasText.value}"

    /** 刷新"已比对 / 剩余"的统计 */
    private fun refreshAiCounts() {
        val total = aiCandidates().size
        _aiSearchedCount.value = aiSearchedIds.size
        _aiRemainingCount.value = (total - aiSearchedIds.size).coerceAtLeast(0)
    }

    /**
     * 清掉上一次搜索留下的**所有可见状态**。
     *
     * ⚠️ 踩过的 bug（用户实测）：
     *    这里原来只清了「结果 / 说明 / 是否搜过 / 进度」，
     *    漏了「被排除的图 / 被排除的开关 / AI 原始回复」——
     *    于是点「重新开始」之后，**被排除的那几张图和原始回复还挂在界面上**，
     *    看起来像"没清干净"。
     *
     * 原则：**只要界面上看得见，就必须在这里清掉**。
     */
    fun clearAiResult() {
        _aiResults.value = emptyList()
        _aiRejected.value = emptyList()
        _aiShowRejected.value = false
        _aiMessage.value = null
        _aiHasSearched.value = false
        _aiStage.value = null
        _aiRawResponse.value = ""
    }

    /**
     * 完全重新开始：忘掉"已比对过"的记录 + 清空界面上所有搜索结果。
     *
     * 会清：已比对记录、找到的图、**被排除的图**、结果说明、**AI 原始回复**、进度文字。
     * 不会清：搜索描述、筛选条件、**已缓存的理解结果**（描述没变，标准应该保持一致）。
     */
    fun resetAiProgress() {
        aiSearchedIds.clear()
        clearAiResult()
        refreshAiCounts()
    }

    /** 界面用来刷新"已比对 / 还剩"的统计（条件或照片集合变了时调） */
    fun refreshAiStats() {
        refreshAiCounts()
    }

    fun openAiSearchScreen() {
        _screen.value = Screen.AI
        refreshAiCounts()
    }

    fun openSettingsScreen() {
        _screen.value = Screen.SETTINGS
    }

    /**
     * 开始 AI 搜索（或"继续搜下一批"）。
     *
     * 流程：
     *  1. 本地按确定条件筛出全部候选
     *  2. **跳过已经比对过的**，取接下来 [AppSettings.maxCandidates] 张
     *  3. 先让 AI 理解描述（必要时联网查资料）
     *  4. 把这一批发给 AI 比对，挑出最像的
     *
     * 「继续搜下一批」和第一次搜索走的是**同一个函数** ——
     * 因为"跳过已比对"是自动的，所以再点一次自然就是下一批。
     */
    fun startAiSearch() {
        if (_aiLoading.value) return

        val description = _aiDescription.value.trim()
        if (description.isEmpty()) {
            _aiMessage.value = "请先描述一下图长什么样。"
            return
        }
        if (conditionCount.value == 0) {
            _aiMessage.value = "请先设置至少一个条件（格式 / 年份 / 文字），再开始搜索。"
            return
        }

        val s = settings.value
        if (!s.aiReady) {
            _aiMessage.value = "还没有填 AI 密钥。请先到设置页填写。"
            return
        }

        // 条件变了 → 候选集合变了 → 清掉旧的"已比对"记录
        val signature = currentConditionSignature()
        if (signature != aiLastConditionSignature) {
            aiSearchedIds.clear()
            aiLastConditionSignature = signature
        }

        val candidates = aiCandidates()
        if (candidates.isEmpty()) {
            _aiMessage.value = "当前条件下没有候选图片，放宽条件再试。"
            return
        }

        // 跳过已经比对过的，取下一批
        val fresh = candidates.filter { it.id !in aiSearchedIds }
        if (fresh.isEmpty()) {
            _aiMessage.value = "当前条件下的图都已经比对过了。\n" +
                "可以放宽条件、改描述，或者点「重新开始」再从头搜一遍。"
            refreshAiCounts()
            return
        }

        _aiLoading.value = true
        _aiMessage.value = null
        _aiHasSearched.value = true
        _aiStage.value = "正在准备…"

        viewModelScope.launch {
            // ⚠️ 从存储里**现读一次**设置，不要用 settings.value。
            //    因为用户可能刚填完密钥就点搜索，那时界面上的 settings 还没刷新到。
            val actual = runCatching { settingsStore.current() }.getOrDefault(s)

            // 取多少张：用 effectiveBatch（它会把"全部"和"候选没那么多"两种情况都处理对）
            val takeCount = actual.effectiveBatch(fresh.size)
            val batch = fresh.take(takeCount.coerceAtLeast(1))

            val result = AiImageSearch.search(
                context = getApplication(),
                settings = actual,
                description = description,
                candidates = batch.map { it.id to it.uri },
                useUnderstanding = _aiUseUnderstanding.value,
                // ⚠️ **复用上次的理解结果**（用户要求）。
                //    继续搜下一批时不再重新理解 —— 否则每批得到的"特征清单"
                //    会有细微差异，判断标准就飘了，搜索结果就不一致。
                //    cacheAiUnderstanding 里已经保证"描述变了就作废"。
                cachedUnderstanding = aiUnderstanding,
                verify = _aiVerify.value,
                onStage = { _aiStage.value = it },
            )

            // 把这次用的理解结果缓存起来，下一批直接复用
            result.understanding?.let { aiUnderstanding = it }

            // 记住"这一批比过了"，下次自动跳过
            batch.forEach { aiSearchedIds += it.id }

            // 新挑出来的放前面（最新的搜索结果更可能是用户想要的），
            // 但保留之前批次已经找到的，别让它们消失
            val byId = batch.associateBy { it.id }
            val newlyFound = result.photoIds.mapNotNull { byId[it] }
            val merged = (newlyFound + _aiResults.value).distinctBy { it.id }
            _aiResults.value = merged

            // 被复核剔掉的也留着，用户能翻看（怕"宁可错杀"杀错了）
            val newlyRejected = result.rejectedIds.mapNotNull { byId[it] }

            // ⚠️ **"被排除的"要包含"第一轮筛选就没选上的"**（用户反馈）。
            //
            //    之前只显示"复核剔掉的"，用户看不到"初选阶段就没选上"的那些 ——
            //    但那些同样是"AI 看过、但没要"的图，用户可能想检查。
            //
            //    所以定义成：**这一批发给 AI 的图 − 最终找到的** = 被排除的。
            //    这样两个阶段的排除都在里面，一个不漏。
            val newlyFoundIds = result.photoIds.toSet()
            val rejectedThisBatch = batch.filter { it.id !in newlyFoundIds }

            // 已经被"找到"的，就不该再出现在"被排除的"列表里
            val foundIds = merged.map { it.id }.toSet()
            _aiRejected.value = (_aiRejected.value + rejectedThisBatch)
                .distinctBy { it.id }
                .filter { it.id !in foundIds }

            val remaining = (candidates.size - aiSearchedIds.size).coerceAtLeast(0)
            _aiMessage.value = buildString {
                append("本批比对 ${batch.size} 张，挑出 ${newlyFound.size} 张。")
                append("\n累计找到 ${merged.size} 张 · 已比对 ${aiSearchedIds.size}/${candidates.size} 张")
                if (remaining > 0) append(" · 还剩 $remaining 张没比对")
                if (result.message.isNotBlank()) {
                    append("\n\n")
                    append(result.message)
                }
            }

            // 留着 AI 的原始回复，出问题时能查（点界面上的"看 AI 的原始回复"）
            _aiRawResponse.value = result.raw

            _aiStage.value = null
            _aiLoading.value = false
            refreshAiCounts()
        }
    }

    /** 设置页：测试 AI 连接 */
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun clearTestResult() {
        _testResult.value = null
    }

    fun testAiConnection() {
        if (_testing.value) return
        val s = settings.value
        if (!s.aiReady) {
            _testResult.value = "请先填 API 密钥。"
            return
        }
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            _testResult.value = AiImageSearch.testConnection(s)
            _testing.value = false
        }
    }

    fun openPhotosScreen() {
        _screen.value = Screen.PHOTOS
    }

    init {
        loadAlbums()

        // ⚠️ **换相册 → 自动忘掉 AI 搜索的进度和结果**（用户 2026-10 要求）。
        //
        // 为什么必须监听"当前相册名"而不是在扫描函数里清：
        //   换相册有好几条路径（点扫描、确认换相册、以后可能还有别的），
        //   在每条路径上都手动清很容易漏。**监听结果状态最保险** ——
        //   只要相册真的换了，就一定清。
        viewModelScope.launch {
            repository.currentAlbumName().collect { name ->
                val changed = name != _lastAlbumName
                _lastAlbumName = name
                if (changed && name != null) {
                    // 换了相册 → 旧搜索结果全部作废
                    forgetAiSearch()
                    // 换相册等于换了一整个照片库，缓存的理解也该扔掉
                    aiUnderstanding = null
                    // 按用户要求：搜索框清空、三个条件都恢复成"不限"
                    _aiDescription.value = ""
                    _aiLastDescription = ""
                    _filterKind.value = null
                    _filterYearRange.value = null
                    _filterHasText.value = null
                    aiLastConditionSignature = ""
                    refreshAiCounts()
                }
            }
        }
    }

    /** 上一次看到的相册名（用来发现"换相册了"） */
    private var _lastAlbumName: String? = null

    /** 列出手机里的相册 */
    fun loadAlbums() {
        viewModelScope.launch {
            _loadingAlbums.value = true
            runCatching { repository.listAlbums() }
                .onSuccess { list ->
                    _albums.value = list
                    // 如果还没选过，默认帮用户选中"相机"相册，省一步操作
                    if (_selectedAlbum.value == null) {
                        _selectedAlbum.value = list.firstOrNull {
                            it.bucketName.contains("相机") || it.bucketName.contains("Camera", true)
                        } ?: list.firstOrNull()
                    }
                }
                .onFailure { _error.value = "列出相册失败：${it.message}" }
            _loadingAlbums.value = false
        }
    }

    fun selectAlbum(album: MediaStoreReader.AlbumInfo) {
        _selectedAlbum.value = album
    }

    /**
     * 排查"为什么找不到相册"。
     * 结果会显示在界面上，用户可以直接念给我听。
     */
    fun diagnose() {
        viewModelScope.launch {
            _loadingAlbums.value = true
            runCatching { repository.diagnose() }
                .onSuccess { d ->
                    val bucketText = if (d.buckets.isEmpty()) {
                        "（一个都没有）"
                    } else {
                        d.buckets.take(12).joinToString("\n") { (name, n) -> "  • $name：$n 张" }
                    }
                    _diagnostic.value = buildString {
                        appendLine("系统一共交出 ${d.totalImages} 张图片")
                        appendLine("按目录分：")
                        appendLine(bucketText)
                        if (d.error != null) appendLine("查询出错：${d.error}")
                    }
                }
                .onFailure { _diagnostic.value = "排查失败：${it.message}" }
            _loadingAlbums.value = false
        }
    }

    fun clearDiagnostic() {
        _diagnostic.value = null
    }

    /** 扫描选中的相册 */
    fun scanSelectedAlbum() {
        val album = _selectedAlbum.value ?: run {
            _error.value = "请先选一个相册"
            return
        }
        if (_scan.value.isScanning) return
        scanAlbumInternal(album)
    }

    /**
     * 先清空目录，再扫描指定相册。
     * 用在"用户确认要换相册"的时候。
     */
    fun clearCatalogAndScan(album: MediaStoreReader.AlbumInfo) {
        if (_scan.value.isScanning) return
        scanAlbumInternal(album)
    }

    private fun scanAlbumInternal(album: MediaStoreReader.AlbumInfo) {
        // ⚠️ **换相册 = 立刻中止正在跑的识别**（用户反馈的 bug）。
        //
        //    不中止会怎样：
        //      扫描会把旧相册的记录删掉，而识别循环手里还拿着**旧的照片编号**，
        //      于是它继续"识别"一堆已经不存在的记录 —— 进度条照走、时间照样耗，
        //      但其实一行都写不进去（或者写进缓存却不在当前相册里），纯属白跑。
        //
        //    为什么放在**扫描开始时**、而不是"相册名变化时"：
        //      相册名是扫描**结束后**才变的（要等新照片写进数据库）。
        //      如果等它变才取消，整个扫描期间识别还在跑 —— 来不及。
        abortOcrQuietly()

        viewModelScope.launch {
            runCatching {
                repository.scanAlbum(album) { progress -> _scan.value = progress }
                // 扫描完成 → 标记一下，界面会自动跳到"识别分析"页
                _scanJustFinished.value = true
            }.onFailure {
                _scan.value = PhotoRepository.ScanProgress(
                    isScanning = false,
                    scanned = 0,
                    message = "扫描失败：${it.message}",
                )
                _error.value = "扫描失败：${it.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** 把界面需要的"创建方式"准备好（不需要额外的依赖注入框架） */
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                PhotoViewModel(app)
            }
        }
    }
}
