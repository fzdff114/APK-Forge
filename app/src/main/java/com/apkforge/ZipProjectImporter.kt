package com.apkforge

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

data class ImportResult(val root: File, val fileCount: Int)

object ZipProjectImporter {
    private const val MAX_FILES = 8_000
    private const val MAX_EXPANDED_BYTES = 512L * 1024L * 1024L

    fun import(context: Context, uri: Uri): ImportResult {
        val base = File(context.cacheDir, "imports").apply { mkdirs() }
        val destination = File(base, "project-${System.currentTimeMillis()}").apply { mkdirs() }
        var files = 0
        var totalBytes = 0L
        val rootPath = destination.canonicalPath + File.separator

        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "无法读取所选 ZIP 文件" }
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(destination, entry.name)
                    require(out.canonicalPath.startsWith(rootPath)) { "ZIP 包含越界路径，已停止解压" }
                    if (entry.isDirectory) out.mkdirs() else {
                        files++
                        require(files <= MAX_FILES) { "ZIP 文件数量超过 $MAX_FILES" }
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                require(totalBytes <= MAX_EXPANDED_BYTES) { "解压内容超过 512 MiB 安全上限" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        require(files > 0) { "ZIP 中没有可用文件" }
        return ImportResult(destination, files)
    }
}
