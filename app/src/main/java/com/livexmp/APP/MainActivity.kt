package com.livexmp.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.util.Log
import java.util.ArrayList
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// 用于关于弹窗的页面枚举
enum class AboutPage { Main, Privacy, Permissions }

/**
 * LiveXMP 主界面 Activity
 * 采用现代化的 Compose UI 架构。
 * 核心功能：负责文件选择、状态展示、设置配置，并启动后台合并服务。
 */
class MainActivity : AppCompatActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 核心代码：开启边缘到边缘（Edge-to-Edge）支持，使内容流向屏下的通知栏和手势区域
        enableEdgeToEdge()

        // 核心代码：配置隐藏系统的状态栏和导航栏（实现纯净的全屏沉浸式体验）
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            val context = LocalContext.current
            
            // MD3 动态取色主题支持（Android 12+ 会根据壁纸颜色自动变换主题色）
            val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val colorScheme = when {
                dynamicColor && isSystemInDarkTheme() -> dynamicDarkColorScheme(context)
                dynamicColor && !isSystemInDarkTheme() -> dynamicLightColorScheme(context)
                isSystemInDarkTheme() -> darkColorScheme()
                else -> lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MuxerScreen() // 进入主屏幕 Composable
                }
            }
        }
    }

    /**
     * 主界面主体组合函数
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MuxerScreen() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        // --- 核心状态管理 ---
        // inputUris: 存储用户通过文件或文件夹选择器添加的所有文件 URI 列表
        var inputUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        // fileNames: 缓存解析出的文件名，键为 Uri，值为文件名。用于在列表中高效展示。
        val fileNames = remember { mutableStateMapOf<Uri, String>() }
        
        // 辅助：当 inputUris 变化时自动清理不再需要的缓存，并解析新加入的文件名
        LaunchedEffect(inputUris) {
            // 清理缓存中已不存在于 inputUris 的项
            val currentUris = inputUris.toSet()
            val keysToRemove = fileNames.keys.filter { it !in currentUris }
            keysToRemove.forEach { fileNames.remove(it) }
            
            // 解析新项（如果尚未在缓存中）
            inputUris.forEach { uri ->
                if (!fileNames.containsKey(uri)) {
                    // 使用 DocumentFile 解析文件名
                    val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "Unknown"
                    fileNames[uri] = name
                }
            }
        }

        // 移除单项文件的逻辑
        fun removeFile(uri: Uri) {
            inputUris = inputUris.filter { it != uri }
        }

        // outputUri: 存储用户指定的合并结果输出文件夹 URI
        var outputUri by remember { mutableStateOf<Uri?>(null) }
        
        // 高级设置项的状态
        var moveUnmatched by remember { mutableStateOf(false) }
        var forceConvertHeicToJpg by remember { mutableStateOf(false) }
        var deleteConvertedHeic by remember { mutableStateOf(false) }
        var deleteOriginalOnSuccess by remember { mutableStateOf(false) }

        // 订阅后台服务的 Flow 状态，实现跨进程进度实时刷新
        val muxState by MuxerService.state.collectAsState()
        
        // 弹窗控制状态
        var showAboutDialog by remember { mutableStateOf(false) }
        var showVideoOnlyDialog by remember { mutableStateOf(false) }
        var orphanVideosCount by remember { mutableIntStateOf(0) }
        var isSettingsExpanded by remember { mutableStateOf(false) }
        var alwaysProcessOrphans by remember { mutableStateOf<Boolean?>(null) }
        var rememberChoice by remember { mutableStateOf(false) }

        // --- SAF (Storage Access Framework) 启动器配置 ---
        
        // 文件多选启动器：用户选择文件后，增量添加至 inputUris 并去重
        val inputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                uris.forEach { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                inputUris = (inputUris + uris).distinct() 
            }
        }

        // 文件夹解析辅助函数：遍历用户选中的文件夹，并提取其中的媒体文件
        fun parseFolderUris(treeUri: Uri) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
            val allowedExts = setOf("jpg", "jpeg", "heic", "mov", "mp4")
            val discoveredUris = root.listFiles()
                .filter { it.isFile && it.name?.substringAfterLast(".")?.lowercase() in allowedExts }
                .map { it.uri }
            
            if (discoveredUris.isNotEmpty()) {
                inputUris = (inputUris + discoveredUris).distinct()
            }
        }

        // 文件夹选择启动器
        val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                parseFolderUris(it)
            }
        }

        // 输出目录选择启动器
        val outputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                // 获取持久化权限，确保应用下次启动依然能直接访问该目录
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                outputUri = it
            }
        }

        /**
         * 真正调用后台服务的逻辑
         */
        fun startMuxing(forceVideoToLive: Boolean) {
            Log.d("LiveXMP", "startMuxing called, forceVideoToLive: $forceVideoToLive")
            if (inputUris.isEmpty() || outputUri == null) {
                Log.w("LiveXMP", "startMuxing aborted: inputs or output empty")
                Toast.makeText(context, R.string.error_select_path, Toast.LENGTH_SHORT).show()
                return
            }
            // 构造 Intent 将所有 UI 配置发送给 MuxerService
            val intent = Intent(context, MuxerService::class.java).apply {
                putParcelableArrayListExtra("inputUris", ArrayList(inputUris))
                putExtra("outputDirUri", outputUri.toString())
                putExtra("moveUnmatched", moveUnmatched)
                putExtra("forceConvertHeicToJpg", forceVideoToLive)
                putExtra("deleteConvertedHeic", deleteConvertedHeic)
                putExtra("deleteOriginalOnSuccess", deleteOriginalOnSuccess)
            }
            // 启动前台服务
            context.startForegroundService(intent)
        }

        /**
         * 点击“开始合并”时的预检流程
         */
        suspend fun performPreScanAndStart() {
            Log.d("LiveXMP", "performPreScanAndStart started, input size: ${inputUris.size}")
            if (inputUris.isEmpty() || outputUri == null) {
                Log.w("LiveXMP", "performPreScanAndStart aborted: empty paths")
                Toast.makeText(context, R.string.error_select_path, Toast.LENGTH_SHORT).show()
                return
            }
            // 调用 Muxer 进行极速预扫描，检查有多少个视频找不到图片
            val scanResult = MotionPhotoMuxer.preScan(context, inputUris)
            Log.d("LiveXMP", "Scan Result: pairs=${scanResult.pairsCount}, orphans=${scanResult.orphansCount}")
            
            if (scanResult.pairsCount == 0 && scanResult.orphansCount == 0) {
                Log.i("LiveXMP", "No valid pairs or orphans found for processing.")
                Toast.makeText(context, "未发现可匹配的图片/视频组，请检查文件名是否一致", Toast.LENGTH_LONG).show()
                return
            }
            
            // 如果存在只有视频没有图片的“孤儿视频”，弹出询问窗口
            if (scanResult.orphansCount > 0 && alwaysProcessOrphans == null) {
                orphanVideosCount = scanResult.orphansCount
                showVideoOnlyDialog = true
            } else {
                // 如果用户之前已经选择了，或者不存在孤儿视频，直接开始
                startMuxing(forceVideoToLive = alwaysProcessOrphans ?: false)
            }
        }

        // --- UI 布局代码 ---
        BoxWithConstraints {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val isLargeScreen = maxWidth > 600.dp // 简单适配大屏/折叠屏
            val showSideBySide = isLandscape || isLargeScreen

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                // 右下角的浮动操作按钮（FAB），作为核心启动入口
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { 
                            Log.d("LiveXMP", "FAB Clicked")
                            Toast.makeText(context, "正在检查文件...", Toast.LENGTH_SHORT).show()
                            coroutineScope.launch { 
                                try {
                                    performPreScanAndStart()
                                } catch (e: Exception) {
                                    Log.e("LiveXMP", "Error in performPreScanAndStart", e)
                                    Toast.makeText(context, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } 
                        },
                        expanded = !muxState.isProcessing,
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        text = { Text(stringResource(R.string.start_muxing), style = MaterialTheme.typography.titleMedium) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                },
                floatingActionButtonPosition = FabPosition.End
            ) { padding ->
                val contentModifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding) // 消耗 Scaffold 内边距
                    .safeDrawingPadding()       // 核心：防止界面内容顶进状态栏或底部的动作栏
                    .fillMaxSize()
                    .padding(16.dp)

                if (showSideBySide) {
                    // 横屏或大屏模式：采用高效的双分栏布局
                    Row(modifier = contentModifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(modifier = Modifier.weight(0.4f).verticalScroll(rememberScrollState())) {
                            StepOneCard(
                                inputUris = inputUris,
                                fileNames = fileNames,
                                outputUri = outputUri,
                                onAddFiles = { inputLauncher.launch(arrayOf("image/*", "video/*")) },
                                onAddFolder = { folderLauncher.launch(null) },
                                onClearInput = { inputUris = emptyList() },
                                onRemoveFile = { removeFile(it) },
                                onOutputClick = { outputLauncher.launch(null) },
                                onAboutClick = { showAboutDialog = true }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            AdvancedSettingsCard(
                                expanded = isSettingsExpanded,
                                onToggle = { isSettingsExpanded = it },
                                moveUnmatched = moveUnmatched,
                                onMoveUnmatchedChange = { moveUnmatched = it },
                                forceConvertHeicToJpg = forceConvertHeicToJpg,
                                onForceConvertChange = { forceConvertHeicToJpg = it },
                                deleteConvertedHeic = deleteConvertedHeic,
                                onDeleteConvertedChange = { deleteConvertedHeic = it },
                                deleteOriginalOnSuccess = deleteOriginalOnSuccess,
                                onDeleteOriginalChange = { deleteOriginalOnSuccess = it }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.6f)
                                .fillMaxHeight()
                                .padding(bottom = 72.dp) // 为右下角 FAB 留出空间
                        ) {
                            ProgressAndLogSection(muxState, modifier = Modifier.fillMaxSize())
                        }
                    }
                } else {
                    // 竖屏模式：控制面板独占空间，隐藏冗余的终端日志列表，仅保留进度条
                    Column(
                        modifier = contentModifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StepOneCard(
                            inputUris = inputUris,
                            fileNames = fileNames,
                            outputUri = outputUri,
                            onAddFiles = { inputLauncher.launch(arrayOf("image/*", "video/*")) },
                            onAddFolder = { folderLauncher.launch(null) },
                            onClearInput = { inputUris = emptyList() },
                            onRemoveFile = { removeFile(it) },
                            onOutputClick = { outputLauncher.launch(null) },
                            onAboutClick = { showAboutDialog = true }
                        )
                        AdvancedSettingsCard(
                            expanded = isSettingsExpanded,
                            onToggle = { isSettingsExpanded = it },
                            moveUnmatched = moveUnmatched,
                            onMoveUnmatchedChange = { moveUnmatched = it },
                            forceConvertHeicToJpg = forceConvertHeicToJpg,
                            onForceConvertChange = { forceConvertHeicToJpg = it },
                            deleteConvertedHeic = deleteConvertedHeic,
                            onDeleteConvertedChange = { deleteConvertedHeic = it },
                            deleteOriginalOnSuccess = deleteOriginalOnSuccess,
                            onDeleteOriginalChange = { deleteOriginalOnSuccess = it }
                        )
                        // 在竖屏底部仅显示关键进度卡片，不显示日志终端
                        ProgressStatsCard(muxState)
                        
                        // 为 FAB 留点底部边距
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }

        // --- 各种提示对话框组件 ---

        // 孤儿视频处理询问框
        if (showVideoOnlyDialog) {
            AlertDialog(
                onDismissRequest = { showVideoOnlyDialog = false },
                title = { Text(stringResource(R.string.dialog_orphan_title, orphanVideosCount)) },
                text = {
                    Column {
                        Text(stringResource(R.string.dialog_orphan_text))
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                            Text(stringResource(R.string.dialog_remember_choice), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (rememberChoice) alwaysProcessOrphans = true
                        showVideoOnlyDialog = false
                        startMuxing(forceVideoToLive = true)
                    }) { Text(stringResource(R.string.btn_convert_all)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (rememberChoice) alwaysProcessOrphans = false
                        showVideoOnlyDialog = false
                        startMuxing(forceVideoToLive = false)
                    }) { Text(stringResource(R.string.btn_only_pairs)) }
                }
            )
        }

        if (showAboutDialog) {
            AboutDialog(onDismiss = { showAboutDialog = false })
        }
    }

    /**
     * 合并后的步骤化选择卡片
     * 集成了第一步（输入）和第二步（输出），并带有文件列表回显
     */
    @Composable
    fun StepOneCard(
        inputUris: List<Uri>, 
        fileNames: Map<Uri, String>,
        outputUri: Uri?, 
        onAddFiles: () -> Unit, 
        onAddFolder: () -> Unit,
        onClearInput: () -> Unit,
        onRemoveFile: (Uri) -> Unit,
        onOutputClick: () -> Unit,
        onAboutClick: () -> Unit
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // --- 顶部标题栏 ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        if (inputUris.isNotEmpty()) {
                            IconButton(onClick = onClearInput) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.btn_clear_list), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    IconButton(onClick = onAboutClick) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about_app), tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)
                
                // --- 第一步：输入路径 ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.step_one), style = MaterialTheme.typography.titleMedium)
                    if (inputUris.isNotEmpty()) {
                        Text(
                            stringResource(R.string.selected_files_count, inputUris.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 输入按钮
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddFiles, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_add_files), maxLines = 1)
                    }
                    OutlinedButton(onClick = onAddFolder, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.CreateNewFolder, null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_add_folder), maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 已选文件列表区域
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), MaterialTheme.shapes.small)
                        .padding(8.dp)
                ) {
                    if (inputUris.isEmpty()) {
                        Text(
                            stringResource(R.string.empty_file_list),
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(inputUris.size) { index ->
                                val uri = inputUris[index]
                                SelectedFileItem(
                                    name = fileNames[uri] ?: "...",
                                    onRemove = { onRemoveFile(uri) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // --- 第二步：输出路径 ---
                Text(stringResource(R.string.step_two), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedButton(
                    onClick = onOutputClick, 
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (outputUri != null) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Icon(if (outputUri != null) Icons.Default.FolderSpecial else Icons.Default.CreateNewFolder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = outputUri?.lastPathSegment ?: stringResource(R.string.select_output),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    /**
     * 列表中单个文件的显示条目
     */
    @Composable
    fun SelectedFileItem(name: String, onRemove: () -> Unit) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Close, 
                        contentDescription = stringResource(R.string.btn_remove),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    /**
     * 高级配置卡片
     */
    @Composable
    fun AdvancedSettingsCard(
        expanded: Boolean, 
        onToggle: (Boolean) -> Unit,
        moveUnmatched: Boolean, onMoveUnmatchedChange: (Boolean) -> Unit,
        forceConvertHeicToJpg: Boolean, onForceConvertChange: (Boolean) -> Unit,
        deleteConvertedHeic: Boolean, onDeleteConvertedChange: (Boolean) -> Unit,
        deleteOriginalOnSuccess: Boolean, onDeleteOriginalChange: (Boolean) -> Unit
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.config_options), style = MaterialTheme.typography.titleMedium) },
                    trailingContent = {
                        IconButton(onClick = { onToggle(!expanded) }) {
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    },
                    modifier = Modifier.clickable { onToggle(!expanded) }
                )
                // 折叠动画展开内容
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp), thickness = 0.5.dp)
                        SwitchItem(stringResource(R.string.setting_move_unmatched), moveUnmatched, onMoveUnmatchedChange)
                        SwitchItem(stringResource(R.string.setting_force_convert), forceConvertHeicToJpg, onForceConvertChange)
                        SwitchItem(stringResource(R.string.setting_delete_temp), deleteConvertedHeic, onDeleteConvertedChange)
                        SwitchItem(stringResource(R.string.setting_delete_original), deleteOriginalOnSuccess, onDeleteOriginalChange)
                    }
                }
            }
        }
    }

    /**
     * 整合进度和日志的控制台分段部件
     */
    @Composable
    fun ProgressAndLogSection(muxState: MuxState, modifier: Modifier = Modifier) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressStatsCard(muxState) // 进度卡片
            // 日志终端：确保在分栏布局下能支撑起垂直空间
            LogTerminal(
                log = muxState.log, 
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    /**
     * 进度详情卡片
     */
    @Composable
    fun ProgressStatsCard(muxState: MuxState) {
        // 如果当前没有任务且不在处理中，不渲染该卡片以节省空间
        if (!muxState.isProcessing && muxState.total == 0) return
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.title_process_progress), style = MaterialTheme.typography.labelLarge)
                    Text("${muxState.current} / ${muxState.total}", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 计算进度百分比
                val progress = if (muxState.total > 0) muxState.current.toFloat() / muxState.total else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                // 成功/失败统计的小标签
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(stringResource(R.string.status_success, muxState.success)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF81C784).copy(alpha = 0.8f))
                    )
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(stringResource(R.string.status_fail, muxState.fail)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFE57373).copy(alpha = 0.8f))
                    )
                }
            }
        }
    }

    /**
     * 实现类似黑客终端风格的实时日志查看器
     */
    @Composable
    fun LogTerminal(log: String, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF1E1E1E), // 经典终端深灰背景
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            val scrollState = rememberScrollState()
            // 自动滚动逻辑：每当日志更新，滚动条自动滑到底部
            LaunchedEffect(log) { scrollState.animateScrollTo(scrollState.maxValue) }
            Column(modifier = Modifier.padding(12.dp).verticalScroll(scrollState)) {
                Text(
                    text = log.ifEmpty { stringResource(R.string.log_ready) },
                    color = Color(0xFF4CAF50), // 终端绿字体色
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                )
            }
        }
    }

    /**
     * 关于、隐私与权限说明对话框
     */
    @Composable
    fun AboutDialog(onDismiss: () -> Unit) {
        var currentPage by remember { mutableStateOf(AboutPage.Main) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentPage != AboutPage.Main) {
                        IconButton(onClick = { currentPage = AboutPage.Main }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    }
                    Text(when(currentPage) {
                        AboutPage.Main -> stringResource(R.string.dialog_about_title)
                        AboutPage.Privacy -> stringResource(R.string.dialog_privacy_title)
                        AboutPage.Permissions -> stringResource(R.string.dialog_permissions_title)
                    })
                }
            },
            text = {
                Column {
                    when(currentPage) {
                        AboutPage.Main -> {
                            Text(stringResource(R.string.developer_info))
                            Text(stringResource(R.string.project_usage), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.dialog_privacy_title)) },
                                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                                modifier = Modifier.clickable { currentPage = AboutPage.Privacy }
                            )
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.dialog_permissions_title)) },
                                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                                modifier = Modifier.clickable { currentPage = AboutPage.Permissions }
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.app_version), style = MaterialTheme.typography.labelSmall)
                        }
                        AboutPage.Privacy -> Text(stringResource(R.string.privacy_content), style = MaterialTheme.typography.bodyMedium)
                        AboutPage.Permissions -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.perm_folder_access), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.perm_bg_service), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.perm_vibrate), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_confirm)) } }
        )
    }

    /**
     * 通用的开关选择行组件
     */
    @Composable
    fun SwitchItem(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
