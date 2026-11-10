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
import java.net.URLEncoder
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

        val base = "$API/repos/$owner/$repository"
        val repoResponse = request("GET", base, token)
        require(repoResponse.code == 200) { apiError("无法读取目标仓库", repoResponse) }
        val repoInfo = JSONObject(repoResponse.body)
        require(repoInfo.optBoolean("private")) {
            "出于源码隐私保护，APK Forge 拒绝向 Public 仓库上传工程。请新建或选择 Private 仓库。"
        }
        val defaultBranch = repoInfo.optString("default_branch", "main")
        val encodedBranch = URLEncoder.encode(defaultBranch, "UTF-8").replace("+", "%20")

        val folder = File(cacheDir, "remote-build").apply { mkdirs() }
        val sourceZip = File(folder, "project-${System.currentTimeMillis()}.zip")
        ProjectArchive.create(project.root, sourceZip)
        val start = System.currentTimeMillis()
        val buildBranch = "apkforge-build-$start"
        val uploadPath = ".apkforge/uploads/${sourceZip.name}"
        val artifactName = "apkforge-debug-$start"
        var branchCreated = false
        var dispatchAccepted = false
        var runFinished = false

        try {
            onStatus("目标仓库为 Private，正在创建临时构建分支…")
            val mainRef = request("GET", "$base/git/ref/heads/$encodedBranch", token)
            require(mainRef.code == 200) { apiError("读取默认分支失败", mainRef) }
            val baseSha = JSONObject(mainRef.body).getJSONObject("object").getString("sha")
            val branchBody = JSONObject().put("ref", "refs/heads/$buildBranch").put("sha", baseSha)
            val branchResponse = request("POST", "$base/git/refs", token, branchBody.toString())
            require(branchResponse.code == 201) { apiError("创建临时分支失败", branchResponse) }
            branchCreated = true

            onStatus("正在准备工程压缩包：${sourceZip.length() / 1024} KB")
            val uploadBody = JSONObject()
                .put("message", "APK Forge: temporary Android project upload")
                .put("branch", buildBranch)
                .put("content", Base64.encodeToString(sourceZip.readBytes(), Base64.NO_WRAP))
            onStatus("正在上传到临时私有分支…")
            val upload = request("PUT", "$base/contents/$uploadPath", token, uploadBody.toString())
            require(upload.code in 200..201) { apiError("上传工程失败", upload) }

            onStatus("工程已上传，启动 GitHub Actions 编译…")
            val dispatchBody = JSONObject().put("ref", buildBranch).put(
                "inputs", JSONObject().put("source_zip_path", uploadPath).put("artifact_name", artifactName)
            )
            val dispatch = request("POST", "$base/actions/workflows/android-build.yml/dispatches", token, dispatchBody.toString())
            require(dispatch.code == 204) { apiError("启动云端构建失败", dispatch) }
            dispatchAccepted = true

            onStatus("已启动构建，等待 GitHub runner…")
            val since = Instant.ofEpochMilli(start - 120_000L)
            var run: JSONObject? = null
            for (i in 0 until 60) {
                val list = request("GET", "$base/actions/runs?branch=$buildBranch&event=workflow_dispatch&per_page=20", token)
                require(list.code == 200) { apiError("读取构建任务失败", list) }
                val runs = JSONObject(list.body).optJSONArray("workflow_runs") ?: JSONArray()
                run = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
                    .filter {
                        it.optString("event") == "workflow_dispatch" &&
                            it.optString("head_branch") == buildBranch &&
                            runCatching { Instant.parse(it.optString("created_at")).isAfter(since) }.getOrDefault(false)
                    }.maxByOrNull { it.optLong("id") }
                if (run != null) break
                delay(2000)
            }
            val selectedRun = requireNotNull(run) {
                "GitHub 已接受构建请求，但没有返回运行记录。临时分支保留以便排查，请检查 Actions 页面。"
            }
            val runId = selectedRun.getLong("id")
            val runUrl = selectedRun.optString("html_url")
            var completed: JSONObject? = null
            for (i in 0 until 720) {
                val state = request("GET", "$base/actions/runs/$runId", token)
                require(state.code == 200) { apiError("读取构建状态失败", state) }
                val current = JSONObject(state.body)
                if (current.optString("status") == "completed") {
                    completed = current
                    runFinished = true
                    break
                }
                onStatus("云端正在编译 APK…（任务 #$runId）")
                delay(5000)
            }
            val result = requireNotNull(completed) { "云端构建等待超时。临时分支保留以便排查。任务地址：$runUrl" }
            if (result.optString("conclusion") != "success")
                throw IllegalStateException("云端构建结果：${result.optString("conclusion")}。任务地址：$runUrl")

            onStatus("编译成功，正在下载 APK…")
            val artifactsResponse = request("GET", "$base/actions/runs/$runId/artifacts", token)
            require(artifactsResponse.code == 200) { apiError("读取构建产物失败", artifactsResponse) }
            val artifacts = JSONObject(artifactsResponse.body).optJSONArray("artifacts") ?: JSONArray()
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
            if (branchCreated && (!dispatchAccepted || runFinished)) deleteBranch(base, buildBranch, token)
            sourceZip.delete()
        }
    }

    private fun request(method: String, url: String, token: String, body: String? = null): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("User-Agent", "APK-Forge-Android")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(title: String, result: HttpResult): String {
        val message = runCatching { JSONObject(result.body).optString("message") }.getOrDefault("")
        return "$title（HTTP ${result.code}）：${message.ifBlank { result.body.take(300) }}"
    }

    private fun deleteBranch(base: String, branch: String, token: String) {
        runCatching { request("DELETE", "$base/git/refs/heads/$branch", token) }
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
                var total = 0L
                first.inputStream.buffered().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_ARTIFACT_BYTES) { "Artifact 超过 1 GiB 安全上限" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
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
            var total = 0L
            redirected.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_ARTIFACT_BYTES) { "Artifact 超过 1 GiB 安全上限" }
                        output.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            redirected.disconnect()
        }
    }

    private fun extractArtifact(zipFile: File, destination: File) {
        val prefix = destination.canonicalPath + File.separator
        var entries = 0
        var total = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(destination, entry.name)
                require(out.canonicalPath.startsWith(prefix)) { "Artifact ZIP 包含越界路径" }
                if (entry.isDirectory) out.mkdirs() else {
                    entries++
                    require(entries <= 1000) { "Artifact 文件数量异常" }
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_ARTIFACT_BYTES) { "Artifact 解压内容超过 1 GiB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
