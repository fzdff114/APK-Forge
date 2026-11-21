package com.alibaba.mnnllm.android.modelist

import java.io.File
import java.io.IOException

/**
 * Indexes GGUF folders written by LocalGgufModelImporter.
 * It supports one-file models and complete llama.cpp-style multi-part GGUF models.
 */
object LocalGgufModelLibrary {
    private val shardPattern = Regex("(?i)-(\\d{5})-of-(\\d{5})\\.gguf$")

    data class Entry(
        val folder: File,
        val primaryFile: File,
        val displayName: String,
        val files: List<File>,
        val totalBytes: Long,
        val isComplete: Boolean,
        val modifiedAt: Long
    )

    fun root(contextFiles: File): File = File(contextFiles, "gguf_models")

    fun scan(root: File): List<Entry> {
        val directories = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val entries = ArrayList<Entry>()
        for (folder in directories) {
            val modelFiles = folder.listFiles()
                ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
            if (modelFiles.isEmpty()) continue

            val shards = modelFiles.mapNotNull { file ->
                shardPattern.find(file.name)?.let { match ->
                    Triple(file, match.groupValues[1].toInt(), match.groupValues[2].toInt())
                }
            }
            val isSharded = shards.isNotEmpty()
            val totalParts = shards.firstOrNull()?.third ?: 1
            val indices = shards.map { it.second }.toSet()
            val complete = if (!isSharded) {
                modelFiles.size == 1
            } else {
                shards.size == modelFiles.size &&
                    totalParts > 0 &&
                    shards.all { it.third == totalParts } &&
                    shards.size == totalParts &&
                    indices == (1..totalParts).toSet()
            }

            val primary = if (isSharded) {
                shards.minByOrNull { it.second }?.first ?: modelFiles.first()
            } else {
                modelFiles.first()
            }
            val baseName = primary.name.replace(shardPattern, ".gguf")
                .removeSuffix(".gguf")
            entries += Entry(
                folder = folder,
                primaryFile = primary,
                displayName = baseName.ifBlank { folder.name },
                files = modelFiles,
                totalBytes = modelFiles.sumOf { it.length() },
                isComplete = complete,
                modifiedAt = folder.lastModified()
            )
        }
        return entries.sortedByDescending { it.modifiedAt }
    }

    @Throws(IOException::class)
    fun delete(root: File, folder: File) {
        val canonicalRoot = root.canonicalFile
        val canonicalFolder = folder.canonicalFile
        if (canonicalFolder == canonicalRoot || canonicalFolder.parentFile != canonicalRoot) {
            throw IOException("拒绝删除模型目录之外的文件。")
        }
        if (canonicalFolder.exists() && !canonicalFolder.deleteRecursively()) {
            throw IOException("无法删除模型文件夹：${folder.name}")
        }
    }
}
