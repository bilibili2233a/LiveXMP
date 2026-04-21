package com.livexmp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 任务进度状态数据模型
 */
data class MuxState(
    val current: Int = 0,      // 当前正在处理第几个
    val total: Int = 0,        // 任务总数（配对组数）
    val success: Int = 0,      // 成功数量
    val fail: Int = 0,         // 失败数量
    val log: String = "",      // 累积的控制台日志文本
    val isProcessing: Boolean = false // 服务是否正在运行中
)

/**
 * 后台合并服务 (Foreground Service)
 * 允许应用在后台、锁屏状态下依然能够稳定地执行耗时的照片合并作业。
 */
class MuxerService : Service() {

    // 使用 Flow 管理全局状态，UI 通过订阅它来实时更新界面
    companion object {
        private val _state = MutableStateFlow(MuxState())
        val state: StateFlow<MuxState> = _state
    }

    // 协程作用域：生命周期与服务绑定，确保任务随服务停止而取消
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationManager: NotificationManager

    // 通知频道 ID
    private val CHANNEL_ID = "muxer_service_channel"
    // 前台服务固定 ID
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 从启动 Intent 中提取任务参数
        val inputUris = intent?.getParcelableArrayListExtra<Uri>("inputUris") ?: return START_NOT_STICKY
        val outputDirUriStr = intent.getStringExtra("outputDirUri") ?: return START_NOT_STICKY
        val outputDirUri = Uri.parse(outputDirUriStr)

        val params = MotionPhotoMuxer.MuxParams(
            inputUris = inputUris,
            outputDirUri = outputDirUri,
            moveUnmatched = intent.getBooleanExtra("moveUnmatched", false),
            forceConvertHeicToJpg = intent.getBooleanExtra("forceConvertHeicToJpg", false),
            deleteConvertedHeic = intent.getBooleanExtra("deleteConvertedHeic", false),
            deleteOriginalOnSuccess = intent.getBooleanExtra("deleteOriginalOnSuccess", false)
        )

        // 重置状态
        _state.update { MuxState(isProcessing = true, log = getString(R.string.log_service_start)) }
        
        // 提升为前台服务，防止后台被系统杀死
        startForegroundServiceCompat()

        // 在协程中启动耗时合并任务
        serviceScope.launch {
            MotionPhotoMuxer.processFiles(
                context = this@MuxerService,
                params = params,
                onProgress = { current, total, success, fail ->
                    // 更新全局状态，通知 UI 刷新进度条和数字
                    _state.update { it.copy(current = current, total = total, success = success, fail = fail) }
                    // 同时更新系统状态栏通知中的进度
                    updateNotification(current, total, success, fail)
                },
                onLog = { msg ->
                    // 追加日志文本到 UI 终端
                    _state.update { it.copy(log = it.log + msg + "\n") }
                }
            )
            
            // 任务全部完成后，停止前台服务并震动反馈
            _state.update { it.copy(isProcessing = false) }
            showCompletionNotification(_state.value.success, _state.value.fail)
            vibrateFeedback()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * 根据当前进度更新持续显示的通知
     */
    private fun updateNotification(current: Int, total: Int, success: Int, fail: Int) {
        val progressPercent = if (total > 0) (current * 100 / total) else 0
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 待替换为合适的图标
            .setContentTitle(getString(R.string.notification_title_processing, progressPercent))
            .setContentText(getString(R.string.notification_text_progress, success, fail, "${total - current}"))
            .setProgress(total, current, false)
            .setOngoing(true) // 禁止用户划掉正在运行的通知
            .setSilent(true)  // 进度更新不发出声音打扰用户
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 任务完成时的震动反馈（兼容旧版和 Android 12+）
     */
    private fun vibrateFeedback() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showCompletionNotification(success: Int, fail: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title_complete))
            .setContentText(getString(R.string.notification_text_complete, success, fail))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun startForegroundServiceCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.log_service_start))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
