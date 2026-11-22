package com.alibaba.mnnllm.android.modelist

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Copies user-selected GGUF models into the app-owned gguf_models directory.
 * Models stay on-device; each import is isolated in its own generated folder.
 */
object LocalGgufModelImporter {
    private val shardPattern = Regex("(?i)-(\\d{5})-of-(\\d{5})\\.gguf$")

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "gguf_models")

    fun copyFromUri(
        context: Context,
        uri: Uri,
        displayName: String,
        cancellationSignal: CancellationSignal? = null,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        val name = safeName(displayName)
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            throw IOException("请选择 .gguf 模型文件。")
        }
        if (name.contains("mmproj", ignoreCase = true)) {
            throw IOException("当前 GGUF 后端只支持文本模型；mmproj 多模态投影文件暂不可单独导入。")
        }
        val expectedSize = querySize(context, uri)
        val folder = createModelFolder(context, name)
        try {
            val target = File(folder, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyStream(input, target, expectedSize, cancellationSignal, onProgress)
            } ?: throw IOException("无法读取所选模型文件。")
            validateMainModel(target)
            return target
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    fun copyFromTree(
        context: Context,
        treeUri: Uri,
        cancellationSignal: CancellationSignal? = null,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): List<File> {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法读取所选模型文件夹。")
        val files = mutableListOf<Pair<DocumentFile, String>>()
        collectGgufFiles(tree, "", files, cancellationSignal)
        if (files.isEmpty()) return emptyList()

        if (files.any { it.first.name.orEmpty().contains("mmproj", ignoreCase = true) }) {
            throw IOException("所选文件夹包含 mmproj 多模态投影文件。当前版本只导入文本 GGUF 模型，暂不支持视觉模型。")
        }

        val totalBytes = files.sumOf { it.first.length().coerceAtLeast(0L) }
        var copiedBefore = 0L
        val importedFolders = mutableListOf<File>()
        val importedModels = mutableListOf<File>()
        try {
            val grouped = files.groupBy { (document, relativePath) ->
                val name = document.name ?: throw IOException("GGUF 文件缺少文件名。")
                val parent = relativePath.substringBeforeLast('/', "")
                val shard = shardPattern.find(name)
                val base = if (shard != null) name.removeRange(shard.range) else name
                parent + "\u0000" + base.lowercase()
            }.toSortedMap()

            for ((_, group) in grouped) {
                cancellationSignal?.throwIfCanceled()
                val sorted = group.sortedBy { it.first.name.orEmpty().lowercase() }
                val names = sorted.map { it.first.name ?: throw IOException("GGUF 文件缺少文件名。") }
                if (names.map { safeName(it).lowercase() }.toSet().size != names.size) {
                    throw IOException("同一模型分组中存在重名 GGUF 文件，无法安全导入。")
                }

                val shards = sorted.mapNotNull { pair ->
                    val name = pair.first.name ?: return@mapNotNull null
                    shardPattern.find(name)?.let { match ->
                        Triple(pair, match.groupValues[1].toInt(), match.groupValues[2].toInt())
                    }
                }
                val primaryPair = if (shards.isNotEmpty()) {
                    val expectedParts = shards.first().third
                    if (shards.size != sorted.size ||
                        shards.any { it.third != expectedParts } ||
                        shards.size != expectedParts ||
                        shards.map { it.second }.toSet() != (1..expectedParts).toSet()
                    ) {
                        throw IOException("GGUF 分片不完整或分片编号不匹配：应包含 1 到 ${expectedParts} 号分片。")
                    }
                    shards.minBy { it.second }.first
                } else {
                    if (sorted.size != 1) throw IOException("所选文件夹内包含多个未分组的 GGUF 文件。")
                    sorted.single()
                }

                val primaryName = primaryPair.first.name ?: throw IOException("主 GGUF 文件名无效。")
                val folder = createModelFolder(context, primaryName)
                importedFolders += folder

                for ((document, _) in sorted) {
                    cancellationSignal?.throwIfCanceled()
                    val name = safeName(document.name ?: throw IOException("GGUF 文件缺少文件名。"))
                    val target = File(folder, name)
                    val expected = document.length().takeIf { it > 0L } ?: -1L
                    context.contentResolver.openInputStream(document.uri)?.use { input ->
                        val copied = copyStream(
                            input, target, expected, cancellationSignal
                        ) { current, _ ->
                            onProgress?.invoke(copiedBefore + current, totalBytes)
                        }
                        copiedBefore += copied
                    } ?: throw IOException("无法读取 GGUF 文件：$name")
                }

                val primaryFile = File(folder, safeName(primaryName))
                validateMainModel(primaryFile)
                importedModels += primaryFile
            }
            return importedModels
        } catch (error: Exception) {
            importedFolders.forEach { it.deleteRecursively() }
            throw error
        }
    }

    private fun collectGgufFiles(
        directory: DocumentFile,
        prefix: String,
        output: MutableList<Pair<DocumentFile, String>>,
        cancellationSignal: CancellationSignal?
    ) {
        cancellationSignal?.throwIfCanceled()
        directory.listFiles().forEach { child ->
            cancellationSignal?.throwIfCanceled()
            val name = child.name ?: return@forEach
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                collectGgufFiles(child, relative, output, cancellationSignal)
            } else if (child.isFile && name.endsWith(".gguf", ignoreCase = true)) {
                output += child to relative
            }
        }
    }

    private fun createModelFolder(context: Context, modelName: String): File {
        val base = root(context)
        if (!base.exists() && !base.mkdirs()) throw IOException("无法创建 GGUF 模型目录。")
        val stem = safeName(modelName).removeSuffix(".gguf").take(64).ifBlank { "gguf_model" }
        val folder = File(base, "${stem}_${UUID.randomUUID().toString().take(8)}")
        if (!folder.mkdirs()) throw IOException("无法创建模型存储文件夹。")
        return folder
    }

    private fun safeName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val safe = leaf.replace(Regex("[^A-Za-z0-9._()\\-\\u4e00-\\u9fff]"), "_")
            .trim('.', '_')
            .take(128)
        if (safe.isBlank() || safe == "." || safe == "..") throw IOException("GGUF 文件名无效。")
        return safe
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && index >= 0) cursor.getLong(index).coerceAtLeast(0L)
            else 0L
        } ?: 0L
    }

    private fun copyStream(
        input: java.io.InputStream,
        target: File,
        expectedSize: Long,
        cancellationSignal: CancellationSignal?,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)?
    ): Long {
        var copied = 0L
        val buffer = ByteArray(1024 * 1024)
        FileOutputStream(target).use { output ->
            while (true) {
                cancellationSignal?.throwIfCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (expectedSize > 0L && copied + count > expectedSize) {
                    throw IOException("模型文件读取长度超过文件提供方报告的大小。")
                }
                output.write(buffer, 0, count)
                copied += count.toLong()
                onProgress?.invoke(copied, expectedSize)
            }
            output.flush()
            output.fd.sync()
        }
        if (expectedSize > 0L && copied != expectedSize) {
            target.delete()
            throw IOException("模型文件未完整复制：已复制 $copied 字节，预期 $expectedSize 字节。")
        }
        if (copied < 8L) {
            target.delete()
            throw IOException("GGUF 文件过短或已损坏。")
        }
        return copied
    }

    private fun validateMainModel(file: File) {
        val result = file.inputStream().use { GgufMetadataReader.inspect(it) }
        when (result) {
            is GgufMetadataReader.Result.Failure -> throw IOException(result.reason)
            is GgufMetadataReader.Result.Success -> {
                val metadata = result.metadata
                if (metadata.tensorCount <= 0L || metadata.architecture.isNullOrBlank()) {
                    throw IOException("GGUF 文件缺少有效模型架构或张量信息。")
                }
            }
        }
    }
}
