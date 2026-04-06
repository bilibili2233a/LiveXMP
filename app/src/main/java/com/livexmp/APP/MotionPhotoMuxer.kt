package com.LiveXMP.APP

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
 * 该类负责将 Apple 导出的 HEIC 图片和 MOV 视频合并为符合 Google 规范的 Motion Photo。
 */
object MotionPhotoMuxer {

    private const val TAG = "MotionPhotoMuxer"

    /**
     * 合并操作的参数配置类
     */
    data class MuxParams(
        val inputDirUri: Uri,          // 输入目录的 URI
        val outputDirUri: Uri,         // 输出目录的 URI
        val moveUnmatched: Boolean,    // 是否移动不匹配的文件
        val forceConvertHeicToJpg: Boolean, // 是否强制将所有 HEIC 转换为 JPG
        val deleteConvertedHeic: Boolean,   // 合并后是否删除转换生成的中间 HEIC/JPG
        val deleteOriginalOnSuccess: Boolean // 合并成功后是否删除原始文件
    )

    /**
     * 核心处理逻辑：扫描文件夹并批量处理
     * @param context Android 上下文
     * @param params 处理参数
     * @param onProgress 进度回调 (当前处理数, 总数)
     * @param onLog 日志回显回调
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun processFolder(
        context: Context,
        params: MuxParams,
        onProgress: (Int, Int) -> Unit,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 通过 SAF (Storage Access Framework) 获取目录控制权
        val inputDir = DocumentFile.fromTreeUri(context, params.inputDirUri) ?: return@withContext
        val outputDir = DocumentFile.fromTreeUri(context, params.outputDirUri) ?: return@withContext

        // 列出所有文件并筛选出图片
        val allFiles = inputDir.listFiles()
        val imageFiles = allFiles.filter { it.name?.endsWith(".heic", true) == true || it.name?.endsWith(".jpg", true) == true }
        var processedCount = 0

        onLog("扫描到 ${imageFiles.size} 个图片文件...")

        for (imgFile in imageFiles) {
            val baseName = imgFile.name?.substringBeforeLast(".") ?: continue
            // 查找同名的视频文件 (MOV 或 MP4)
            val videoFile = allFiles.find { 
                it.name.equals("$baseName.mov", true) || it.name.equals("$baseName.mp4", true) 
            }

            if (videoFile != null) {
                onLog("🔗 匹配成功: ${imgFile.name} + ${videoFile.name}")
                try {
                    // 执行单个文件的合并逻辑
                    muxSingleLivePhoto(context, imgFile, videoFile, outputDir, params, onLog)
                    onLog("✅ 合并成功: $baseName.jpg")
                    
                    // 如果设置了成功后删除原始文件
                    if (params.deleteOriginalOnSuccess) {
                        imgFile.delete()
                        videoFile.delete()
                        onLog("🗑️ 已删除原始文件: ${imgFile.name}, ${videoFile.name}")
                    }
                } catch (e: Exception) {
                    onLog("❌ 合并失败 ${imgFile.name}: ${e.message}")
                    Log.e(TAG, "Mux error", e)
                }
            } else {
                onLog("⚠️ 未发现匹配视频: ${imgFile.name}")
            }
            
            processedCount++
            // 通知 UI 更新进度
            onProgress(processedCount, imageFiles.size)
        }
        
        onLog("🎉 所有任务处理完毕！")
    }

    /**
     * 单个文件的具体合并实现
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun muxSingleLivePhoto(
        context: Context,
        imgFile: DocumentFile,
        videoFile: DocumentFile,
        outputDir: DocumentFile,
        params: MuxParams,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val baseName = imgFile.name?.substringBeforeLast(".") ?: "output"
        val outFileName = "${baseName}_motion.jpg"
        
        // 在输出目录创建目标文件
        var outFile = outputDir.findFile(outFileName)
        if (outFile == null) {
            outFile = outputDir.createFile("image/jpeg", outFileName)
        }
        val outUri = outFile!!.uri

        // 1. 获取视频文件的长度，这是 XMP 偏移量计算的关键
        val videoFd = context.contentResolver.openFileDescriptor(videoFile.uri, "r") 
            ?: throw IllegalStateException("无法打开视频文件: ${videoFile.name}")
        val videoSize = videoFd.statSize

        // 2. 利用系统硬件加速解码图片
        // 苹果导出的 HEIC 无法直接用 XMP，通常需要转为 JPG 存储
        val tempJpgFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")
        try {
            onLog("⏱️ [硬件加速] 解码 ${imgFile.name}...")
            val source = ImageDecoder.createSource(context.contentResolver, imgFile.uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                // 强制使用硬件位图内存，防止大图 OOM
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE 
            }

            // 将解码后的位图压缩为高质量 JPG 存入临时文件
            FileOutputStream(tempJpgFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            bitmap.recycle() // 显式回收位图内存

            // 3. 构建 Google 动态照片规范的 XMP 元数据
            // 这部分元数据告诉 Google 相册：该 JPG 尾部拼接了一个特定长度的视频
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
            // 将字符串包裹为 JPEG 的 APP1 数据段
            val xmpApp1 = buildApp1XmpSegment(xmpString)

            // 4. 使用 NIO FileChannel 进行“零拷贝”级的数据拼接
            // 直接在内核空间传输数据，效率极高
            onLog("⏱️ [NIO 零拷贝] 合并文件中...")
            val outFd = context.contentResolver.openFileDescriptor(outUri, "w")
                ?: throw IllegalStateException("无法写入输出文件")
            
            FileInputStream(tempJpgFile).use { fisJpg ->
                FileInputStream(videoFd.fileDescriptor).use { fisVideo ->
                    FileOutputStream(outFd.fileDescriptor).use { fosOut ->
                        val inJpgChannel = fisJpg.channel
                        val inVideoChannel = fisVideo.channel
                        val outChannel = fosOut.channel

                        // 4.1 写入 JPG 标准文件头 (Start of Image)
                        val headerBuffer = ByteBuffer.allocate(2)
                        inJpgChannel.read(headerBuffer)
                        headerBuffer.flip()
                        outChannel.write(headerBuffer)

                        // 4.2 在文件头下方立即插入我们的 XMP 元数据段
                        outChannel.write(ByteBuffer.wrap(xmpApp1))

                        // 4.3 传输图片主体的其余字节
                        inJpgChannel.transferTo(2, inJpgChannel.size() - 2, outChannel)

                        // 4.4 在图片数据末尾追加视频文件
                        inVideoChannel.transferTo(0, inVideoChannel.size(), outChannel)
                    }
                }
            }
            outFd.close()
            videoFd.close()

        } finally {
            // 清理临时转换生成的 JPG
            if (tempJpgFile.exists()) {
                tempJpgFile.delete()
            }
        }
    }

    /**
     * 构建符合 JPEG 规范的 APP1 XMP 数据段封装
     */
    private fun buildApp1XmpSegment(xmp: String): ByteArray {
        // XMP 在 JPEG 中的标准前缀
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)
        val payload = xmp.toByteArray(StandardCharsets.UTF_8)
        // 长度包含 2 字节的长字段本身
        val length = 2 + header.size + payload.size 

        val buffer = ByteBuffer.allocate(2 + length)
        buffer.put(0xFF.toByte())
        buffer.put(0xE1.toByte()) // JPEG APP1 标记
        buffer.putShort(length.toShort()) // 段长度
        buffer.put(header) // 命名空间头
        buffer.put(payload) // 实际 XMP 内容
        
        return buffer.array()
    }
}
