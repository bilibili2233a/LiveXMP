package com.LiveXMP.APP

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * LiveXMP 主界面 Activity
 * 采用 Jetpack Compose 构建，支持 Material Design 3 现代界面布局。
 */
class MainActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置 Compose UI 内容
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background // 遵循系统的主题背景色
                ) {
                    MuxerScreen() // 渲染核心功能界面
                }
            }
        }
    }

    /**
     * MuxerScreen: 包含路径选择、选项配置及任务执行的核心界面逻辑
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MuxerScreen() {
        // Compose 环境上下文
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope() // 用于在 UI 点击事件中启动后台协程

        // 状态管理：输入、输出文件夹 URI
        var inputUri by remember { mutableStateOf<Uri?>(null) }
        var outputUri by remember { mutableStateOf<Uri?>(null) }

        // 状态管理：各功能开关标识
        var moveUnmatched by remember { mutableStateOf(false) }
        var forceConvertHeicToJpg by remember { mutableStateOf(false) }
        var deleteConvertedHeic by remember { mutableStateOf(false) }
        var deleteOriginalOnSuccess by remember { mutableStateOf(false) }

        // 状态管理：任务处理进度
        var progress by remember { mutableStateOf(0f) }
        var progressText by remember { mutableStateOf("") }
        var isProcessing by remember { mutableStateOf(false) }
        
        // 日志显示状态
        var logText by remember { mutableStateOf("准备就绪。\n") }

        /**
         * 输入文件夹选择启动器 (使用 SAF 系统选择器)
         */
        val inputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                // 向系统申请持久化目录访问权限 (防止重启后失效)
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                inputUri = it
            }
        }

        /**
         * 输出文件夹选择启动器
         */
        val outputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                outputUri = it
            }
        }

        /**
         * 用于在日志文本框追加内容的辅助函数
         */
        fun appendLog(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logText += "[$time] $msg\n"
        }

        // Scaffold 基础支架布局
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("实况照片合成器 (LiveXMP)") },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()), // 支持长内容滚动
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // [第一步]：路径选择区域
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("第一步：选择路径", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(onClick = { inputLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (inputUri != null) "已选择输入: ${inputUri?.lastPathSegment}" else "选择输入文件夹")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(onClick = { outputLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (outputUri != null) "已选择输出: ${outputUri?.lastPathSegment}" else "选择输出文件夹")
                        }
                    }
                }

                // [第二步]：选项配置区域 (通过自定义 SwitchItem 渲染)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("第二步：配置选项", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))

                        SwitchItem("移动不匹配的文件", moveUnmatched) { moveUnmatched = it }
                        SwitchItem("强制转换所有 HEIC 为 JPG", forceConvertHeicToJpg) { forceConvertHeicToJpg = it }
                        SwitchItem("合并后删除转换过的 HEIC", deleteConvertedHeic) { deleteConvertedHeic = it }
                        SwitchItem("合并成功后删除原始文件", deleteOriginalOnSuccess) { deleteOriginalOnSuccess = it }
                    }
                }

                // [第三步]：合并进度显示
                if (isProcessing || progress > 0f) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("处理进度", style = MaterialTheme.typography.labelLarge)
                            Text(progressText, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    }
                }

                // 日志回显文本框
                OutlinedTextField(
                    value = logText,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("处理日志...") }
                )

                // [第四步]：执行合并操作的按钮
                Button(
                    onClick = {
                        if (inputUri != null && outputUri != null) {
                            isProcessing = true
                            progress = 0f
                            logText = "" // 开启任务时清空旧日志
                            appendLog("🚀 开始合并作业...")
                            
                            val params = MotionPhotoMuxer.MuxParams(
                                inputUri!!, outputUri!!, moveUnmatched, forceConvertHeicToJpg,
                                deleteConvertedHeic, deleteOriginalOnSuccess
                            )

                            // 启动协程进行异步文件夹处理
                            coroutineScope.launch {
                                MotionPhotoMuxer.processFolder(
                                    context = context,
                                    params = params,
                                    onProgress = { current, total ->
                                        progress = current.toFloat() / total
                                        progressText = "$current / $total"
                                    },
                                    onLog = { msg ->
                                        appendLog(msg)
                                    }
                                )
                                isProcessing = false
                            }
                        } else {
                            appendLog("❌ 错误：请先选择输入和输出文件夹！")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isProcessing // 任务执行中禁用按钮
                ) {
                    Text("开始合并", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    /**
     * SwitchItem: 重用的开关行列 UI 组件
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
