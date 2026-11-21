package com.alibaba.mnnllm.android.modelist

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Downloads the exact DeepSeek Q2_K artifact already verified by the project's host smoke test.
 * This avoids third-party file-provider wrappers altering bytes during local-file import.
 */
object VerifiedDeepSeekDownloader {
    const val MODEL_NAME = "DeepSeek-R1-Distill-Qwen-1.5B-Q2_K.gguf"
    const val EXPECTED_SIZE = 752_880_224L
    const val EXPECTED_SHA256 =
        "902a0f3910078db0fdb1cf58686d36160794b96e4698eae577fb18b35f2b0d87"
    private const val MODEL_URL =
        "https://modelscope.cn/models/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/master/DeepSeek-R1-Distill-Qwen-1.5B-Q2_K.gguf"

    fun download(
        context: Context,
        cancellationSignal: CancellationSignal? = null,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        val parent = context.getExternalFilesDir("gguf_models")
            ?: File(context.filesDir, "gguf_models")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建本地 GGUF 模型目录。")
        }
        val folder = File(parent, "DeepSeek-R1-Distill-Qwen-1.5B-Q2_K_verified_" +
            UUID.randomUUID().toString().take(8))
        if (!folder.mkdirs()) throw IOException("无法创建模型目录。")
        val partial = File(folder, "$MODEL_NAME.part")
        val target = File(folder, MODEL_NAME)
        var connection: HttpURLConnection? = null
        try {
            cancellationSignal?.throwIfCanceled()
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 90_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "MNN-Chat-GGUF/0.9.5")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val response = connection.responseCode
            if (response !in 200..299) {
                throw IOException("ModelScope 下载失败，HTTP $response。")
            }
            val advertised = connection.contentLengthLong
            if (advertised > 0L && advertised != EXPECTED_SIZE) {
                throw IOException(
                    "ModelScope 返回的文件大小不符：应为 $EXPECTED_SIZE 字节，实际声明 $advertised 字节。"
                )
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        cancellationSignal?.throwIfCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count.toLong()
                        onProgress?.invoke(copied, EXPECTED_SIZE)
                    }
                    output.fd.sync()
                }
            }

            val actualHash = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            if (copied != EXPECTED_SIZE || actualHash != EXPECTED_SHA256) {
                throw IOException(
                    "下载文件完整性校验失败：大小 $copied / $EXPECTED_SIZE 字节，" +
                        "SHA-256 $actualHash。文件未加入模型库。"
                )
            }
            if (!partial.renameTo(target)) {
                throw IOException("校验通过，但无法将下载文件写入模型库。")
            }
            return target
        } catch (error: OperationCanceledException) {
            folder.deleteRecursively()
            throw error
        } catch (error: Exception) {
            folder.deleteRecursively()
            if (error is IOException) throw error
            throw IOException("已验证模型下载失败：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            connection?.disconnect()
        }
    }
}
