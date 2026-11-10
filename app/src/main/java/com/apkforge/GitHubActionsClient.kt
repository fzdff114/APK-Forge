package com.apkforge

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.ZipInputStream

object GitHubActionsClient {
    private const val API = "https://api.github.com"
    private const val MAX_ARTIFACT_BYTES = 1024L * 1024L * 1024L
    private data class HttpResult(val code: Int, val body: String)

    suspend fun buildAndDownload(
        owner: String, repository: String, token: String, project: ProjectReport,
        cacheDir: File, onStatus: suspend (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,39}"))) { "GitHub 用户名格式不正确" }
        require(repository.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "GitHub 仓库名格式不正确" }
        require(token.startsWith("github_pat_") || token.startsWith("ghp_") || token.startsWith("gho_")) {
            "请输入 GitHub Personal Access Token"
        }

        val folder = File(cacheDir, "remote-build").apply { mkdirs() }
        val sourceZip = File(folder, "project-${System.currentTimeMillis()}.zip")
        ProjectArchive.create(project.root, sourceZip)
        val uploadPath = ".apkforge/uploads/${sourceZip.name}"
        val artifactName = "apkforge-debug-${System.currentTimeMillis()}"
        val base = "$API/repos/$owner/$repository"
        val start = System.currentTimeMillis()
        var uploadedSha: String? = null

        try {
            onStatus("正在准备工程压缩包：${sourceZip.length() / 1024} KB")
            val payload = JSONObject()
                .put("message", "APK Forge: upload temporary Android project")
                .put("branch", "main")
                .put("content", Base64.encodeToString(sourceZip.readBytes(), Base64.NO_WRAP))
            onStatus("正在上传工程到 $owner/$repository …")
            val upload = request("PUT", "$base/contents/$uploadPath", token, payload.toString())
            require(upload.code in 200..201) { apiError("上传工程失败", upload) }
            uploadedSha = JSONObject(upload.body).getJSONObject("content").getString("sha")

            onStatus("工程已上传，启动 GitHub Actions 编译…")
            val dispatchBody = JSONObject().put("ref", "main").put(
                "inputs", JSONObject().put("source_zip_path", uploadPath).put("artifact_name", artifactName)
            )
            val dispatch = request("POST", "$base/actions/workflows/android-build.yml/dispatches", token, dispatchBody.toString())
            require(dispatch.code == 204) { apiError("启动云端构建失败", dispatch) }

            onStatus("已启动构建，等待 GitHub runner…")
            val since = Instant.ofEpochMilli(start - 120_000L)
            var run: JSONObject? = null
            for (i in 0 until 40) {
                val list = request("GET", "$base/actions/runs?branch=main&event=workflow_dispatch&per_page=20", token)
                require(list.code == 200) { apiError("读取构建任务失败", list) }
                val runs = JSONObject(list.body).optJSONArray("workflow_runs") ?: JSONArray()
                run = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
                    .filter {
                        it.optString("event") == "workflow_dispatch" &&
                            it.optString("name") == "Build Android project" &&
                            runCatching { Instant.parse(it.optString("created_at")).isAfter(since) }.getOrDefault(false)
                    }.maxByOrNull { it.optLong("id") }
                if (run != null) break
                delay(2000)
            }
            val selectedRun = requireNotNull(run) {
                "GitHub 接受了构建请求，但没有返回运行记录。请检查仓库 Actions 页面。"
            }
            val runId = selectedRun.getLong("id")
            val runUrl = selectedRun.optString("html_url")
            var completed: JSONObject? = null
            for (i in 0 until 540) {
                val state = request("GET", "$base/actions/runs/$runId", token)
                require(state.code == 200) { apiError("读取构建状态失败", state) }
                val current = JSONObject(state.body)
                if (current.optString("status") == "completed") {
                    completed = current
                    break
                }
                onStatus("云端正在编译 APK…（任务 #$runId）")
                delay(5000)
            }
            val result = requireNotNull(completed) { "云端构建等待超时。任务地址：$runUrl" }
            if (result.optString("conclusion") != "success")
                throw IllegalStateException("云端构建结果：${result.optString("conclusion")}。任务地址：$runUrl")

            onStatus("编译成功，正在下载 APK…")
            val response = request("GET", "$base/actions/runs/$runId/artifacts", token)
            require(response.code == 200) { apiError("读取构建产物失败", response) }
            val artifacts = JSONObject(response.body).optJSONArray("artifacts") ?: JSONArray()
            val artifact = (0 until artifacts.length()).mapNotNull { artifacts.optJSONObject(it) }
                .firstOrNull { it.optString("name") == artifactName && !it.optBoolean("expired") }
                ?: throw IllegalStateException("构建成功，但没有找到 APK Artifact。任务地址：$runUrl")
            val artifactZip = File(folder, "artifact-$runId.zip")
            downloadArtifact(artifact.optString("archive_download_url"), token, artifactZip)
            val extracted = File(folder, "artifact-$runId").apply { mkdirs() }
            extractArtifact(artifactZip, extracted)
            val apk = extracted.walkTopDown().firstOrNull { it.isFile && it.extension.equals("apk", true) }
                ?: throw IllegalStateException("构建产物 ZIP 中没有 APK 文件。")
            val finalApk = File(folder, apk.name)
            apk.copyTo(finalApk, overwrite = true)
            onStatus("APK 已生成：${finalApk.name}（${finalApk.length() / 1024} KB）")
            finalApk
        } finally {
            uploadedSha?.let { deleteUpload(base, uploadPath, token, it) }
            sourceZip.delete()
        }
    }

    private fun request(method: String, url: String, token: String, body: String? = null): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("User-Agent", "APK-Forge-Android")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun apiError(title: String, result: HttpResult): String {
        val detail = runCatching { JSONObject(result.body).optString("message") }.getOrDefault("")
        return "$title（HTTP ${result.code}）：${detail.ifBlank { result.body.take(300) }}"
    }

    private fun deleteUpload(base: String, path: String, token: String, sha: String) {
        runCatching {
            val body = JSONObject().put("message", "APK Forge: delete temporary project ZIP")
                .put("sha", sha).put("branch", "main").toString()
            request("DELETE", "$base/contents/$path", token, body)
        }
    }

    private fun downloadArtifact(url: String, token: String, destination: File) {
        val first = URL(url).openConnection() as HttpURLConnection
        first.instanceFollowRedirects = false
        first.connectTimeout = 20_000
        first.readTimeout = 120_000
        first.setRequestProperty("Authorization", "Bearer $token")
        first.setRequestProperty("Accept", "application/vnd.github+json")
        val target: String
        try {
            val code = first.responseCode
            target = if (code in 300..399) first.getHeaderField("Location") ?: error("Artifact 下载地址为空")
                else if (code in 200..299) url else error("Artifact 下载失败（HTTP $code）")
            if (code in 200..299) {
                first.inputStream.use { input -> destination.outputStream().buffered().use { input.copyTo(it) } }
                return
            }
        } finally {
            first.disconnect()
        }
        val redirected = URL(target).openConnection() as HttpURLConnection
        redirected.connectTimeout = 20_000
        redirected.readTimeout = 120_000
        try {
            require(redirected.responseCode in 200..299) { "Artifact 文件下载失败（HTTP ${redirected.responseCode}）" }
            var bytes = 0L
            redirected.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= MAX_ARTIFACT_BYTES) { "Artifact 超过 1 GiB 安全上限" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            redirected.disconnect()
        }
    }

    private fun extractArtifact(zipFile: File, destination: File) {
        val prefix = destination.canonicalPath + File.separator
        var count = 0
        var total = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(destination, entry.name)
                require(out.canonicalPath.startsWith(prefix)) { "Artifact ZIP 包含越界路径" }
                if (entry.isDirectory) out.mkdirs() else {
                    count++
                    require(count <= 1000) { "Artifact 文件数量异常" }
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_ARTIFACT_BYTES) { "Artifact 解压内容超过 1 GiB" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
