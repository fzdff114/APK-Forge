package com.apkforge

import android.content.Context
import java.io.File
import java.util.UUID

data class AiGeneratedFile(val path: String, val content: String)
data class AiProjectDraft(val summary: String, val files: List<AiGeneratedFile>, val notes: List<String>)

object AiProjectWorkspace {
    private const val MAX_FILES = 100
    private const val MAX_FILE_BYTES = 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 12L * 1024L * 1024L
    private val allowedExtensions = setOf(
        "kt", "java", "kts", "gradle", "xml", "json", "properties", "toml",
        "pro", "txt", "md", "yml", "yaml", "gitignore", "rules"
    )
    private val blockedNames = setOf(
        "local.properties", "gradlew", "gradlew.bat", "gradle-wrapper.jar",
        "key.properties", ".env"
    )
    private val blockedExtensions = setOf("jks", "keystore", "pem", "p12", "pfx")

    fun createNewProject(context: Context): File =
        File(context.filesDir, "projects/ai-" + UUID.randomUUID()).apply {
            check(mkdirs()) { "无法创建新工程目录" }
        }

    fun validateDraft(draft: AiProjectDraft): List<String> {
        require(draft.files.isNotEmpty()) { "AI 没有返回任何工程文件" }
        require(draft.files.size <= MAX_FILES) { "AI 返回文件过多（最多 $MAX_FILES 个）" }
        val seen = mutableSetOf<String>()
        var total = 0L
        draft.files.forEach { generated ->
            val path = generated.path
            require(path.isNotBlank() && path.length <= 240) { "AI 返回了无效文件路径" }
            require(!path.startsWith("/") && !path.startsWith("\\") && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
                "不允许绝对路径：$path"
            }
            require(!path.contains("\\")) { "文件路径必须使用 /：$path" }
            val parts = path.split("/")
            require(parts.none { it.isBlank() || it == "." || it == ".." }) { "文件路径越界：$path" }
            require(parts.none { it in setOf(".git", ".gradle", ".idea", ".apkforge") }) {
                "AI 不允许修改受保护目录：$path"
            }
            val name = parts.last()
            val extension = name.substringAfterLast(".", "").lowercase()
            require(name !in blockedNames && extension !in blockedExtensions) {
                "AI 不允许写入密钥、本机配置或 Gradle 启动二进制：$path"
            }
            require(extension in allowedExtensions || name == ".gitignore") {
                "不支持 AI 输出此文件类型：$path"
            }
            require(seen.add(path.lowercase())) { "AI 返回重复路径：$path" }
            val byteCount = generated.content.toByteArray(Charsets.UTF_8).size.toLong()
            require(byteCount in 1..MAX_FILE_BYTES) { "文件内容为空或超过 1 MiB：$path" }
            total += byteCount
            require(total <= MAX_TOTAL_BYTES) { "AI 返回文件总量超过 12 MiB" }
        }
        return draft.files.map { it.path }
    }

    fun applyDraft(root: File, draft: AiProjectDraft): Int {
        validateDraft(draft)
        val base = root.canonicalFile
        val prefix = base.path + File.separator
        val backupRoot = File(base, ".apkforge/backups/" + System.currentTimeMillis())
        val planned = draft.files.map { entry ->
            val target = File(base, entry.path).canonicalFile
            require(target.path.startsWith(prefix)) { "AI 输出路径越界" }
            target to entry
        }
        planned.forEach { (target, entry) ->
            target.parentFile?.mkdirs()
            if (target.isFile) {
                val backup = File(backupRoot, entry.path)
                backup.parentFile?.mkdirs()
                target.copyTo(backup, overwrite = true)
            }
            val temporary = File(target.parentFile, target.name + ".apkforge-tmp")
            temporary.writeText(entry.content, Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                temporary.delete()
                error("无法替换文件")
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                error("无法写入文件")
            }
        }
        return planned.size
    }

    fun countFiles(root: File): Int =
        root.walkTopDown().onEnter { dir ->
            dir == root || dir.name !in setOf(".git", ".gradle", ".idea", "build", ".apkforge")
        }.count { it.isFile }
}
