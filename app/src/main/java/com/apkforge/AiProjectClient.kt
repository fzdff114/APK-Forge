package com.apkforge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AiProjectClient {
    private const val MAX_CONTEXT_CHARS = 140_000
    private const val TIMEOUT_CONNECT = 25_000
    private const val TIMEOUT_READ = 180_000

    suspend fun generateOrRepair(
        endpoint: String,
        model: String,
        apiKey: String,
        requirement: String,
        existingRoot: File?,
        onStatus: suspend (String) -> Unit
    ): AiProjectDraft = withContext(Dispatchers.IO) {
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
            "API 地址必须使用 HTTP 或 HTTPS"
        }
        require(model.isNotBlank()) { "请填写模型名称" }
        require(apiKey.isNotBlank()) { "请填写 AI API Key" }
        require(requirement.isNotBlank()) { "请描述你要生成的 App 功能" }

        val contextText = existingRoot?.let(::collectProjectContext)
            ?: "这是一个全新 Android 项目，没有现有工程文件。"
        val mode = if (existingRoot == null) "从零生成一个完整、可构建的 Android App 工程"
            else "修复和完善现有 Android 工程，同时保留已有功能"

        val system = """
你是 APK Forge 内置的资深 Android 工程师和构建修复 Agent。
你的工作是把用户的自然语言需求转化为真实、完整、可编译的 Android 工程文件。
必须遵守：
1. 只输出一个 JSON 对象，不要 Markdown 代码围栏、前言或结尾说明。
2. JSON 结构必须是：{"summary":"简要说明","files":[{"path":"相对工程根目录的文件路径","content":"该文件完整文本内容"}],"notes":["必要提醒"]}。
3. 每个文件都要返回完整内容，绝不能使用省略号、伪代码、空类或 TODO 占位来冒充实现。
4. 新项目必须包含 settings.gradle.kts、根 build.gradle.kts、gradle.properties、app/build.gradle.kts、app/src/main/AndroidManifest.xml 和真实的 Activity/Compose 源码。
5. 新项目默认使用 Android Gradle Plugin 8.9.2、Gradle 8.11.1、Kotlin 2.1.20、JDK 17、compileSdk 35、minSdk 26；如用户要求其他版本，说明并保持整套版本匹配。
6. Android 工程优先 Kotlin + Jetpack Compose + Material 3；不要引入 Google Play Services，除非用户明确要求。
7. 不要生成 local.properties、gradlew、gradlew.bat、gradle-wrapper.jar、密钥、签名文件或真实凭证。
8. 依赖必须真实存在且版本相容；不要引用不存在的 Maven 坐标或虚构 Android API。
9. 如果是修复现有工程，只修改必要文件，保留原包名、界面、业务逻辑和功能；不得无故推倒重写。
10. 不能确定的外部 API、硬件功能或第三方服务，写入 notes，不得伪造已经实现。
11. 不要输出构建命令脚本或要求 APK Forge 执行任意 shell 命令。只返回工程文件。
12. 返回的路径必须相对工程根目录，使用正斜杠。
""".trimIndent()

        val user = """
工作模式：$mode

【用户需求】
$requirement

【现有工程上下文】
$contextText

请先推理工程依赖关系，再返回可以直接写入磁盘的完整工程文件 JSON。
""".trimIndent()

        onStatus("正在调用 AI：$model")
        val payload = JSONObject()
            .put("model", model.trim())
            .put("temperature", 0.2)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))

        val connection = URL(endpoint.trim()).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_CONNECT
        connection.readTimeout = TIMEOUT_READ
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("User-Agent", "APK-Forge-Android")

        val responseText: String
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            require(code in 200..299) {
                val detail = runCatching {
                    val error = JSONObject(responseText).opt("error")
                    if (error is JSONObject) error.optString("message") else error?.toString().orEmpty()
                }.getOrDefault(responseText.take(500))
                "AI 接口请求失败（HTTP $code）：$detail"
            }
        } finally {
            connection.disconnect()
        }

        onStatus("AI 已返回结果，正在检查文件清单和路径安全…")
        val response = JSONObject(responseText)
        val choices = response.optJSONArray("choices") ?: error("AI 响应缺少 choices")
        val choice = choices.optJSONObject(0) ?: error("AI 没有返回有效内容")
        val message = choice.optJSONObject("message") ?: error("AI 响应缺少 message")
        val aiText = readMessageContent(message.opt("content"))
        val result = JSONObject(stripCodeFence(aiText))
        val filesJson = result.optJSONArray("files") ?: error("AI JSON 中没有 files 数组")
        val files = (0 until filesJson.length()).map { index ->
            val file = filesJson.optJSONObject(index) ?: error("第 " + (index + 1) + " 个文件结构错误")
            AiGeneratedFile(file.optString("path"), file.optString("content"))
        }
        val notesArray = result.optJSONArray("notes")
        val notes = if (notesArray == null) emptyList()
            else (0 until notesArray.length()).map { notesArray.optString(it) }.filter { it.isNotBlank() }
        val draft = AiProjectDraft(
            summary = result.optString("summary", "AI 已生成工程文件。"),
            files = files,
            notes = notes
        )
        AiProjectWorkspace.validateDraft(draft)
        draft
    }

    private fun collectProjectContext(root: File): String {
        val output = StringBuilder()
        output.appendLine("工程根目录：" + root.name)
        var used = 0
        val candidates = root.walkTopDown().onEnter { dir ->
            dir == root || dir.name !in setOf(".git", ".gradle", ".idea", "build", ".apkforge")
        }.filter { it.isFile && isUsefulTextFile(it) }
            .sortedBy { contextPriority(it.relativeTo(root).invariantSeparatorsPath) }
            .take(80)
            .toList()

        for (file in candidates) {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val remaining = MAX_CONTEXT_CHARS - used
            if (remaining <= 1000) break
            val content = runCatching {
                if (file.length() > 30_000L) {
                    file.inputStream().bufferedReader().use { it.readText().take(30_000) }
                } else file.readText(Charsets.UTF_8)
            }.getOrDefault("")
            val block = "\n--- FILE: $relative ---\n" + content.take(minOf(30_000, remaining)) + "\n"
            output.append(block)
            used += block.length
        }
        return output.toString().take(MAX_CONTEXT_CHARS)
    }

    private fun contextPriority(path: String): Int = when {
        path == "settings.gradle.kts" || path == "settings.gradle" -> 0
        path == "build.gradle.kts" || path == "build.gradle" -> 1
        path.endsWith("app/build.gradle.kts") || path.endsWith("app/build.gradle") -> 2
        path.endsWith("AndroidManifest.xml") -> 3
        path.endsWith(".toml") -> 4
        path.endsWith(".kt") || path.endsWith(".java") -> 5
        path.endsWith(".xml") -> 6
        else -> 10
    }

    private fun isUsefulTextFile(file: File): Boolean {
        if (file.name == "local.properties" ||
            file.extension.lowercase() in setOf("jar", "apk", "aab", "png", "jpg", "jpeg", "webp", "so", "class", "jks", "keystore")) {
            return false
        }
        return file.name in setOf(
            "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties"
        ) || file.extension.lowercase() in setOf(
            "kt", "java", "xml", "json", "toml", "properties", "pro", "txt", "md"
        )
    }

    private fun readMessageContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val item = value.optJSONObject(i) ?: continue
                append(item.optString("text"))
            }
        }
        else -> error("AI 返回的 message.content 格式不受支持")
    }

    private fun stripCodeFence(value: String): String {
        val trimmed = value.trim()
        val fence = 96.toChar().toString().repeat(3)
        if (!trimmed.startsWith(fence)) return trimmed
        val withoutStart = trimmed.substringAfter('\n', "")
        return withoutStart.substringBeforeLast(fence).trim()
    }
}
