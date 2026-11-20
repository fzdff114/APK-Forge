package com.alibaba.mnnllm.android.modelist

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Imports a ZIP only when it contains exactly one complete MNN model directory.
 * Extraction is streamed to app-private staging storage and never follows archive paths.
 */
object LocalModelArchiveImporter {
    private const val MAX_ENTRIES = 50_000
    private const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L
    private const val BUFFER_BYTES = 1024 * 1024

    @Throws(IOException::class)
    fun importMnnZip(context: Context, input: InputStream, displayName: String): File {
        val modelsRoot = context.getExternalFilesDir("local_models")
            ?: File(context.filesDir, "local_models")
        return importMnnZipToDirectory(input, modelsRoot, displayName)
    }

    /**
     * JVM-only import entry point so archive security and model validation tests do not need
     * to boot an Android/Robolectric application.
     */
    @Throws(IOException::class)
    internal fun importMnnZipToDirectory(input: InputStream, modelsRoot: File, displayName: String): File {
        if (!modelsRoot.exists() && !modelsRoot.mkdirs()) {
            throw IOException("无法创建本地模型目录。")
        }

        val staging = File(modelsRoot, ".zip-import-${UUID.randomUUID()}")
        if (!staging.mkdirs()) {
            throw IOException("无法创建 ZIP 临时目录。")
        }

        var stagingMoved = false
        try {
            extractSafely(input, staging)
            val candidates = staging.walkTopDown()
                .filter { it.isDirectory }
                .filter { directory ->
                    val names = directory.listFiles()?.map { it.name } ?: emptyList()
                    LocalModelFormatInspector.inspectDirectory(names).state ==
                        LocalModelFormatInspector.State.READY_TO_IMPORT
                }
                .toList()

            if (candidates.isEmpty()) {
                throw IOException("ZIP 内没有完整的 MNN 模型目录。需要 config.json、llm.mnn、llm_config.json 和 tokenizer 文件。")
            }
            if (candidates.size > 1) {
                throw IOException("ZIP 内发现多个 MNN 模型目录，请将需要导入的模型单独打包。")
            }

            val candidate = candidates.single()
            val baseName = safeModelName(displayName.substringBeforeLast('.', displayName))
                .ifBlank { safeModelName(candidate.name).ifBlank { "LocalModel" } }
            val destination = uniqueDestination(modelsRoot, baseName)

            if (candidate == staging) {
                if (!staging.renameTo(destination)) {
                    throw IOException("无法完成 ZIP 模型导入。")
                }
                stagingMoved = true
            } else if (!candidate.renameTo(destination)) {
                throw IOException("无法将模型目录移动到本地模型库。")
            }
            return destination
        } finally {
            if (!stagingMoved) {
                staging.deleteRecursively()
            }
        }
    }

    /**
     * Returns a normalized relative path, or null for unsafe/ambiguous entry names.
     * Kept internal so traversal handling can be covered by local unit tests.
     */
    internal fun normalizedEntryPath(entryName: String): String? {
        if (entryName.isBlank() || entryName.length > 1024) return null
        val name = entryName.replace('\\', '/')
        if (name.startsWith('/') || name.startsWith("//") ||
            Regex("^[A-Za-z]:").containsMatchIn(name) || name.any { it.code < 0x20 }
        ) return null

        val isDirectory = name.endsWith('/')
        val trimmed = if (isDirectory) name.dropLast(1) else name
        if (trimmed.isBlank()) return null
        val parts = trimmed.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || ':' in it }) return null
        return parts.joinToString("/")
    }

    @Throws(IOException::class)
    private fun extractSafely(input: InputStream, staging: File) {
        val seen = HashSet<String>()
        val buffer = ByteArray(BUFFER_BYTES)
        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    throw IOException("ZIP 文件包含过多条目，已停止解压。")
                }

                val relativePath = normalizedEntryPath(entry.name)
                    ?: throw IOException("ZIP 包含不安全的路径：${entry.name}")
                val isDirectory = entry.isDirectory || entry.name.endsWith('/')
                val uniquenessKey = relativePath + if (isDirectory) "/" else ""
                if (!seen.add(uniquenessKey)) {
                    throw IOException("ZIP 包含重复路径：$relativePath")
                }

                val target = File(staging, relativePath)
                val canonicalRoot = staging.canonicalFile
                val canonicalTarget = target.canonicalFile
                if (canonicalTarget.path != canonicalRoot.path &&
                    !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
                ) {
                    throw IOException("ZIP 路径超出临时目录范围。")
                }

                if (isDirectory) {
                    if (target.exists() && !target.isDirectory) {
                        throw IOException("ZIP 路径类型冲突：$relativePath")
                    }
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("无法创建 ZIP 子目录：$relativePath")
                    }
                    zip.closeEntry()
                    continue
                }

                val parent = target.parentFile ?: throw IOException("ZIP 文件路径无效：$relativePath")
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("无法创建 ZIP 文件目录：$relativePath")
                }
                if (target.exists()) {
                    throw IOException("ZIP 包含重复文件：$relativePath")
                }

                var entryBytes = 0L
                BufferedOutputStream(target.outputStream(), BUFFER_BYTES).use { output ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw IOException("ZIP 解压数据超过安全上限，已停止解压。")
                        }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun safeModelName(name: String): String {
        return name.replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .take(120)
            .trim('.')
    }

    private fun uniqueDestination(parent: File, baseName: String): File {
        var candidate = File(parent, baseName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(parent, "$baseName ($suffix)")
            suffix++
        }
        return candidate
    }
}
