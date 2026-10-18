package com.atri.photofinder.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.atri.photofinder.data.ai.AiImageSearch
import com.atri.photofinder.data.db.PhotoEntity
import com.atri.photofinder.data.media.MediaStoreReader
import com.atri.photofinder.data.settings.AiProviders
import com.atri.photofinder.model.FileKind
import com.atri.photofinder.model.ResolutionTier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 界面总入口：三个页面，按状态切换。
 *
 *   一「选择相册」—— 授权、挑相册、扫描、识别文字
 *   二「浏览图片」—— 整屏照片网格 + 弹窗筛选
 *   三「查看大图」—— 全屏看图，可缩放、可拖动
 */
@Composable
fun MainScreen(viewModel: PhotoViewModel) {
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(MediaPermissions.hasAccess(context)) }
    var partialOnly by remember { mutableStateOf(MediaPermissions.isPartialAccessOnly(context)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = MediaPermissions.hasAccess(context)
        partialOnly = MediaPermissions.isPartialAccessOnly(context)
        asked = true
        if (hasPermission) viewModel.loadAlbums()
    }

    // 首次进入自动申请一次，省得用户找不到入口
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(MediaPermissions.permissionsToRequest())
            asked = true
        }
    }

    val screen by viewModel.screen.collectAsState()
    val scanJustFinished by viewModel.scanJustFinished.collectAsState()

    // 扫描完成 → 自动进入"识别分析"页
    LaunchedEffect(scanJustFinished) {
        if (scanJustFinished) {
            viewModel.consumeScanFinished()
            viewModel.openAnalyzeScreen()
        }
    }

    // 手机上按返回键 → 回上一页（不是直接退出 App）
    BackHandler(enabled = screen != Screen.ALBUM) { viewModel.goBack() }

    when {
        !hasPermission -> PermissionScreen(
            askedBefore = asked,
            onRequest = { launcher.launch(MediaPermissions.permissionsToRequest()) },
        )

        screen == Screen.SETTINGS -> SettingsScreen(viewModel = viewModel)

        screen == Screen.AI -> AiSearchScreen(viewModel = viewModel)

        screen == Screen.AI_RESULTS -> AiResultsScreen(viewModel = viewModel)

        screen == Screen.SEARCH -> SearchScreen(viewModel = viewModel)

        screen == Screen.PHOTOS -> PhotosScreen(viewModel = viewModel)

        screen == Screen.ANALYZE -> AnalyzeScreen(viewModel = viewModel)

        else -> AlbumScreen(
            viewModel = viewModel,
            partialOnly = partialOnly,
            onReRequest = { launcher.launch(MediaPermissions.permissionsToRequest()) },
        )
    }

    // ---------- 全局弹窗 ----------
    // 放在最外层，这样**任何页面**都能弹出诊断结果 / 识别完成，不会出现"点了没反应"

    // 补充 / 修正文字（阶段 4）
    // 放在最外层 → 浏览页、文字搜索页、AI 搜索页、AI 结果页**全都能用**
    val editingPhoto by viewModel.editingPhoto.collectAsState()
    editingPhoto?.let { photo ->
        TextEditDialog(
            photo = photo,
            onCancel = { viewModel.cancelEditText() },
            onSave = { viewModel.saveEditedText(it) },
            onRestoreMachine = { viewModel.restoreMachineText() },
        )
    }

    val diagnostic by viewModel.diagnostic.collectAsState()
    diagnostic?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearDiagnostic() },
            title = { Text("诊断结果") },
            text = {
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDiagnostic() }) { Text("知道了") }
            },
        )
    }

    val ocrSummary by viewModel.ocrSummary.collectAsState()
    ocrSummary?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearOcrSummary() },
            title = { Text("识别完成") },
            text = {
                Text(
                    text = msg,
                    fontSize = 13.sp,
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearOcrSummary() }) { Text("知道了") }
            },
        )
    }

    val error by viewModel.error.collectAsState()
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("出错了") },
            text = {
                Text(
                    text = msg,
                    fontSize = 13.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("知道了") }
            },
        )
    }
}

// ============================================================
//  页面一：选择相册
// ============================================================

/**
 * App 的启动页，只做一件事：选相册。
 *
 * 布局刻意保持极简 —— 顶部是应用名，中间**整块留白给相册列表**，
 * 底部一个扫描按钮。其余步骤都放到下一页，避免这一页拥挤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumScreen(
    viewModel: PhotoViewModel,
    partialOnly: Boolean,
    onReRequest: () -> Unit,
) {
    val albums by viewModel.albums.collectAsState()
    val selected by viewModel.selectedAlbum.collectAsState()
    val scan by viewModel.scan.collectAsState()
    val loading by viewModel.loadingAlbums.collectAsState()
    val error by viewModel.error.collectAsState()
    val diagnostic by viewModel.diagnostic.collectAsState()
    val count by viewModel.photoCount.collectAsState()
    val currentAlbum by viewModel.currentAlbumName.collectAsState()

    var pendingAlbum by remember { mutableStateOf<MediaStoreReader.AlbumInfo?>(null) }

    // ---------- 弹窗：换相册确认 ----------
    pendingAlbum?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingAlbum = null },
            title = { Text("是否更换相册") },
            text = { Text("更换后将扫描「${target.bucketName}」并清空当前目录记录。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCatalogAndScan(target)
                    pendingAlbum = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAlbum = null }) { Text("取消") }
            },
        )
    }

    // ---------- 诊断结果 / 识别完成 / 出错 都由最外层统一处理（见 MainScreen） ----------

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("相册找图") },
                actions = {
                    // 设置入口（填 AI 密钥的地方）
                    IconButton(onClick = { viewModel.openSettingsScreen() }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {

            if (partialOnly) {
                InfoCard(
                    title = "仅授权了部分照片",
                    body = "当前只能看到你勾选的照片。想看全部，请重新授权并选择「允许全部」。",
                    actionText = "重新授权",
                    onAction = onReRequest,
                )
            }

            // 当前相册 + 刷新（很轻的一行，不占地方）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            ) {
                Text(
                    text = currentAlbum?.let { "当前相册：$it" } ?: "请选择相册",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { viewModel.loadAlbums() }) { Text("刷新", fontSize = 12.sp) }
            }

            // ---------- 中间整块：相册列表 ----------
            when {
                loading -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("正在查找相册…", fontSize = 13.sp)
                    }
                }

                albums.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "未找到相册",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.diagnose() }) { Text("诊断") }
                    }
                }

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(albums, key = { it.bucketId }) { album ->
                        AlbumRow(
                            album = album,
                            isSelected = album.bucketId == selected?.bucketId,
                            onClick = { viewModel.selectAlbum(album) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            // ---------- 底部：扫描 ----------
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            if (scan.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    text = scan.message ?: "正在扫描…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(6.dp))
            }

            Button(
                onClick = {
                    val target = selected ?: return@Button
                    // 已经装了别的相册且里面有内容 → 先确认
                    if (currentAlbum != null && currentAlbum != target.bucketName && count > 0) {
                        pendingAlbum = target
                    } else {
                        viewModel.scanSelectedAlbum()
                    }
                },
                enabled = selected != null && !scan.isScanning,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Text(
                    when {
                        scan.isScanning -> "扫描中…"
                        selected == null -> "请先选择相册"
                        else -> "扫描「${selected?.bucketName}」"
                    }
                )
            }
        }
    }
}

/** 相册列表的一行 */
@Composable
private fun AlbumRow(
    album: MediaStoreReader.AlbumInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isSelected) "●" else "○",
            fontSize = 16.sp,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = album.bucketName,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp,
        )
        Text(
            text = "${album.photoCount} 张",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ============================================================
//  页面二：识别分析
// ============================================================

/**
 * 扫描完成后的页面：在这里做"识别"，然后决定去搜索还是浏览。
 *
 * 布局：顶部当前相册信息 → 中间识别结果统计 → 底部几个操作按钮。
 * 左上角有返回按钮（回相册页），手机返回键同样可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyzeScreen(viewModel: PhotoViewModel) {
    val currentAlbum by viewModel.currentAlbumName.collectAsState()
    val count by viewModel.photoCount.collectAsState()
    val oldest by viewModel.oldestDate.collectAsState()
    val ocrPending by viewModel.ocrPending.collectAsState()
    val ocrHit by viewModel.ocrHit.collectAsState()
    val ocrMiss by viewModel.ocrMiss.collectAsState()
    val ocrSkipped by viewModel.ocrSkipped.collectAsState()
    val ocrCache by viewModel.ocrCacheCount.collectAsState()
    val albumStats by viewModel.albumOcrStats.collectAsState()
    val ocrProgress by viewModel.ocr.collectAsState()
    val ocrSummary by viewModel.ocrSummary.collectAsState()

    var showAlbumStats by remember { mutableStateOf(false) }
    var showBackgroundHelp by remember { mutableStateOf(false) }

    // ---------- 弹窗：已识别相册 ----------
    if (showAlbumStats) {
        val stats by viewModel.albumOcrStats.collectAsState()
        AlertDialog(
            onDismissRequest = { showAlbumStats = false },
            title = { Text("已识别相册") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (stats.isEmpty()) {
                        Text("暂无识别记录", fontSize = 13.sp)
                    } else {
                        stats.forEach { s ->
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    text = s.bucketName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${s.total} 张　·　有文字 ${s.recognized}　·　无文字 ${s.noText}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            HorizontalDivider()
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "切换相册或重扫都不会丢。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAlbumStats = false }) { Text("关闭") }
            },
        )
    }

    // ---------- 弹窗：识别时如何使用手机 ----------
    if (showBackgroundHelp) {
        AlertDialog(
            onDismissRequest = { showBackgroundHelp = false },
            title = { Text("识别时用手机") },
            text = {
                Column {
                    Text(
                        text = "识别需要 App 在前台。\n" +
                            "想边用手机边识别，把它切成小窗：\n" +
                            "上滑进最近任务 → 点图标 → 选「小窗」。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "国产系统会限制后台，常驻通知也挡不住，所以不做后台识别。\n" +
                            "进度实时保存，随时可暂停或继续。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackgroundHelp = false }) { Text("知道了") }
            },
        )
    }

    // ---------- 弹窗：诊断结果、识别完成、出错 都由最外层统一处理 ----------

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentAlbum ?: "未扫描",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append("$count 张")
                                if (oldest != null && oldest!! > 0L) {
                                    append("　最早 ${yearOf(oldest!!)} 年")
                                }
                            },
                            fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 设置入口（填 AI 密钥的地方）
                    IconButton(onClick = { viewModel.openSettingsScreen() }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {

            Spacer(Modifier.height(8.dp))

            // ---------- 识别结果概览 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                // 一条极淡的边，让白卡片和浅蓝背景之间有条清晰的界线
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = "识别文字", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "本地识别 · 不联网 · 不收费",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(10.dp))

                    if (ocrProgress.isRunning) {
                        LinearProgressIndicator(
                            progress = {
                                if (ocrProgress.total == 0) 0f
                                else ocrProgress.done.toFloat() / ocrProgress.total.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = buildString {
                                append(if (ocrProgress.isPaused) "已暂停　" else "识别中　")
                                append("${ocrProgress.done} / ${ocrProgress.total}")
                                if (!ocrProgress.isPaused) {
                                    append("　有文字 ${ocrProgress.withText} 张")
                                }
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )

                        // ---------- 识别中的操作按钮（紧跟进度条下方） ----------
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ocrProgress.isPaused) {
                                Button(
                                    onClick = { viewModel.resumeOcr() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("继续") }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.pauseOcr() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("暂停") }
                            }
                            OutlinedButton(
                                onClick = { viewModel.cancelOcr() },
                                modifier = Modifier.weight(1f),
                            ) { Text("取消") }
                        }
                        TextButton(
                            onClick = { showBackgroundHelp = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("识别时用手机", fontSize = 12.sp) }
                    } else {
                        if (ocrHit > 0 || ocrMiss > 0) {
                            Text(
                                text = "有文字 $ocrHit 张　·　没文字 $ocrMiss 张",
                                fontSize = 13.sp,
                            )
                        } else {
                            Text(
                                text = "尚未识别",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (ocrCache > 0) {
                            TextButton(
                                onClick = { showAlbumStats = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("已识别相册（${albumStats.size} 个）", fontSize = 12.sp) }
                        }
                        Text(
                            text = when {
                                count == 0 -> "请先扫描相册"
                                ocrPending == 0 -> "全部识别完成"
                                ocrSkipped > 0 -> "还有 $ocrSkipped 张没识别（上次取消了）"
                                else -> "待识别 $ocrPending 张"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )

                        // ---------- 识别按钮（紧跟状态文字下方） ----------
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.startOcr() },
                            enabled = count > 0 && ocrPending > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    count == 0 -> "请先扫描相册"
                                    ocrPending == 0 -> "已全部识别"
                                    ocrHit > 0 || ocrMiss > 0 -> "继续识别（$ocrPending 张）"
                                    else -> "按文字识别（$count 张）"
                                }
                            )
                        }

                        if (ocrHit > 0 || ocrMiss > 0) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TextButton(
                                    onClick = { viewModel.resetOcrAndRestart() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("重新识别", fontSize = 12.sp) }
                                TextButton(
                                    onClick = { viewModel.runOcrSelfTest() },
                                    enabled = count > 0,
                                    modifier = Modifier.weight(1f),
                                ) { Text("诊断", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 下一步入口（移到这里：紧跟识别卡片下方） ----------
            // 用户要求：这两个按钮往上移，紧贴"识别文字"卡片
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.openSearchScreen() },
                    enabled = count > 0,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("搜索图片", fontSize = 14.sp) }
                OutlinedButton(
                    onClick = {
                        viewModel.resetFilters()
                        viewModel.openPhotosScreen()
                    },
                    enabled = count > 0,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("浏览图片", fontSize = 14.sp) }
            }

            // 把剩下的空间吃掉，这样下面的 AI 入口就**贴在屏幕底部**
            Spacer(Modifier.weight(1f))

            // ---------- 底部：按图片内容搜索（放大、更醒目） ----------
            AiSearchEntry(onClick = { viewModel.openAiSearchScreen() })
        }
    }
}

// ============================================================
//  页面五：设置（AI 模型 API）
// ============================================================

/**
 * 设置页。
 *
 * 目前主要就一件事：**填 AI 模型的 API 密钥**，让「按图片内容搜索」能用。
 *
 * ⚠️ 密钥只存在这台手机上（DataStore），**绝不写进代码、绝不上传 GitHub**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(viewModel: PhotoViewModel) {
    val settings by viewModel.settings.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    var showKeyDialog by remember { mutableStateOf(false) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var showMaxDialog by remember { mutableStateOf(false) }

    // ---------- 弹窗：填密钥 ----------
    if (showKeyDialog) {
        var draft by remember { mutableStateOf(settings.apiKey) }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("API 密钥") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴你的密钥，一般以 sk- 开头", fontSize = 13.sp) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "密钥只保存在这台手机上，不会上传到任何地方（除了你选的那家 AI 服务）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveApiKey(draft)
                    showKeyDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 弹窗：选服务商 ----------
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("选择 AI 服务") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                ) {
                    AiProviders.ALL.forEach { p ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.saveProvider(p)
                                    showProviderDialog = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = (if (p.id == settings.provider.id) "● " else "○ ") + p.displayName,
                                fontSize = 15.sp,
                                color = if (p.id == settings.provider.id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (p.note.isNotBlank()) {
                                Text(p.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 弹窗：自定义地址与模型名 ----------
    if (showEndpointDialog) {
        var base by remember { mutableStateOf(settings.effectiveBaseUrl) }
        var model by remember { mutableStateOf(settings.effectiveModelName) }
        AlertDialog(
            onDismissRequest = { showEndpointDialog = false },
            title = { Text("服务地址与模型") },
            text = {
                Column {
                    Text("服务地址", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(
                        value = base,
                        onValueChange = { base = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://api.deepseek.com", fontSize = 13.sp) },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("模型名", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("deepseek-flash", fontSize = 13.sp) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "留空就恢复成所选服务的默认值。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveCustomEndpoint(base, model)
                    showEndpointDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEndpointDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 弹窗：一次比对多少张 ----------
    if (showMaxDialog) {
        AlertDialog(
            onDismissRequest = { showMaxDialog = false },
            title = { Text("一次比对多少张") },
            text = {
                var customText by remember { mutableStateOf("") }
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "这个数字直接决定每次搜索的花销和等待时间。\n" +
                            "可以自己填，最大 500 张。",
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customText,
                        onValueChange = { new ->
                            val digits = new.filter { it.isDigit() }.take(3)
                            val n = digits.toIntOrNull()
                            customText = if (n != null && n > 500) "500" else digits
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义张数", fontSize = 12.sp) },
                        placeholder = { Text("1 ~ 500", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val n = customText.toIntOrNull() ?: return@TextButton
                                    viewModel.saveMaxCandidates(n)
                                    showMaxDialog = false
                                },
                                enabled = (customText.toIntOrNull() ?: 0) in 1..500,
                            ) { Text("确定", fontSize = 12.sp) }
                        },
                    )

                    customText.toIntOrNull()?.takeIf { it in 1..500 }?.let { n ->
                        Text(
                            text = "约 ${AiImageSearch.estimate(n).text}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("或者快速选一个：", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))

                    listOf(10, 20, 50, 100, 300).forEach { n ->
                        TierOption(
                            label = "$n 张　（约 ${AiImageSearch.estimate(n).text}）",
                            selected = settings.maxCandidates == n,
                        ) {
                            viewModel.saveMaxCandidates(n)
                            showMaxDialog = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMaxDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 弹窗：测试结果 ----------
    testResult?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearTestResult() },
            title = { Text("测试连接") },
            text = {
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearTestResult() }) { Text("知道了") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(text = "AI 模型", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "「按图片内容搜索」用，需要联网",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))

            SettingRow(
                title = "API 密钥",
                value = settings.maskedApiKey,
                highlight = !settings.hasApiKey,
                onClick = { showKeyDialog = true },
            )
            SettingRow(
                title = "AI 服务",
                value = settings.provider.displayName,
                onClick = { showProviderDialog = true },
            )
            SettingRow(
                title = "服务地址",
                value = settings.effectiveBaseUrl,
                onClick = { showEndpointDialog = true },
            )
            SettingRow(
                title = "模型名",
                value = settings.effectiveModelName,
                onClick = { showEndpointDialog = true },
            )
            SettingRow(
                title = "一次比对",
                value = if (settings.wantsAll) {
                    "全部（按候选数自动定）"
                } else {
                    "${settings.maxCandidates} 张　（约 ${AiImageSearch.estimate(settings.maxCandidates).text}）"
                },
                onClick = { showMaxDialog = true },
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { viewModel.testAiConnection() },
                enabled = settings.hasApiKey && !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "正在测试…" else "测试连接")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(text = "关于隐私", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "· 密钥只存在本机，不会上传。\n" +
                    "· 文字识别完全本地，不联网。\n" +
                    "· 只有点「开始搜索」才联网，且只发筛选出的候选图。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 设置页的一行（左边标题、右边当前值） */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(text = title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 13.sp,
            color = if (highlight) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outline
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
            textAlign = TextAlign.End,
        )
    }
    HorizontalDivider()
}

// ============================================================
//  页面六：按图片内容搜索（云端 AI）
// ============================================================

/**
 * 「按图片内容搜索」。
 *
 * ## 流程（用户拍板的方案）
 *
 * ```
 * ① 输入描述（图长什么样）
 * ② 先设至少一个"确定条件"（清晰度 / 年份 / 文字）← 必须，用来缩小候选
 * ③ 本地筛出候选图
 * ④ 只把候选发给云端 AI 比对
 * ⑤ 返回最像的几张
 * ```
 *
 * ⚠️ **为什么强制要求先设条件**（用户明确要求）：
 *    候选图越多，上传越慢、花钱越多、AI 也越容易挑错。
 *    先用确定条件缩小范围，又省钱又更准。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSearchScreen(viewModel: PhotoViewModel) {
    val description by viewModel.aiDescription.collectAsState()
    val loading by viewModel.aiLoading.collectAsState()
    val results by viewModel.aiResults.collectAsState()
    val message by viewModel.aiMessage.collectAsState()
    val hasSearched by viewModel.aiHasSearched.collectAsState()
    val conditionCount by viewModel.conditionCount.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val allCount by viewModel.photoCount.collectAsState()
    val useUnderstanding by viewModel.aiUseUnderstanding.collectAsState()
    val verify by viewModel.aiVerify.collectAsState()
    val stage by viewModel.aiStage.collectAsState()
    val searchedCount by viewModel.aiSearchedCount.collectAsState()
    val remainingCount by viewModel.aiRemainingCount.collectAsState()
    val effectiveBatch by viewModel.aiEffectiveBatch.collectAsState()
    val batchLabel by viewModel.aiBatchLabel.collectAsState()
    val wantsAll by viewModel.aiWantsAll.collectAsState()
    val rejected by viewModel.aiRejected.collectAsState()
    val showBatchSizeDialog by viewModel.showBatchSizeDialog.collectAsState()
    val rawResponse by viewModel.aiRawResponse.collectAsState()
    var showRawDialog by remember { mutableStateOf(false) }

    // ---------- 弹窗：AI 的原始回复（排查"为什么搜不到"） ----------
    if (showRawDialog) {
        AlertDialog(
            onDismissRequest = { showRawDialog = false },
            title = { Text("AI 的原始回复") },
            text = {
                Text(
                    text = rawResponse,
                    fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showRawDialog = false }) { Text("关闭") }
            },
        )
    }

    val filterKind by viewModel.filterKind.collectAsState()
    val filterYearRange by viewModel.filterYearRange.collectAsState()
    val filterHasText by viewModel.filterHasText.collectAsState()

    val allPhotos by viewModel.allPhotos.collectAsState()
    val years: List<Int> = remember(allPhotos) {
        allPhotos.map { yearOf(it.dateMillis) }.filter { it > 0 }.distinct().sortedDescending()
    }
    val hasOcrResults = remember(allPhotos) { allPhotos.any { it.ocrState != 0 } }

    // 候选图数量（跟着条件实时变）
    val candidateCount = remember(allPhotos, filterKind, filterYearRange, filterHasText) {
        allPhotos.count { p ->
            (filterKind == null || p.fileKind == filterKind) &&
                (filterYearRange == null || yearOf(p.dateMillis) in filterYearRange!!) &&
                (filterHasText == null || p.hasRecognizedText == filterHasText)
        }
    }

    var showKindDialog by remember { mutableStateOf(false) }
    var showYearDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }

    // 条件或照片集合变了 → 刷新"已比对 / 还剩"的统计
    LaunchedEffect(allPhotos.size, filterKind, filterYearRange, filterHasText) {
        viewModel.refreshAiStats()
    }

    val viewerIndex by viewModel.viewerIndex.collectAsState()
    val viewing = viewerIndex
    if (viewing != null && results.isNotEmpty()) {
        FullScreenViewer(
            photos = results,
            initialIndex = viewing.coerceIn(0, results.size - 1),
            onClose = { viewModel.closeViewer() },
            onEditText = { viewModel.startEditText(it) },
        )
        return
    }

    // ---------- 三个条件的弹窗（和浏览页/搜索页一样的组件） ----------
    if (showKindDialog) {
        FileKindDialog(
            current = filterKind,
            onPick = { kind -> viewModel.setFilterKind(kind); showKindDialog = false },
            onDismiss = { showKindDialog = false },
        )
    }

    if (showYearDialog && years.isNotEmpty()) {
        YearRangePickerDialog(
            years = years,
            current = filterYearRange,
            onPick = { range -> viewModel.setFilterYearRange(range); showYearDialog = false },
            onDismiss = { showYearDialog = false },
        )
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("按文字筛选") },
            text = {
                Column {
                    TierOption("不限", filterHasText == null) {
                        viewModel.setFilterHasText(null); showTextDialog = false
                    }
                    TierOption("有文字", filterHasText == true) {
                        viewModel.setFilterHasText(true); showTextDialog = false
                    }
                    TierOption("未识别到文字", filterHasText == false) {
                        viewModel.setFilterHasText(false); showTextDialog = false
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTextDialog = false }) { Text("取消") } },
        )
    }

    // ---------- 弹窗：本次比对多少张（可以自定义，不限固定档位） ----------
    if (showBatchSizeDialog) {
        var customText by remember { mutableStateOf("") }

        // 这个弹窗里能用到的最大值：不超过"还没比对过的张数"
        val maxUsable = remainingCount.coerceAtLeast(1)

        AlertDialog(
            onDismissRequest = { viewModel.closeBatchSizeDialog() },
            title = { Text("本次比对多少张") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "最多 $maxUsable 张（还没比对过的）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))

                    // ---- 手动输入（自定义） ----
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { new ->
                            // 只留数字，并且不让它超过可用张数
                            val digits = new.filter { it.isDigit() }.take(4)
                            val n = digits.toIntOrNull()
                            customText = if (n != null && n > maxUsable) "$maxUsable" else digits
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义张数", fontSize = 12.sp) },
                        placeholder = { Text("1 ~ $maxUsable", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val n = customText.toIntOrNull() ?: return@TextButton
                                    viewModel.saveMaxCandidates(n)
                                    viewModel.closeBatchSizeDialog()
                                },
                                enabled = (customText.toIntOrNull() ?: 0) in 1..maxUsable,
                            ) { Text("确定", fontSize = 12.sp) }
                        },
                    )

                    // 自定义时的花销估算
                    customText.toIntOrNull()?.takeIf { it in 1..maxUsable }?.let { n ->
                        Text(
                            text = "约 ${AiImageSearch.estimate(
                                imageCount = n,
                                withUnderstanding = useUnderstanding,
                                withWebSearch = useUnderstanding,
                                withVerify = verify,
                            ).text}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("或者快速选一个：", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))

                    // 快捷档位只列"没超过可用张数"的
                    listOf(10, 20, 50, 100, 200).filter { it <= maxUsable }.forEach { n ->
                        TierOption(
                            label = "$n 张　（约 ${AiImageSearch.estimate(
                                imageCount = n,
                                withUnderstanding = useUnderstanding,
                                withWebSearch = useUnderstanding,
                                withVerify = verify,
                            ).text}）",
                            selected = settings.maxCandidates == n,
                        ) {
                            viewModel.saveMaxCandidates(n)
                            viewModel.closeBatchSizeDialog()
                        }
                    }

                    // 一键全比（图多的时候很实用）
                    // ⚠️ 存的是"**全部**"这个意图，不是当时的数字 ——
                    //    这样条件变了、候选变多，显示会跟着变（修掉过一个 bug）。
                    if (maxUsable > 10) {
                        TierOption(
                            label = "全部 $maxUsable 张　（约 ${AiImageSearch.estimate(
                                imageCount = maxUsable,
                                withUnderstanding = useUnderstanding,
                                withWebSearch = useUnderstanding,
                                withVerify = verify,
                            ).text}）",
                            selected = wantsAll,
                        ) {
                            viewModel.saveMaxCandidatesAll()
                            viewModel.closeBatchSizeDialog()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.closeBatchSizeDialog() }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("按图片内容搜索") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openSettingsScreen() }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                // ⚠️ **这一页必须能滚动**。
                //    内容多（输入框 + 条件条 + 开关 + 统计 + 按钮 + 说明），
                //    不能滚的话超出屏幕的部分（**包括"查看结果"按钮**）
                //    会被挤到屏幕外面，用户根本点不到。
                .verticalScroll(rememberScrollState()),
        ) {

            // ---------- 没填密钥：先引导去设置 ----------
            if (!settings.hasApiKey) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("需要先填 AI 密钥", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "到设置页填一个密钥就能用。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.openSettingsScreen() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("去设置") }
                    }
                }
                return@Column
            }

            // ---------- 描述 ----------
            OutlinedTextField(
                value = description,
                onValueChange = { viewModel.setAiDescription(it) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("描述图长什么样，如：一只橘猫趴在键盘上", fontSize = 13.sp) },
                minLines = 2,
                maxLines = 4,
            )

            // ---------- 条件（必须先设） ----------
            Text(
                text = "先缩小范围（至少选一个条件）",
                fontSize = 12.sp,
                fontWeight = if (conditionCount == 0) FontWeight.Bold else FontWeight.Normal,
                color = if (conditionCount == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )

            FilterBar(
                filterKind = filterKind,
                filterYearRange = filterYearRange,
                filterHasText = filterHasText,
                showTextFilter = hasOcrResults,
                onKindClick = { showKindDialog = true },
                onYearClick = { showYearDialog = true },
                onTextClick = { showTextDialog = true },
                onReset = { viewModel.resetFilters() },
            )

            Spacer(Modifier.height(4.dp))

            // ---------- 是否让 AI 先理解描述（必要时联网） ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setAiUseUnderstanding(!useUnderstanding) }
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(
                    checked = useUnderstanding,
                    onCheckedChange = { viewModel.setAiUseUnderstanding(it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("先理解描述（可联网查资料）", fontSize = 13.sp)
                    Text(
                        text = "能认出动漫角色名、作品名",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // ---------- 是否复核（提高准确度） ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setAiVerify(!verify) }
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(
                    checked = verify,
                    onCheckedChange = { viewModel.setAiVerify(it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("再复核一遍（推荐）", fontSize = 13.sp)
                    Text(
                        text = "逐张再确认，剔掉不像的，准得多",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 统计 + **点这里改"本次比多少张"**（用户要求能在界面上自己选）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "符合 $candidateCount 张　·　已比对 $searchedCount　·　还剩 $remainingCount",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = "预计花销 " + AiImageSearch.estimate(
                            // ⚠️ 用 effectiveBatch（实际会比对的张数），
                            //    不要用 remainingCount 去 min settings.maxCandidates ——
                            //    那样虽然数字对，但和按钮上显示的来源不一致，容易又出 bug。
                            imageCount = effectiveBatch,
                            withUnderstanding = useUnderstanding,
                            withWebSearch = useUnderstanding,
                            withVerify = verify,
                        ).text,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                OutlinedButton(onClick = { viewModel.openBatchSizeDialog() }) {
                    Text(
                        // ⚠️ 用 aiBatchLabel —— 它会按**当前**候选数实时算。
                        //    不要读 settings.maxCandidates，那是"用户设的值"，
                        //    条件变了之后可能比实际候选还多（踩过这个 bug）。
                        text = batchLabel,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 搜索按钮 ----------
            Button(
                onClick = { viewModel.startAiSearch() },
                enabled = !loading && description.isNotBlank() &&
                    conditionCount > 0 && remainingCount > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = when {
                        loading -> "AI 正在处理…"
                        searchedCount > 0 && remainingCount > 0 ->
                            "继续搜下一批（还剩 $remainingCount 张）"
                        searchedCount > 0 && remainingCount == 0 -> "都比对过了"
                        else -> "开始搜索"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ---------- 结果入口 ----------
            // 放在搜索按钮正下方 —— 搜完顺手就能点。
            //
            // ⚠️ 这里**不直接铺缩略图**：这一页上面有输入框和一堆按钮，
            //    剩给网格的只有屏幕底下一窄条，**根本看不清**。
            //    所以改成按钮，点进独立的"搜索结果"页看完整的。
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        viewModel.setAiShowRejected(false)
                        viewModel.openAiResultsScreen()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("查看找到的 ${results.size} 张图")
                }
            }

            // ---------- 查看"被排除的" ----------
            // 复核是"宁可错杀"的策略，所以要给用户一个翻看被杀掉那些的入口，
            // 免得万一杀错了却永远发现不了。
            if (rejected.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.setAiShowRejected(true)
                        viewModel.openAiResultsScreen()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("查看被排除的 ${rejected.size} 张", fontSize = 13.sp)
                }
            }

            // 已经搜过一部分 → 给"重新开始"的出口
            if (searchedCount > 0 && !loading) {
                TextButton(
                    onClick = { viewModel.resetAiProgress() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重新开始", fontSize = 12.sp) }
            }

            if (loading) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stage ?: "正在处理…",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ---------- 说明 ----------
            // 只显示**统计数字**（本批挑出几张、累计几张）。
            //
            // ⚠️ 不再显示"AI 总结的特征"那一大段 ——
            //    用户说得很对：**原文在"看 AI 的原始回复"里都有，不用重复占地方。**
            //    （那段一长，就把"查看结果"按钮挤出屏幕了。）
            if (searchedCount > 0 && message != null) {
                Text(
                    text = message!!.substringBefore("\n\n"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // 出问题时能看 AI 到底回了什么（诊断用）
            if (rawResponse.isNotBlank() && !loading) {
                TextButton(
                    onClick = { showRawDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("AI 原始回复", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ============================================================
//  页面：AI 搜索的结果（独立整页）
// ============================================================

/**
 * AI 搜索的结果，**独立一整页**展示。
 *
 * 缩略图、点开看大图、缩放拖动这些**和"浏览图片"页完全一样**
 * （因为它们用的是同一个 [PhotoGrid] 组件和同一个全屏查看器）。
 *
 * 之所以单独开一页：AI 搜索页上面有输入框和一堆按钮，
 * 留给缩略图的只剩屏幕底下一窄条，看不清。
 *
 * 两种内容切换：
 *  - **找到的**：复核通过的
 *  - **被排除的**：复核剔掉的 —— 复核是"宁可错杀"，
 *    所以要让用户能翻看被杀掉那些，免得万一杀错了发现不了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiResultsScreen(viewModel: PhotoViewModel) {
    val found by viewModel.aiResults.collectAsState()
    val rejected by viewModel.aiRejected.collectAsState()
    val showRejected by viewModel.aiShowRejected.collectAsState()
    val description by viewModel.aiDescription.collectAsState()
    val message by viewModel.aiMessage.collectAsState()
    val viewerIndex by viewModel.viewerIndex.collectAsState()

    // 当前这一页要显示哪一批
    val photos = if (showRejected) rejected else found

    // 全屏看大图（和浏览页共用同一个查看器）
    val viewing = viewerIndex
    if (viewing != null && photos.isNotEmpty()) {
        FullScreenViewer(
            photos = photos,
            initialIndex = viewing.coerceIn(0, photos.size - 1),
            onClose = { viewModel.closeViewer() },
            onEditText = { viewModel.startEditText(it) },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (showRejected) "被排除的图" else "搜索结果",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = buildString {
                                append("${photos.size} 张")
                                if (description.isNotBlank()) append(" · 「$description」")
                            },
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // ---------- 两种内容之间切换 ----------
            if (rejected.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.setAiShowRejected(false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = (if (!showRejected) "● " else "") + "找到的 ${found.size}",
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.setAiShowRejected(true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = (if (showRejected) "● " else "") + "被排除 ${rejected.size}",
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            // ---------- 说明 ----------
            if (showRejected) {
                Text(
                    text = "AI 复核时剔掉的。里面若有你要的，放宽条件重搜。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                message?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            if (photos.isEmpty()) {
                Text(
                    text = if (showRejected) "没有被排除的图。" else "还没有结果。回上一页搜索一次。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                // 网格占满剩下的全部空间（这才是"能看清"的关键）
                PhotoGrid(
                    photos = photos,
                    gridState = viewModel.aiResultsGridState,
                    scrollSignature = "airesults|$showRejected|${photos.size}|$description",
                    lastSignature = "",
                    onSignatureApplied = {},
                    onOpenViewer = { viewModel.openViewer(it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ============================================================
//  页面三：搜索
// ============================================================

/**
 * 搜索页。
 *
 * ⚠️ **只搜当前相册**（目录里装的那个相册）。
 *    想搜别的相册，先回相册页扫描那个相册。
 *
 * 搜索依据：图里识别出的文字、用户手动补的文字、文件名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(viewModel: PhotoViewModel) {
    val query by viewModel.searchText.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val currentAlbum by viewModel.currentAlbumName.collectAsState()
    val allCount by viewModel.photoCount.collectAsState()
    val ocrHit by viewModel.ocrHit.collectAsState()

    // 条件筛选用（和浏览页共用同一份状态）
    val filterKind by viewModel.filterKind.collectAsState()
    val filterYearRange by viewModel.filterYearRange.collectAsState()
    val filterHasText by viewModel.filterHasText.collectAsState()

    val allPhotos by viewModel.allPhotos.collectAsState()
    val years: List<Int> = remember(allPhotos) {
        allPhotos.map { yearOf(it.dateMillis) }.filter { it > 0 }.distinct().sortedDescending()
    }
    val hasOcrResults = remember(allPhotos) { allPhotos.any { it.ocrState != 0 } }

    var showKindDialog by remember { mutableStateOf(false) }
    var showYearDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }

    val viewerIndex by viewModel.viewerIndex.collectAsState()

    // ---------- 全屏看大图 ----------
    val viewing = viewerIndex

    if (viewing != null && results.isNotEmpty()) {
        FullScreenViewer(
            photos = results,
            initialIndex = viewing.coerceIn(0, results.size - 1),
            onClose = { viewModel.closeViewer() },
            onEditText = { viewModel.startEditText(it) },
        )
        return
    }

    // ---------- 弹窗：按文件格式筛选 ----------
    if (showKindDialog) {
        FileKindDialog(
            current = filterKind,
            onPick = { kind -> viewModel.setFilterKind(kind); showKindDialog = false },
            onDismiss = { showKindDialog = false },
        )
    }

    // ---------- 弹窗：按年份范围筛选 ----------
    if (showYearDialog && years.isNotEmpty()) {
        YearRangePickerDialog(
            years = years,
            current = filterYearRange,
            onPick = { range ->
                viewModel.setFilterYearRange(range)
                showYearDialog = false
            },
            onDismiss = { showYearDialog = false },
        )
    }

    // ---------- 弹窗：按文字筛选 ----------
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("按文字筛选") },
            text = {
                Column {
                    TierOption("不限", filterHasText == null) {
                        viewModel.setFilterHasText(null); showTextDialog = false
                    }
                    TierOption("有文字", filterHasText == true) {
                        viewModel.setFilterHasText(true); showTextDialog = false
                    }
                    TierOption("未识别到文字", filterHasText == false) {
                        viewModel.setFilterHasText(false); showTextDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTextDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentAlbum ?: "未扫描",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (query.isBlank() && filterKind == null &&
                                filterYearRange == null && filterHasText == null
                            ) {
                                "共 $allCount 张"
                            } else {
                                "匹配 ${results.size} 张"
                            },
                            fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {

            // ---------- 搜索框 ----------
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setSearchText(it) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                placeholder = { Text("输入图里的文字", fontSize = 14.sp) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearSearch() }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
            )

            // ---------- 筛选条件 ----------
            // 这些是"确定的条件"，可以用来排除文字识别错误带来的干扰项
            FilterBar(
                filterKind = filterKind,
                filterYearRange = filterYearRange,
                filterHasText = filterHasText,
                showTextFilter = hasOcrResults,
                onKindClick = { showKindDialog = true },
                onYearClick = { showYearDialog = true },
                onTextClick = { showTextDialog = true },
                onReset = { viewModel.resetFilters() },
            )

            // ---------- 提示 ----------
            if (query.isBlank() && filterKind == null && filterYearRange == null && filterHasText == null) {
                Text(
                    text = if (ocrHit > 0) {
                        "用空格分词，每个词都要出现在图里。\n" +
                            "例：「发 票」= 同时含「发」和「票」。"
                    } else {
                        "还没识别过文字，先回上一页识别。"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // ---------- 结果 ----------
            when {
                allCount == 0 -> {
                    Text(
                        text = "还没扫描相册",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                results.isEmpty() -> {
                    Text(
                        text = "没有匹配的图片",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                else -> {
                    // 滚动"签名"：只放**用户设定的条件**。
                    // 条件没变 → 签名不变 → 位置自然保留（看大图回来就不会跳）。
                    // ⚠️ 千万不要把"打开大图"之类的东西加进来：
                    //    那会让"看大图"也变成"条件变了"，反而把位置弄丢。
                    val signature = "$query|$filterKind|$filterYearRange|$filterHasText"
                    val lastSignature by viewModel.searchScrollKey.collectAsState()
                    PhotoGrid(
                        photos = results,
                        gridState = viewModel.searchGridState,
                        scrollSignature = signature,
                        lastSignature = lastSignature,
                        onSignatureApplied = { viewModel.setSearchScrollKey(it) },
                        onOpenViewer = { viewModel.openViewer(it) },
                        modifier = Modifier.weight(1f).padding(top = 4.dp),
                    )
                }
            }

            // 用户要求：这个界面**不要**「按图片内容搜索」的入口
            // （它属于另一种搜索方式，放在文字搜索页里会让底部挤、也容易点错）
        }
    }
}

// ============================================================
//  页面三：浏览图片
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotosScreen(viewModel: PhotoViewModel) {
    val allPhotos by viewModel.allPhotos.collectAsState()
    val filterKind by viewModel.filterKind.collectAsState()
    val filterYearRange by viewModel.filterYearRange.collectAsState()
    val filterHasText by viewModel.filterHasText.collectAsState()
    val currentAlbum by viewModel.currentAlbumName.collectAsState()

    // 这个相册里有没有识别结果 —— 决定要不要显示"文字"筛选按钮
    val hasOcrResults = remember(allPhotos) { allPhotos.any { it.ocrState != 0 } }

    val years: List<Int> = remember(allPhotos) {
        allPhotos.map { yearOf(it.dateMillis) }.filter { it > 0 }.distinct().sortedDescending()
    }

    val filtered = remember(allPhotos, filterKind, filterYearRange, filterHasText) {
        allPhotos.filter { photo ->
            (filterKind == null || photo.fileKind == filterKind) &&
                (filterYearRange == null || yearOf(photo.dateMillis) in filterYearRange!!) &&
                (filterHasText == null || photo.hasRecognizedText == filterHasText)
        }
    }

    val viewerIndex by viewModel.viewerIndex.collectAsState()
    var showKindDialog by remember { mutableStateOf(false) }
    var showYearDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }

    // ---------- 全屏看大图 ----------
    val viewing = viewerIndex
    if (viewing != null && filtered.isNotEmpty()) {
        FullScreenViewer(
            photos = filtered,
            initialIndex = viewing.coerceIn(0, filtered.size - 1),
            onClose = { viewModel.closeViewer() },
            onEditText = { viewModel.startEditText(it) },
        )
        return
    }

    // ---------- 弹窗：按文件格式筛选 ----------
    if (showKindDialog) {
        FileKindDialog(
            current = filterKind,
            onPick = { kind -> viewModel.setFilterKind(kind); showKindDialog = false },
            onDismiss = { showKindDialog = false },
        )
    }

    // ---------- 弹窗：滚轮选年份范围 ----------
    if (showYearDialog && years.isNotEmpty()) {
        YearRangePickerDialog(
            years = years,
            current = filterYearRange,
            onPick = { range ->
                viewModel.setFilterYearRange(range)
                showYearDialog = false
            },
            onDismiss = { showYearDialog = false },
        )
    }

    // ---------- 弹窗：按文字筛选 ----------
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("按文字筛选") },
            text = {
                Column {
                    TierOption("不限", filterHasText == null) {
                        viewModel.setFilterHasText(null); showTextDialog = false
                    }
                    TierOption("有文字", filterHasText == true) {
                        viewModel.setFilterHasText(true); showTextDialog = false
                    }
                    TierOption("未识别到文字", filterHasText == false) {
                        viewModel.setFilterHasText(false); showTextDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTextDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // 显示当前目录里是哪个相册，避免用户搞混
                        Text(
                            text = currentAlbum ?: "未扫描",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(text = "${filtered.size} 张", fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
        ) {

            if (allPhotos.isEmpty()) {
                Text(
                    text = "还没扫描相册",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }

            // 筛选条：三个按钮挤在一排（宽度自动平分，不换行）
            FilterBar(
                filterKind = filterKind,
                filterYearRange = filterYearRange,
                filterHasText = filterHasText,
                showTextFilter = hasOcrResults,
                onKindClick = { showKindDialog = true },
                onYearClick = { showYearDialog = true },
                onTextClick = { showTextDialog = true },
                onReset = { viewModel.resetFilters() },
            )

            // 照片网格：占满剩余全部高度
            // 滚动"签名"只放筛选条件：改条件 → 回到顶部；看大图回来 → 位置保持
            val lastSignature by viewModel.browseScrollKey.collectAsState()
            PhotoGrid(
                photos = filtered,
                gridState = viewModel.browseGridState,
                scrollSignature = "$filterKind|$filterYearRange|$filterHasText",
                lastSignature = lastSignature,
                onSignatureApplied = { viewModel.setBrowseScrollKey(it) },
                onOpenViewer = { viewModel.openViewer(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TierOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = if (selected) "●" else "○", modifier = Modifier.width(26.dp))
        Text(label, fontSize = 14.sp)
    }
}

/**
 * 「按文件格式筛选」弹窗（浏览页 / 搜索页 / AI 搜索页共用）。
 *
 * 用户 2026-10 要求：**用"文件格式"替代原来的"清晰度"筛选**。
 * 只有两类：GIF（会动的图）/ 图片（普通静态图）。
 */
@Composable
private fun FileKindDialog(
    current: FileKind?,
    onPick: (FileKind?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按格式筛选") },
        text = {
            Column {
                TierOption("不限", current == null) { onPick(null) }
                TierOption("GIF　（会动的图）", current == FileKind.GIF) { onPick(FileKind.GIF) }
                TierOption("图片　（JPG / PNG 等静态图）", current == FileKind.IMAGE) {
                    onPick(FileKind.IMAGE)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 筛选条：格式 / 年份 / 文字，**三个按钮平分一排**。
 *
 * 用户要求："三个按钮放在一排，一排屏幕放不下就把按钮的宽度变小一点。"
 * 所以用 `weight(1f)` 让三个按钮**平分宽度**，屏幕窄就自动变窄，
 * 而不是换行（换行看起来丑）。
 *
 * 「重置」只在有筛选生效时才出现，单独占一小格。
 *
 * @param showTextFilter 有没有识别过。没识别过就不显示"文字"筛选（没有分类的意义）
 */
@Composable
private fun FilterBar(
    filterKind: FileKind?,
    filterYearRange: IntRange?,
    filterHasText: Boolean?,
    showTextFilter: Boolean,
    onKindClick: () -> Unit,
    onYearClick: () -> Unit,
    onTextClick: () -> Unit,
    onReset: () -> Unit,
) {
    val hasAnyFilter = filterKind != null || filterYearRange != null || filterHasText != null

    // 年份按钮上显示的文字：同一个年份就显示一个，否则显示范围
    val yearLabel = filterYearRange?.let { r ->
        if (r.first == r.last) "${r.first}" else "${r.first}-${r.last}"
    } ?: "不限"

    // ⚠️ 分两行是**故意的**（用户反馈过"两个年份在一个按钮里显示不全"）。
    //
    //    原来四个按钮挤一行，每个只有 ~88dp，
    //    "年份 2020-2026" 这种 13 个字符根本放不下，会被截断成"年份 202…"。
    //    所以把「重置」单独挪到第二行，让三个筛选按钮各拿 ~105dp，够放下。
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onKindClick,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    // 格式分类很短（GIF / 图片），用 "格式" 作前缀更省地方
                    text = "格式 ${filterKind?.label ?: "不限"}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            OutlinedButton(
                onClick = onYearClick,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "年份 $yearLabel",
                    fontSize = if (yearLabel.length > 5) 11.sp else 12.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            if (showTextFilter) {
                OutlinedButton(
                    onClick = onTextClick,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "文字 " + when (filterHasText) {
                            null -> "不限"
                            true -> "有字"
                            false -> "无字"
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        // 第二行：只有真的设了条件才出现"重置"，免得白占地方
        if (hasAnyFilter) {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) { Text("重置条件", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/**
 * 照片网格（带滚动位置记忆）。
 *
 * ## 滚动位置的规则（用户明确要求）
 *
 * | 发生了什么 | 列表位置 |
 * |---|---|
 * | **打开大图 → 退出** | **保持不变** |
 * | **改了搜索词 / 筛选条件** | **回到顶部** |
 *
 * ## 实现方式
 *
 * 1. **滚动状态由 ViewModel 持有**（[gridState] 参数传进来）。
 *    因为打开大图时整个网格会被跳过渲染、销毁重建，
 *    `rememberLazyGridState` 记的位置会丢 —— 那就是"跳回顶部"的原因。
 *
 * 2. 用一个**滚动"签名"**判断要不要回顶部：
 *    - 签名没变（比如只是看了个大图）→ 什么都不做，位置自然保留
 *    - 签名变了（说明改了搜索词或筛选条件）→ 主动滚回顶部
 *
 * 签名里为什么要带"打开大图的时间戳"：
 *    看大图时搜索结果本身没变。如果只比较条件，
 *    关闭大图时也会被当成"条件变了"，又被滚回顶部 —— 白修了。
 */
@Composable
private fun PhotoGrid(
    photos: List<PhotoEntity>,
    gridState: LazyGridState,
    scrollSignature: String,
    lastSignature: String,
    onSignatureApplied: (String) -> Unit,
    onOpenViewer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 关键点：**不能用 LaunchedEffect(签名) 来做这件事**。
    //    因为打开大图时整个网格会被跳过（从界面上消失），关闭时它**重新挂载**，
    //    而 LaunchedEffect 在**每次挂载时都会执行一遍** ——
    //    于是"关闭大图"就必然触发一次 scrollToItem(0,0)，列表跳到顶端。
    //    （签名只能决定"同一次挂载期间要不要重启"，管不了"重新挂载"。）
    //
    //    所以改成：**比较"这次的签名和上次的是不是一样"**，
    //    只有真的不同（= 用户改了条件）才滚回顶部。
    LaunchedEffect(scrollSignature) {
        if (lastSignature != scrollSignature) {
            onSignatureApplied(scrollSignature)
            gridState.scrollToItem(0, 0)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        items(photos, key = { it.id }) { photo ->
            PhotoCell(
                photo = photo,
                onClick = { onOpenViewer(photos.indexOfFirst { it.id == photo.id }) },
            )
        }
    }
}

/** 网格里的一张缩略图 */
@Composable
private fun PhotoCell(photo: PhotoEntity, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            // 有文字的图右上角标一个红点。
            // 注意：判断要用 hasRecognizedText（不含文件名），否则每张图都会有红点。
            if (photo.hasRecognizedText) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
        Text(
            text = buildString {
                // GIF 特别标一下，因为它是会动的图
                if (photo.fileKind == FileKind.GIF) append("GIF · ")
                append("${photo.resolutionTier.label} · ${photo.longSide}px")
            },
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = (if (photo.isDateApproximate) "约 " else "") + formatDate(photo.dateMillis),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}

// ============================================================
//  年份范围滚轮
// ============================================================

/**
 * 年份范围筛选弹窗（用户 2026-10 要求：从"单个年份"改成"范围"）。
 *
 * 左边是**起始年份**滚轮，右边是**结束年份**滚轮。
 *
 * ⚠️ **右边的可选项会跟着左边变**：
 *    左边选了 2024，右边就只会列出 2024 及之后的年份
 *    （不会让你选出一个"结束年比起始年还早"的无效范围）。
 *
 * 两个滚轮里的年份都是**从老到新**排（上面老、下面新），
 * 这样"从起始年往下滑就是结束年"的感觉更自然。
 */
@Composable
private fun YearRangePickerDialog(
    years: List<Int>,
    current: IntRange?,
    onPick: (IntRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 统一按"从老到新"排，方便按下标算范围（上面老、下面新）
    val asc = remember(years) { years.sorted() }

    // 起始年：用**下标**存（它对应的是 asc 里某个位置）
    val startIndexInit = remember {
        current?.first?.let { asc.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    }
    val startIndex = remember { mutableStateOf(startIndexInit) }

    // ⚠️ 结束年用**年份本身**存，不能用下标。
    //
    //    为什么：如果按下标存，左右两个轮子的下标含义不同，
    //    起始年一动，同一个下标就指向了**别的年份**，用户选好的结束年会悄悄变。
    //    存年份值就永远不会漂。
    val endYear = remember {
        mutableStateOf(
            current?.last ?: asc.last()
        )
    }

    val startYear = asc.getOrNull(startIndex.value) ?: asc.first()

    /**
     * **真正生效的结束年**（唯一可信来源）。
     *
     * 起始年一旦超过结束年，结束年就**等于起始年**（范围收窄成一格）。
     */
    val endYearNow = maxOf(endYear.value, startYear)

    /** 结束年在完整列表里的下标 */
    val endIndex = asc.indexOf(endYearNow).coerceAtLeast(0)

    // 把纠正后的值写回状态，保证后续操作基准一致
    LaunchedEffect(endYearNow) {
        if (endYear.value != endYearNow) endYear.value = endYearNow
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按年份筛选") },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    YearWheel(
                        label = "起始年份",
                        years = asc,
                        selectedIndex = startIndex.value,
                        onSelected = { startIndex.value = it },
                        modifier = Modifier.weight(1f),
                    )
                    YearWheel(
                        label = "结束年份",
                        // ⚠️ 传**完整**列表，不随起始年变化。
                        //    这样右边的内容永远不变，就不会出现"内容变了 → 再滚回来"的闪动。
                        //    早于起始年的那些年份由 minIndex 标记成"灰、不可选"。
                        years = asc,
                        selectedIndex = endIndex,
                        minIndex = startIndex.value,
                        onSelected = { i ->
                            asc.getOrNull(i.coerceIn(0, asc.lastIndex))
                                ?.let { endYear.value = it }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "当前范围：" + run {
                        val s = asc.getOrNull(startIndex.value)
                        when {
                            s == null -> "不限"
                            // 全选 = 不限，界面上也这么写，免得用户以为在筛选
                            s == asc.first() && endYearNow == asc.last() -> "不限（全部年份）"
                            else -> "$s – $endYearNow"
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = asc.getOrNull(startIndex.value)
                when {
                    s == null -> onPick(null)
                    // ⚠️ 起始选了最老、结束选了最新 = 等于没筛，按"不限"处理。
                    //    否则按钮上会显示一个和"不限"完全等价的年份范围，白占地方。
                    s == asc.first() && endYearNow == asc.last() -> onPick(null)
                    else -> onPick(s..endYearNow)
                }
            }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("不限") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 一个年份滚轮（中间高亮表示当前选中）。
 *
 * # ⚠️ 这是第 6 版，前面 5 版都没稳住
 *
 * 前几版用的是 `LazyColumn` + `rememberSnapFlingBehavior`，靠**几何计算**
 * （`visibleItemsInfo` 的 offset 和视口中线比）来判断"中间是哪一项"，结果接连出了：
 * 抖动、滑到顶端选不到最老的、延迟 0.5 秒、结束年被乱改……
 *
 * **根因是"同一件事有两个数据源"**：
 *  - 滚动位置（`LazyListState`，按下标记）
 *  - 选中的年份（父级状态，按值记）
 *
 * 这两者在下标位移、内容填充、惯性动画的边界情况下会互相矛盾，
 * 每次修好一个又冒出另一个。
 *
 * # ✅ 这一版的思路：只留一个数据源
 *
 * **不用 LazyColumn，改成一个普通的可滚动 Column。**
 * 于是"第几项在中间"可以直接**从滚动位置算出来**，不依赖任何布局几何：
 *
 * ```
 * 内容 = 2 行空白 + N 个年份 + 2 行空白        （空白为了让首尾项能停在正中）
 * 容器高 = 5 行
 * 滚动位置 value ∈ [0, (N-1)*行高]
 * 选中项   = round(value / 行高)               ← 就这么简单
 * ```
 *
 * 验证：
 *  - `value = 0` → 第 0 行正好在中间（上面留了 2 行空白）→ 选中第 0 项 ✓
 *  - `value = (N-1)*行高` → 第 N-1 行正好在中间 → 选中最后一项 ✓
 *  - 中间任意位置 → 四舍五入到最近的一行 ✓
 *
 * 这样就**没有 offset 约定、没有 padding 语义、没有下标位移**这些坑了。
 *
 * # 三个行为
 *
 * 1. **边滑边变**：选中项由 `scrollState.value` 派生，拖动时立刻跟着变（无延迟）
 * 2. **松手吸附**：滚动停下后，平滑地吸到最近的一行（看起来才像滚轮）
 * 3. **外部改选中**（比如右边列表因起始年变化而变化）：
 *    只在**列表内容变了**的时候把位置对过去，平时绝不主动滚动 → 不会和手势打架
 */
@Composable
private fun YearWheel(
    label: String,
    years: List<Int>,
    selectedIndex: Int,
    /**
     * 小于这个下标的项**灰掉、不可选**。
     *
     * 用途：结束年份不能早于起始年份。
     *
     * ⚠️ 为什么用"灰掉"而不是"从列表里删掉"：
     *    删掉的话列表内容会变 → 滚动位置的含义跟着变 →
     *    为了把选中项对回来又得滚一次 → **中间隔一帧，肉眼就是"闪一下"**。
     *    灰掉则内容永远不变，**结构上就不可能闪**。
     */
    minIndex: Int = 0,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 内容：2 行空白 + N 个年份 + 2 行空白；容器高 5 行；
    // 选中项 = round(滚动位置 / 行高)
    val itemHeight = 48.dp
    val lastIndex = (years.size - 1).coerceAtLeast(0)
    val safeIndex = selectedIndex.coerceIn(0, lastIndex)
    val minIndexSafe = minIndex.coerceIn(0, lastIndex)

    // 行高的像素值（算选中项要用）
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }

    // 滚动的唯一数据源。初始位置直接按"选中项 × 行高"给，不需要事后再滚。
    val scrollState = rememberScrollState(initial = (safeIndex * itemHeightPx).toInt())

    // 让派生状态读到最新的值
    val lastIndexNow by rememberUpdatedState(lastIndex)
    val itemHeightNow by rememberUpdatedState(itemHeightPx)
    val minIndexNow by rememberUpdatedState(minIndexSafe)

    /** **实时**位置对应第几项（拖动时就跟着变，没有延迟）—— 用于高亮 */
    val rawCenter by remember {
        derivedStateOf {
            (scrollState.value / itemHeightNow).roundToInt().coerceIn(0, lastIndexNow)
        }
    }

    /** **实际选中**的项 = 实时位置，但不能小于 [minIndex] —— 用于上报 */
    val currentCenter = maxOf(rawCenter, minIndexNow)

    // ---- 1. 选中项一变就上报（这是"无延迟"的关键）----
    val onSelectedNow by rememberUpdatedState(onSelected)
    LaunchedEffect(scrollState) {
        snapshotFlow { maxOf(rawCenter, minIndexNow) }
            .distinctUntilChanged()
            .collect { onSelectedNow(it) }
    }

    // ---- 2. 松手后吸附到最近的一行（只在滚动停下时做一次）----
    // 若停在"灰色不可选"的区域，会平滑地弹回最近的合法项。
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val target = maxOf(rawCenter, minIndexNow) * itemHeightNow
                    if (kotlin.math.abs(scrollState.value - target) > 1f) {
                        scrollState.animateScrollTo(target.roundToInt())
                    }
                }
            }
    }

    // ---- 3. 外部改了选中项（比如起始年超过结束年，结束年被抬到起始年）----
    //        平滑滚过去。用户正在拖的时候**跳过**，绝不抢手势。
    LaunchedEffect(safeIndex) {
        if (scrollState.isScrollInProgress) return@LaunchedEffect
        val target = (safeIndex * itemHeightPx).roundToInt()
        if (kotlin.math.abs(scrollState.value - target) > itemHeightPx / 2f) {
            scrollState.animateScrollTo(target)
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier.fillMaxWidth().height(itemHeight * 5),
            contentAlignment = Alignment.Center,
        ) {
            // 中间高亮条（在滚动内容下面，不影响手势）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp),
                    ),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                // 上留白：2 行，让第 0 项能停在正中
                Spacer(Modifier.height(itemHeight * 2))

                years.forEachIndexed { index, year ->
                    // 灰掉的是"不能选"的（比如结束年不能早于起始年）
                    val isDisabled = index < minIndexSafe
                    val isCenter = index == rawCenter
                    Box(
                        modifier = Modifier.fillMaxWidth().height(itemHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$year",
                            fontSize = if (isCenter) 20.sp else 16.sp,
                            fontWeight = if (isCenter && !isDisabled) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            color = when {
                                isDisabled -> MaterialTheme.colorScheme.outlineVariant
                                isCenter -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                // 下留白：2 行，让最后一项也能停在正中
                Spacer(Modifier.height(itemHeight * 2))
            }
        }
    }
}

// ============================================================
//  页面三：全屏看大图
// ============================================================

/**
 * 全屏看图。
 *
 * 支持：双指缩放、双击放大/复位、放大后拖动看细节、单击隐藏信息与状态栏。
 *
 * 三个关键的坑（都是踩出来的）：
 *  1. **单击和双击必须写在同一个 detectTapGestures 里**，
 *     拆成两个的话第一个会消费事件，双击永远收不到。
 *  2. **手势必须排在 graphicsLayer 前面**，
 *     否则手势收到的是"放大后"的坐标，拖动会变成手指位移除以缩放倍数。
 *  3. **放大时要限制拖动范围**，否则能把图片拖出屏幕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenViewer(
    photos: List<PhotoEntity>,
    initialIndex: Int,
    onClose: () -> Unit,
    /**
     * 点右上角"补充文字"时回调，把当前这张照片交出去。
     *
     * 四个界面（浏览 / 文字搜索 / AI 搜索 / AI 结果）都传这个，
     * 所以"任何有缩略图的界面都能补文字"是自动成立的。
     */
    onEditText: (PhotoEntity) -> Unit,
) {
    val index = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    var chromeVisible by remember { mutableStateOf(true) }

    // 控制手机状态栏。
    // 不能直接 `view.context as Activity`：Compose 的 context 常被包了一层，
    // 强转会得到 null，状态栏就隐藏不了。要一层层往里剥。
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val insetsController = remember(activity, view) {
        activity?.let {
            WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
            insetsController?.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            insetsController?.hide(WindowInsetsCompat.Type.statusBars())
            insetsController?.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    // 离开页面时把状态栏还回来，否则图片列表页也没有状态栏
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
            insetsController?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    BackHandler { onClose() }

    val current = photos.getOrNull(index)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        current?.let { photo ->
            ZoomableImage(
                uri = photo.uri,
                contentDescription = photo.name,
                aspect = if (photo.height > 0) photo.width.toFloat() / photo.height.toFloat() else 1f,
                onSingleTap = { chromeVisible = !chromeVisible },
            )
        }

        // 顶部信息（让开状态栏，不重叠）
        if (chromeVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current?.name ?: "",
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("${index + 1} / ${photos.size}")
                            current?.let { p ->
                                // GIF 特别标出来，因为它是会动的图
                                if (p.fileKind == FileKind.GIF) append("　GIF")
                                append("　${p.resolutionTier.label}")
                                append("　${p.width}×${p.height}")
                                append("　${formatDate(p.dateMillis)}")
                            }
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                    )
                }
                // 右上角：补充/修正文字（任何图都能用，GIF 也行）
                current?.let { p ->
                    IconButton(onClick = { onEditText(p) }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "补充文字",
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        // 底部：识别出的文字，或操作提示
        if (chromeVisible && current != null && current.hasRecognizedText) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = if (!current.userText.isNullOrBlank()) "图中文字（已改）" else "图中文字",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 用 recognizedText（不含文件名），否则会把文件名当成识别结果显示
                        text = current.recognizedText,
                        fontSize = 13.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        } else if (chromeVisible) {
            Text(
                text = "单击隐藏信息　·　双击缩放　·　拖动手动调整",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/**
 * 可缩放、可拖动、可单击的图片。
 *
 * 关键点：
 *  1. 位移用普通状态 + 手动逐帧处理手势（不用库的"累计位移"，那会重复累加导致拖动很慢）
 *  2. 必须有边界限制，否则能拖出屏幕
 *  3. 单击和双击写在同一个检测器里
 *  4. 缩放上限 4 倍（倍数越大 GPU 每帧画的像素越多、越卡）
 *
 * @param aspect 照片本身的宽高比，用来算拖动边界（不依赖加载后的图片尺寸）
 */
@Composable
private fun ZoomableImage(
    uri: String,
    contentDescription: String?,
    aspect: Float,
    onSingleTap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 缩放用 Animatable：双击时能平滑过渡
    val scale = remember { Animatable(1f) }
    // 位移用普通状态：同步赋值，跟手
    var offset by remember { mutableStateOf(Offset.Zero) }

    var container by remember { mutableStateOf(IntSize(0, 0)) }
    val onTapNow by rememberUpdatedState(onSingleTap)

    // 限定解码尺寸，避免把 4000×3000 原图丢给 GPU 每帧重绘
    val request = remember(uri, container) {
        var builder = ImageRequest.Builder(context).data(uri)
        if (container.width > 0 && container.height > 0) {
            builder = builder.size(container.width, container.height)
        }
        builder.build()
    }

    /**
     * 允许的最大位移。
     * 用照片的宽高比算显示尺寸 —— 因为图片已被缩小到屏幕大小，
     * 再用缩小后的尺寸算边界，会导致放大后反而拖不动。
     */
    fun boundLimit(): Offset {
        if (container.width == 0 || container.height == 0) return Offset.Zero
        val s = scale.value
        val wRatio = container.width.toFloat()
        val hRatio = container.height.toFloat() * aspect
        val fit = if (wRatio < hRatio) wRatio else hRatio
        val shownW = fit * s
        val shownH = (fit / aspect) * s
        return Offset(
            x = ((shownW - container.width) / 2f).coerceAtLeast(0f),
            y = ((shownH - container.height) / 2f).coerceAtLeast(0f),
        )
    }

    fun clampOffset(raw: Offset, limit: Offset): Offset = Offset(
        x = raw.x.coerceIn(-limit.x, limit.x),
        y = raw.y.coerceIn(-limit.y, limit.y),
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { container = it }
                // 手势必须放在 graphicsLayer 前面，否则手势坐标会被缩放倍数"除一遍"
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)

                        var lastX = 0f
                        var lastY = 0f
                        var haveLast = false
                        var multiTouch = false

                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) break

                            if (event.changes.count { it.pressed } >= 2) multiTouch = true

                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f) {
                                scope.launch {
                                    scale.snapTo((scale.value * zoomChange).coerceIn(1f, 4f))
                                }
                            }

                            val primary = event.changes.firstOrNull() ?: break
                            val panX: Float
                            val panY: Float
                            if (!haveLast) {
                                panX = 0f
                                panY = 0f
                                lastX = primary.position.x
                                lastY = primary.position.y
                                haveLast = true
                            } else {
                                panX = primary.position.x - lastX
                                panY = primary.position.y - lastY
                                lastX = primary.position.x
                                lastY = primary.position.y
                            }

                            // 只有已放大或正在捏合才动图片；
                            // 没放大时不消费事件，让单击/双击能正常识别。
                            if (multiTouch || scale.value > 1.01f) {
                                offset = clampOffset(
                                    Offset(offset.x + panX, offset.y + panY),
                                    boundLimit(),
                                )
                                event.changes.forEach { it.consume() }
                            }
                        }

                        // 抬手收尾
                        if (scale.value <= 1.01f) {
                            offset = Offset.Zero
                        } else {
                            offset = clampOffset(offset, boundLimit())
                        }
                    }
                }
                // 单击 + 双击必须在同一个检测器里，拆开会让双击收不到
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTapNow() },
                        onDoubleTap = {
                            scope.launch {
                                if (scale.value > 1.01f) {
                                    scale.animateTo(1f, tween(200))
                                    offset = Offset.Zero
                                } else {
                                    scale.animateTo(3f, tween(200))
                                    offset = clampOffset(offset, boundLimit())
                                }
                            }
                        },
                    )
                }
                // graphicsLayer 放在手势之后
                .graphicsLayer(
                    scaleX = scale.value,
                    scaleY = scale.value,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

/** 从 Compose 的 context 里剥出真正的 Activity（控制状态栏要用） */
private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

// ============================================================
//  权限页
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionScreen(askedBefore: Boolean, onRequest: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("相册找图") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("需要相册权限", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            Text("用于列出并读取相册图片。", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "请在弹窗中选择「允许」或「允许全部」。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(if (askedBefore) "重新申请" else "申请权限")
            }

            Spacer(Modifier.height(16.dp))
            if (askedBefore) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "点了没反应？可能是被永久拒绝了",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "设置 → 应用 → 相册找图 → 权限 → 照片和视频，" +
                                "选「允许」或「允许全部」，再回来。",
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
//  小工具
// ============================================================

/** 提示卡片（目前只用于"仅授权部分照片"的提醒） */
@Composable
private fun InfoCard(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(text = body, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAction) { Text(actionText, fontSize = 12.sp) }
        }
    }
}

/**
 * 纯文字形式的可点击项（蓝色，让人一眼看出"这是能点的"）。
 *
 * 用途：弹窗里让用户选"用哪种方式识别"。
 * 和 [TierOption] 的区别：那个前面有个 ○/● 表示"单选"，
 * 这里不需要选中标记，所以要看起来像按钮。
 */
@Composable
private fun ActionText(label: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 「按图片内容搜索」的入口。
 *
 * 形状和「搜索图片」「浏览图片」一样（OutlinedButton），
 * 文字**居中**、**加粗**、**蓝色** —— 表明这是另一条完全不同的搜索线路（云端 AI）。
 */
/**
 * 「按图片内容搜索」的入口（放在识别页最底部，做得**醒目**一些）。
 *
 * 用户要求：放在屏幕下方、**按钮放大一些、更明显**。
 * 所以用实心按钮（不是描边）、加高到 56dp、字号 17sp，
 * 并且下面留一点边距，让它和屏幕边缘不贴死。
 */
@Composable
private fun AiSearchEntry(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(bottom = 0.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = "按图片内容搜索",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * 「补充 / 修正文字」弹窗（阶段 4）。
 *
 * ## 用户要求（2026-10）
 * > 用户在任何界面，只要是有缩略图列表，点开大图后，都可以在大图详细信息出现的时候，
 * > 点击右上角的补充文字按钮，进行文字补充，**不限字数**。
 * > **任何图片都可以，GIF 也可以**。
 * > 同时**软件扫描出来的文字也可以在文字补充界面里进行修改**，
 * > **没有扫描出文字的图片直接显示空白**就行。
 *
 * ## 实现要点
 *  - **预填内容** = [PhotoEntity.textForEditing]
 *    （改过的显示改过的；没改过但有识别结果显示识别的；都没有就是空白 → 满足"空白"要求）
 *  - **不限字数**：`maxLines` 给足，并且整块可以滚动
 *  - **GIF 也能用**：这里只处理文字，和图片格式无关
 *  - **保存 = 覆盖机器结果**（见 [PhotoEntity.effectiveText]），
 *    所以把机器认错的字改掉之后，错的那些字**不会再被搜到**
 *  - **清空保存 = 明确"这张图没字"**（不会回退到机器文字）
 *    ⚠️ 这点踩过 bug：一开始做成"清空=恢复机器文字"，
 *    结果用户**永远删不掉**机器认错的文字 —— 删完又变回来了。
 *  - **想恢复机器文字** → 点「恢复机器识别结果」按钮（只有改过之后才出现）
 */
@Composable
private fun TextEditDialog(
    photo: PhotoEntity,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
    onRestoreMachine: () -> Unit,
) {
    // 预填当前生效的文字（没有就是空）
    var draft by remember(photo.id) { mutableStateOf(photo.textForEditing) }

    val hasMachineText = !photo.ocrText.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("补充 / 修正文字") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = buildString {
                        append(photo.name)
                        if (photo.fileKind == FileKind.GIF) append("（GIF）")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },   // 不限字数
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = {
                        Text(
                            "写下这张图里有什么字，方便以后搜到它",
                            fontSize = 13.sp,
                        )
                    },
                    // 长文本可以滚动查看
                    minLines = 6,
                    maxLines = 14,
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("${draft.length} 字")
                        if (draft.isEmpty() && hasMachineText) {
                            // 明确告诉用户"清空"是什么意思，免得以为是恢复机器文字
                            append("　·　保存后这张图就当作没文字")
                        }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )

                // 用户改过之后，才给"恢复机器识别"的出口
                if (photo.hasUserText && hasMachineText) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onRestoreMachine,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("恢复机器识别结果", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

/** 取出某个时间戳的年份 */
private fun yearOf(millis: Long): Int {
    if (millis <= 0L) return 0
    return Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
}

/** 毫秒时间戳 → "年-月-日" */
private fun formatDate(millis: Long): String {
    if (millis <= 0L) return "时间未知"
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val y = c.get(Calendar.YEAR)
    val m = c.get(Calendar.MONTH) + 1
    val d = c.get(Calendar.DAY_OF_MONTH)
    return "$y-" + (if (m < 10) "0$m" else "$m") + "-" + (if (d < 10) "0$d" else "$d")
}
