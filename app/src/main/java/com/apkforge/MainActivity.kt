package com.apkforge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ApkForgeScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApkForgeScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val clipboard = LocalClipboardManager.current

    var prompt by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ProjectReport?>(null) }
    var projectRoot by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("先让 AI 编程代理生成完整工程，再导入 ZIP 进行本机编译。") }
    var logText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<LocalBuildJob?>(null) }
    var activeJobIsSetup by remember { mutableStateOf(false) }
    var environmentReady by remember {
        mutableStateOf(
            context.getSharedPreferences("apkforge_local_builder", Context.MODE_PRIVATE)
                .getBoolean("environment_ready", false)
        )
    }
    var termuxInstalled by remember { mutableStateOf(LocalBuildEngine.isTermuxInstalled(context)) }
    var runPermission by remember { mutableStateOf(LocalBuildEngine.hasRunCommandPermission(context)) }
    var verifiedApk by remember { mutableStateOf<File?>(null) }
    var verification by remember { mutableStateOf<ApkVerification?>(null) }
    var showBuildConfirmation by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "正在安全解压并检查 Android 工程…"
            activity?.lifecycleScope?.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val imported = ZipProjectImporter.import(context, uri)
                        ProjectAnalyzer.analyze(imported.root, imported.fileCount)
                    }
                }
                result.onSuccess {
                    report = it
                    projectRoot = it.root
                    verification = null
                    verifiedApk = null
                    status = "工程导入完成：${it.fileCount} 个文件，${it.errorCount} 个错误，${it.warningCount} 个警告。"
                }.onFailure {
                    report = null
                    projectRoot = null
                    status = "导入失败：${it.message ?: "未知错误"}"
                }
                busy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        runPermission = granted
        status = if (granted) {
            "Termux 命令权限已授予。还需要在 Termux 中启用外部命令并执行存储初始化。"
        } else {
            "未授予 Termux 命令权限，APK Forge 无法调用本地构建工具链。"
        }
    }

    val saveApkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        val source = verifiedApk
        if (uri != null && source != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("无法写入所选位置。")
            }
            status = if (result.isSuccess) "已将 APK 保存到所选位置。" else
                "保存 APK 失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
        }
    }

    LaunchedEffect(activeJob?.id) {
        val job = activeJob ?: return@LaunchedEffect
        var waitingSeconds = 0
        var finished = false
        while (waitingSeconds < 7200 && !finished) {
            delay(1500)
            waitingSeconds += 1
            val snapshot = withContext(Dispatchers.IO) {
                LocalBuildEngine.readStatus(context, job) to LocalBuildEngine.readLog(context, job)
            }
            if (snapshot.second.isNotBlank()) logText = snapshot.second
            when {
                snapshot.first.startsWith("SUCCESS") -> {
                    finished = true
                    if (activeJobIsSetup) {
                        environmentReady = true
                        context.getSharedPreferences("apkforge_local_builder", Context.MODE_PRIVATE)
                            .edit().putBoolean("environment_ready", true).apply()
                        status = "本地 Android 构建环境初始化完成。"
                    } else {
                        status = "Gradle 已完成，正在复制并校验 APK…"
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val file = File(context.cacheDir, "apkforge-outputs/${job.id}.apk")
                                val verified = LocalBuildEngine.copyAndVerifyApk(context, job, file)
                                verified to file
                            }
                        }
                        result.onSuccess { pair ->
                            verification = pair.first
                            verifiedApk = pair.second
                            status = "APK 构建成功并通过 ZIP、Manifest、DEX 与 SHA-256 检查。"
                        }.onFailure {
                            status = "APK 已生成，但二次校验失败：${it.message ?: "未知错误"}"
                        }
                    }
                }
                snapshot.first.startsWith("FAILED") -> {
                    finished = true
                    status = "本地任务失败。请查看下面的 Gradle/Termux 日志。"
                }
                waitingSeconds >= 60 && snapshot.first == "QUEUED" -> {
                    finished = true
                    status = "Termux 没有启动任务。请确认已设置 allow-external-apps=true、授予 RUN_COMMAND 权限并执行 termux-setup-storage。"
                }
            }
        }
        if (!finished) status = "任务仍在运行。可稍后重新打开 APK Forge 查看 Download/APKForge 中的日志和 APK。"
        activeJob = null
        busy = false
    }

    fun refreshRuntime() {
        termuxInstalled = LocalBuildEngine.isTermuxInstalled(context)
        runPermission = LocalBuildEngine.hasRunCommandPermission(context)
    }

    fun promptText(): String {
        val requirements = prompt.trim().ifBlank { "请根据我的描述创建一个完整的 Android 应用。" }
        return """
            你是一名 Android 工程师。请根据下面的需求创建一个真实、完整、可编译的 Android Studio 工程。

            【应用需求】
            §requirements

            【必须交付】
            1. 完整的 settings.gradle(.kts)、根 build.gradle(.kts)、gradle.properties。
            2. 完整 app 模块、AndroidManifest.xml、Kotlin/Java 源码和所有引用的资源。
            3. 配置相互兼容的 Gradle、AGP、Kotlin、JDK、compileSdk 和依赖版本。
            4. 实现真实功能、数据持久化、权限和错误处理；不能用空类、TODO、假按钮或仅有界面的占位实现。
            5. 确保工程目录完整，打包为一个 ZIP，ZIP 顶层包含 settings.gradle 或 settings.gradle.kts。
            6. 提供实际构建说明和已知限制。不要声称未执行的构建已经成功。

            优先使用 Kotlin + Jetpack Compose；最低 Android 版本建议 API 26；默认 compileSdk/targetSdk 35。
        """.trimIndent()
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF246BFD),
        secondary = Color(0xFF12A8A0),
        background = Color(0xFFF4F7FC),
        surface = Color.White
    )) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Default.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("APK Forge", fontWeight = FontWeight.Bold)
                            Text(
                                "AI 工程 → 本机 Gradle → APK",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                })
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF12213D)),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "把提示词变成可编译工程",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "APK Forge 不把源码上传到 GitHub。AI 编程代理负责生成完整工程文件，本应用负责在手机本地执行 Gradle、校验并导出 APK。",
                                color = Color(0xFFD8E2F5),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = prompt,
                                onValueChange = { prompt = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 8,
                                label = { Text("输入你的 Android 应用需求") },
                                placeholder = { Text("例如：创建一个离线记账 App，支持分类、搜索、月度统计和本地备份……") },
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF8CB4FF),
                                    unfocusedBorderColor = Color(0xFF8192B0),
                                    focusedLabelColor = Color(0xFFBBD2FF),
                                    unfocusedLabelColor = Color(0xFFBBD2FF),
                                    cursorColor = Color.White
                                )
                            )
                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(promptText()))
                                    status = "完整工程生成任务已复制。优先交给已连接 APK Forge MCP 的 AI 代理，让它直接写入本机工作区并调用本机编译；也可以让外部代理生成完整 ZIP 再导入。"
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy
                            ) {
                                Text("复制 AI 工程生成任务")
                            }
                        }
                    }
                }

                item {
                    SectionTitle("本地构建环境")
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            RuntimeStatusRow("Termux 运行环境", termuxInstalled, if (termuxInstalled) "已安装" else "未安装")
                            RuntimeStatusRow("RUN_COMMAND 权限", runPermission, if (runPermission) "已授权" else "未授权")
                            RuntimeStatusRow("Android SDK / JDK / Gradle", environmentReady, if (environmentReady) "已初始化" else "尚未初始化")
                            if (!termuxInstalled) {
                                Text(
                                    "本机编译使用 Termux 提供 ARM64 的 JDK、Gradle、Android SDK 和原生 aapt2。请先安装官方 Termux（无需 Google 服务）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases/latest")))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("打开 Termux 官方下载页")
                                }
                            } else {
                                OutlinedButton(onClick = {
                                    LocalBuildEngine.openTermux(context)
                                    status = "请在 Termux 执行一次性配置命令，然后返回 APK Forge。"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Terminal, contentDescription = null)
                                    Text("  打开 Termux")
                                }
                            }
                            if (termuxInstalled && !runPermission) {
                                Button(
                                    onClick = { permissionLauncher.launch(LocalBuildEngine.RUN_COMMAND_PERMISSION) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("授予 Termux 命令权限")
                                }
                            }
                            if (termuxInstalled && runPermission) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(LocalBuildEngine.EXTERNAL_COMMAND_SETUP))
                                        status = "命令已复制。打开 Termux 粘贴并运行，按提示授予存储访问权限。"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("复制 Termux 一次性配置命令")
                                }
                                Button(
                                    onClick = {
                                        runCatching { LocalBuildEngine.startToolchainSetup(context) }
                                            .onSuccess {
                                                activeJobIsSetup = true
                                                activeJob = it
                                                busy = true
                                                logText = ""
                                                status = "正在 Termux 中安装 JDK、Gradle 和 Android SDK；首次下载可能较大。"
                                            }
                                            .onFailure { status = "无法启动本地环境初始化：${it.message}" }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Build, contentDescription = null)
                                    Text("  初始化本地 Android 构建环境")
                                }
                            }
                            Text(
                                "首次设置需要 Termux 开启外部命令、存储访问，并下载 JDK / Gradle / Android SDK。后续生成 APK 不会使用 GitHub Actions。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { refreshRuntime() }, modifier = Modifier.align(Alignment.End)) {
                                Text("刷新环境状态")
                            }
                        }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("导入 AI 生成的工程")
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            },
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Text("  选择 ZIP")
                        }
                    }
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "导入完整 Android Studio 工程 ZIP。ZIP 中应包含 settings.gradle(.kts)、模块 Gradle 文件、Manifest、源码和资源。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Text("  导入 AI 工程 ZIP")
                            }
                            val current = report
                            if (current != null) {
                                Text(
                                    "工程：${current.root.name} · ${current.fileCount} 个文件",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallMetric("错误", current.errorCount.toString(), Color(0xFFD94141), Modifier.weight(1f))
                                    SmallMetric("警告", current.warningCount.toString(), Color(0xFFE28A22), Modifier.weight(1f))
                                    SmallMetric("通过", current.passCount.toString(), Color(0xFF168C65), Modifier.weight(1f))
                                }
                            } else {
                                Text("尚未导入工程", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (report != null) {
                    item { SectionTitle("工程检查报告") }
                    items(report!!.issues) { issue -> IssueCard(issue) }
                    item {
                        Button(
                            onClick = { showBuildConfirmation = true },
                            enabled = !busy && environmentReady && termuxInstalled && runPermission && projectRoot != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                            else Icon(Icons.Default.Build, contentDescription = null)
                            Text("  本机编译 APK")
                        }
                        if (!environmentReady) {
                            Text(
                                "请先完成上方「本地 Android 构建环境」初始化，构建按钮才会启用。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (busy) CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
                                else androidx.compose.material3.Icon(
                                    if (verification != null) Icons.Default.CheckCircle else Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = if (verification != null) Color(0xFF168C65) else MaterialTheme.colorScheme.primary
                                )
                                Text(status, modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (logText.isNotBlank()) {
                                Divider()
                                Text("构建日志", fontWeight = FontWeight.SemiBold)
                                Surface(
                                    color = Color(0xFF101827),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        logText.takeLast(12_000),
                                        modifier = Modifier.padding(12.dp),
                                        color = Color(0xFFD7E3F7),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 28,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (verification != null && verifiedApk != null) {
                    item {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("APK 已生成并验证", fontWeight = FontWeight.Bold, color = Color(0xFF168C65))
                                Text("大小：${verification!!.sizeBytes / (1024 * 1024)} MB")
                                Text("DEX：${verification!!.dexFiles.joinToString()}")
                                Text(
                                    "SHA-256：${verification!!.sha256}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { saveApkLauncher.launch("APK-Forge-app-debug.apk") },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("另存为 APK")
                                }
                                Text(
                                    "构建输出同时保存在手机 Download/APKForge 文件夹。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    if (showBuildConfirmation) {
        AlertDialog(
            onDismissRequest = { showBuildConfirmation = false },
            title = { Text("确认执行本地 Gradle 构建？") },
            text = {
                Text("Gradle 构建脚本和插件能够执行项目代码。只构建你信任的 AI 生成工程或自己检查过的项目。工程将通过 Termux 在本机编译，不会上传到 GitHub。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBuildConfirmation = false
                    val root = projectRoot
                    if (root == null) {
                        status = "请先导入 Android 工程。"
                    } else {
                        runCatching { LocalBuildEngine.startBuild(context, root) }
                            .onSuccess {
                                activeJobIsSetup = false
                                activeJob = it
                                busy = true
                                logText = ""
                                verification = null
                                verifiedApk = null
                                status = "工程已发送到 Termux，正在手机本地执行 Gradle assembleDebug…"
                            }
                            .onFailure { status = "无法启动本地构建：${it.message}" }
                    }
                }) { Text("确认构建") }
            },
            dismissButton = {
                TextButton(onClick = { showBuildConfirmation = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun RuntimeStatusRow(label: String, ready: Boolean, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = if (ready) Color(0xFF168C65) else Color(0xFFE28A22),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallMetric(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = tint, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun IssueCard(issue: ProjectIssue) {
    val tint = when (issue.severity) {
        Severity.ERROR -> Color(0xFFD94141)
        Severity.WARNING -> Color(0xFFE28A22)
        Severity.PASS -> Color(0xFF168C65)
    }
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(issue.code, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(issue.severity.name, style = MaterialTheme.typography.labelSmall, color = tint)
            }
            Text(issue.title, fontWeight = FontWeight.SemiBold)
            Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            issue.file?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
