package com.livexmp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * 高性能动态照片合并工具类 (NIO 零拷贝 + 硬件加解码)
 * 该类负责将 Apple 导出的图片（HEIC/JPG）和 MOV 视频合并为符合 Google 规范的 Motion Photo。
 * 核心原理：在 JPEG 的 APP1 数据段中插入 MicroVideo 偏移元数据，并将视频文件物理追加在图片末尾。
 */
object MotionPhotoMuxer {

    private const val TAG = "MotionPhotoMuxer"

    // 支持处理的文件扩展名
    private val IMAGE_EXTS = setOf("heic", "jpg", "jpeg")
    private val VIDEO_EXTS = setOf("mov", "mp4")

    /**
     * 合并操作的参数配置封装类
     */
    data class MuxParams(
        val inputUris: List<Uri>,      // 待处理的文件 URI 列表（可以是照片或视频）
        val outputDirUri: Uri,         // 合并后结果保存的目标文件夹 URI
        val moveUnmatched: Boolean,    // 是否移动不匹配的文件（暂未使用物理移动，保留逻辑占位）
        val forceConvertHeicToJpg: Boolean, // 当只有视频没有匹配图片时，是否强制抽帧转为实况照片
        val deleteConvertedHeic: Boolean,   // 合并后是否清理中间生成的临时文件
        val deleteOriginalOnSuccess: Boolean // 合并成功后是否删除输入的原始文件
    )

    /**
     * 合并前预扫描的结果统计
     */
    data class ScanResult(val pairsCount: Int, val orphansCount: Int)

    /**
     * 核心入口：处理选定的文件列表
     * 该函数会根据文件名进行自动配对，并执行合并。
     * 
     * @param context Android 上下文
     * @param params 处理参数
     * @param onProgress 进度回调 (当前索引, 总组数, 成功数, 失败数)
     * @param onLog 日志回显回调，用于在 UI 终端显示处理细节
     */
    suspend fun processFiles(
        context: Context,
        params: MuxParams,
        onProgress: (Int, Int, Int, Int) -> Unit,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 获取输出目录的 DocumentFile 对象
        val outputDir = DocumentFile.fromTreeUri(context, params.outputDirUri) ?: return@withContext

        // 1. 将提交的所有 URI 转换为 DocumentFile 对象
        val allFiles = params.inputUris.mapNotNull { uri ->
            DocumentFile.fromSingleUri(context, uri)
        }
        
        // 2. 核心配对逻辑：根据文件名（去掉后缀）进行分组
        // 例如：IMG_001.JPG 和 IMG_001.MOV 会被分到 "img_001" 这一组
        val fileMap = allFiles.groupBy { it.name?.substringBeforeLast(".")?.lowercase() ?: "" }
        val tasks = fileMap.keys.filter { it.isNotEmpty() }
        
        onLog(context.getString(R.string.log_scanned_groups, tasks.size))

        var successCount = 0
        var failCount = 0

        // 3. 遍历每一个分组（即每一组潜在的实况照片）
        tasks.forEachIndexed { index, baseName ->
            val group = fileMap[baseName] ?: return@forEachIndexed
            // 从组内寻找图片文件和视频文件
            val imageFile = group.find { it.name?.substringAfterLast(".")?.lowercase() in IMAGE_EXTS }
            val videoFile = group.find { it.name?.substringAfterLast(".")?.lowercase() in VIDEO_EXTS }

            try {
                if (imageFile != null && videoFile != null) {
                    // 情况 A：发现图片和视频配套 -> 执行标准合并
                    onLog(context.getString(R.string.log_match_success, imageFile.name ?: "", videoFile.name ?: ""))
                    muxSingleLivePhoto(context, imageFile, videoFile, outputDir, params, onLog, baseName)
                    onLog(context.getString(R.string.log_mux_success, baseName))
                    successCount++
                    // 如果开启了“处理后删除原始文件”，则执行删除
                    if (params.deleteOriginalOnSuccess) {
                        try { imageFile.delete(); videoFile.delete() } catch (e: Exception) {}
                        onLog(context.getString(R.string.log_deleted_original, imageFile.name ?: "", videoFile.name ?: ""))
                    }
                } else if (videoFile != null && params.forceConvertHeicToJpg) {
                    // 情况 B：只有视频但开启了“单视频转实况” -> 抽帧作为封面并合并
                    onLog(context.getString(R.string.log_extracting_cover, videoFile.name ?: ""))
                    muxSingleLivePhoto(context, null, videoFile, outputDir, params, onLog, baseName)
                    onLog(context.getString(R.string.log_single_video_success, baseName))
                    successCount++
                    if (params.deleteOriginalOnSuccess) {
                        try { videoFile.delete() } catch (e: Exception) {}
                        onLog(context.getString(R.string.log_deleted_original_single, videoFile.name ?: ""))
                    }
                } else {
                    // 情况 C：孤立文件且不符合转换条件 -> 记录警告日志
                    if (imageFile != null) {
                        onLog(context.getString(R.string.log_no_match_video, imageFile.name ?: ""))
                    } else if (videoFile != null) {
                        onLog(context.getString(R.string.log_no_match_image, videoFile.name ?: ""))
                    }
                }
            } catch (e: Exception) {
                // 处理过程中发生的异常捕获
                onLog(context.getString(R.string.log_process_failed, baseName, e.message ?: ""))
                Log.e(TAG, "处理 $baseName 时出错", e)
                failCount++
            }
            // 每处理一组，更新一次 UI 进度
            onProgress(index + 1, tasks.size, successCount, failCount)
        }
        
        onLog(context.getString(R.string.log_all_done))
    }

    /**
     * 极速预扫描逻辑
     * 在用户点击“开始合并”之前执行，用于快速统计有多少组配对和多少孤立视频，以便弹出询问对话框。
     */
    suspend fun preScan(context: Context, inputUris: List<Uri>): ScanResult = withContext(Dispatchers.IO) {
        if (inputUris.isEmpty()) return@withContext ScanResult(0, 0)
        
        val allFiles = inputUris.mapNotNull { uri ->
            DocumentFile.fromSingleUri(context, uri)
        }
        
        val fileMap = allFiles.groupBy { it.name?.substringBeforeLast(".")?.lowercase() ?: "" }
        val tasks = fileMap.keys.filter { it.isNotEmpty() }

        var pairsCount = 0
        var orphansCount = 0

        tasks.forEach { baseName ->
            val group = fileMap[baseName] ?: return@forEach
            val hasImg = group.any { it.name?.substringAfterLast(".")?.lowercase() in IMAGE_EXTS }
            val hasVid = group.any { it.name?.substringAfterLast(".")?.lowercase() in VIDEO_EXTS }

            if (hasImg && hasVid) {
                pairsCount++
            } else if (hasVid && !hasImg) {
                orphansCount++
            }
        }

        ScanResult(pairsCount, orphansCount)
    }

    /**
     * 从视频中提取第 1 秒的帧作为封面图
     * 用于“单视频转实况照片”场景。
     */
    private fun extractFrame(context: Context, videoFile: DocumentFile): File {
        val retriever = android.media.MediaMetadataRetriever()
        val pfd = context.contentResolver.openFileDescriptor(videoFile.uri, "r")
        retriever.setDataSource(pfd?.fileDescriptor)
        // 提取第 1,000,000 微秒（第 1 秒）的帧
        var bitmap = retriever.getFrameAtTime(1000000)
        if (bitmap == null) {
            // 如果第 1 秒提取失败，回退到首帧
            bitmap = retriever.getFrameAtTime(0)
        }
        val tempFile = File(context.cacheDir, "temp_frame_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { fos ->
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }
        bitmap?.recycle() // 释放位图内存
        retriever.release()
        pfd?.close()
        return tempFile
    }

    /**
     * 单组实况照片的具体合并算法实现
     * 基于 Google Motion Photo 2.0 规范：JPG + XMP Offset + Movie Data
     */
    private suspend fun muxSingleLivePhoto(
        context: Context,
        imgFile: DocumentFile?,
        videoFile: DocumentFile,
        outputDir: DocumentFile,
        params: MuxParams,
        onLog: (String) -> Unit,
        baseName: String
    ) = withContext(Dispatchers.IO) {
        val outFileName = "${baseName}_motion.jpg"
        
        // 目标：在输出目录尝试寻找或创建一个新的 JPG 文件
        var outFile = outputDir.findFile(outFileName)
        if (outFile == null) {
            outFile = outputDir.createFile("image/jpeg", outFileName)
        }
        val outUri = outFile!!.uri

        // 1. 关键步骤：获取视频数据长度（字节数）
        // Google 规范要求在图片头部的 XMP 中声明从文件末尾向前数多少字节是视频数据
        val videoFd = context.contentResolver.openFileDescriptor(videoFile.uri, "r") 
            ?: throw IllegalStateException("无法打开视频文件: ${videoFile.name}")
        val videoSize = videoFd.statSize

        // 2. 准备封面图片
        // 如果输入有原始照片，则使用硬件解码加速转换为标准 JPG。
        // 如果只有视频，则调用 extractFrame 抽帧生成临时 JPG。
        val tempJpgFile = if (imgFile != null) {
            val f = File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")
            onLog(context.getString(R.string.log_hw_decode, imgFile.name ?: ""))
            val source = ImageDecoder.createSource(context.contentResolver, imgFile.uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                // 强制分配硬件内存（ALLOCATOR_HARDWARE），大幅降低内存占用并提高性能
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE 
            }
            FileOutputStream(f).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            bitmap.recycle()
            f
        } else {
            onLog(context.getString(R.string.log_video_frame_extract))
            extractFrame(context, videoFile)
        }

        try {
            // 3. 构造 Google 实况照片元数据 (XMP APP1 Segment)
            // GCamera:MicroVideoOffset "$videoSize" 是最关键的参数
            val xmpString = """
                <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                        GCamera:MicroVideo="1"
                        GCamera:MicroVideoVersion="1"
                        GCamera:MicroVideoOffset="$videoSize"
                        GCamera:MicroVideoPresentationTimestampUs="1500000"/>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="h"?>
            """.trimIndent()
            
            // 封装为 JPEG 专用的 APP1 格式字节流
            val xmpApp1 = buildApp1XmpSegment(xmpString)

            // 4. NIO 零拷贝数据合成
            // 我们不进行逐字节读写，而是利用 FileChannel 直接在磁盘间传输流，极快且不占 JVM 内存。
            onLog(context.getString(R.string.log_nio_merging))
            val outFd = context.contentResolver.openFileDescriptor(outUri, "w")
                ?: throw IllegalStateException("无法打开输出文件进行写入")
            
            FileInputStream(tempJpgFile).use { fisJpg ->
                FileInputStream(videoFd.fileDescriptor).use { fisVideo ->
                    FileOutputStream(outFd.fileDescriptor).use { fosOut ->
                        val inJpgChannel = fisJpg.channel
                        val inVideoChannel = fisVideo.channel
                        val outChannel = fosOut.channel

                        // 4.1 写入 JPEG SOI 标记 (FF D8)
                        val headerBuffer = ByteBuffer.allocate(2)
                        inJpgChannel.read(headerBuffer)
                        headerBuffer.flip()
                        outChannel.write(headerBuffer)

                        // 4.2 注入我们构造的 APP1 XMP 数据段
                        outChannel.write(ByteBuffer.wrap(xmpApp1))

                        // 4.3 传输图片原始数据（跳过刚写的 2 字节头，直到文件末尾）
                        inJpgChannel.transferTo(2, inJpgChannel.size() - 2, outChannel)

                        // 4.4 紧接着在图片数据后方追加完整的视频数据
                        inVideoChannel.transferTo(0, inVideoChannel.size(), outChannel)
                    }
                }
            }
            outFd.close()
            videoFd.close()

        } finally {
            // 清理临时文件，防止 cache 目录爆炸
            if (tempJpgFile.exists()) {
                tempJpgFile.delete()
            }
        }
    }

    /**
     * 辅助函数：构造 JPEG APP1 数据段。
     * APP1 段用于存放元数据。它包含：标记(FFE1) + 长度(2字节) + 命名空间头 + 实际负载数据。
     */
    private fun buildApp1XmpSegment(xmp: String): ByteArray {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)
        val payload = xmp.toByteArray(StandardCharsets.UTF_8)
        val length = 2 + header.size + payload.size 

        val buffer = ByteBuffer.allocate(2 + length)
        buffer.put(0xFF.toByte())
        buffer.put(0xE1.toByte()) 
        buffer.putShort(length.toShort()) 
        buffer.put(header) 
        buffer.put(payload) 
        
        return buffer.array()
    }
}
