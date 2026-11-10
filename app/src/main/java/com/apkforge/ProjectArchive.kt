package com.apkforge

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectArchive {
    private val skippedDirectories = setOf(".git", ".gradle", ".idea", "build", ".apkforge", "captures")
    private const val MAX_UPLOAD_BYTES = 12L * 1024L * 1024L

    fun create(projectRoot: File, output: File): File {
        val root = projectRoot.canonicalFile
        val rootPrefix = root.path + File.separator
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            root.walkTopDown().onEnter { dir -> dir == root || dir.name !in skippedDirectories }.forEach { file ->
                if (!file.isFile || file.name == "local.properties" ||
                    file.extension in setOf("apk", "aab", "log", "iml")) return@forEach
                val canonical = runCatching { file.canonicalPath }.getOrDefault("")
                if (!canonical.startsWith(rootPrefix)) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative.isBlank()) return@forEach
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(output.isFile && output.length() > 0) { "工程压缩包为空" }
        require(output.length() <= MAX_UPLOAD_BYTES) {
            "工程压缩后超过 12 MiB 上传上限，请先排除大型资源或缓存。"
        }
        return output
    }
}
