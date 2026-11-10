package com.apkforge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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
    val activity = context.findActivity()
    val saved = remember { BuildSettingsStore.load(context) }
    var owner by remember { mutableStateOf(saved.owner) }
    var repository by remember { mutableStateOf(saved.repository) }
    var token by remember { mutableStateOf(saved.token) }
    var report by remember { mutableStateOf<ProjectReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("导入 AI 生成的 Android 工程 ZIP，先检查再构建。") }
    var prompt by remember { mutableStateOf("") }
    var pendingApk by remember { mutableStateOf<File?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "正在安全解压并分析工程…"
            activity?.lifecycleScope?.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val imported = ZipProjectImporter.import(context, uri)
                        ProjectAnalyzer.analyze(imported.root, imported.fileCount)
                    }
                }
                result.onSuccess {
                    report = it
                    prompt = AiPromptGenerator.generate(it)
                    status = "检查完成：发现 ${it.issues.size} 项需要关注的问题。"
                }.onFailure {
                    status = "导入失败：${it.message ?: "未知错误"}"
                    report = null
                }
                busy = false
            }
        }
    }

    val saveApkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        val source = pendingApk
        if (uri != null && source != null) {
            val savedResult = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("无法写入选定位置")
            }
            status = if (savedResult.isSuccess) "APK 已保存到你选择的位置。"
                else "保存失败：${savedResult.exceptionOrNull()?.message ?: "未知错误"}"
        }
        pendingApk = null
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF246BFD), secondary = Color(0xFF12A8A0),
        background = Color(0xFFF5F7FC), surface = Color.White
    )) {
        Scaffold(topBar = {
            TopAppBar(title = {
                Column {
                    Text("APK Forge", fontWeight = FontWeight.Bold)
                    Text("AI 工程检查与 APK 构建助手", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, navigationIcon = {
                Icon(Icons.Default.Android, contentDescription = null,
                    modifier = Modifier.padding(horizontal = 14.dp), tint = MaterialTheme.colorScheme.primary)
            })
        }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF12213D)),
                        shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("先检查，再修复，最后构建", color = Color.White,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("扫描 AI 工程中的 Gradle、Manifest、依赖、源码和资源问题，生成可直接交给 AI 的修复指令。",
                                color = Color(0xFFD8E2F5), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Text("  导入工程 ZIP")
                            }
                        }
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("GitHub 云端构建配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("目标仓库必须是 Private，并启用 Actions。你当前的 APK-Forge 仓库是 Public，应用会拒绝向公开仓库上传工程源码。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(owner, { owner = it }, label = { Text("仓库所有者") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(repository, { repository = it }, label = { Text("仓库名称") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(token, { token = it }, label = { Text("GitHub Personal Access Token") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = {
                                if (owner.isBlank() || repository.isBlank()) {
                                    status = "请填写 GitHub 用户名和仓库名称。"
                                } else {
                                    val savedResult = runCatching { BuildSettingsStore.save(context, owner, repository, token) }
                                    status = if (savedResult.isSuccess) "仓库配置已保存；Token 已使用 Android Keystore 加密保存。" else "保存失败：${savedResult.exceptionOrNull()?.message ?: "未知错误"}"
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("保存构建配置")
                            }
                            Text("Fine-grained Token 权限：Contents 读写、Actions 读写。每次构建使用临时分支，结束后删除；工程 ZIP 限制 12 MiB。",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            else Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(status, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                val current = report
                if (current != null) {
                    item {
                        Text("工程检查报告", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryCard("错误", current.errorCount.toString(), Color(0xFFD94141), Modifier.weight(1f))
                            SummaryCard("警告", current.warningCount.toString(), Color(0xFFE28A22), Modifier.weight(1f))
                            SummaryCard("通过", current.passCount.toString(), Color(0xFF168C65), Modifier.weight(1f))
                        }
                    }
                    item { Text("检查项目", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                    items(current.issues) { IssueCard(it) }
                    item {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("AI 修复关键词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("PROJECT_SCAN · DEPENDENCY_CHECK · MANIFEST_REPAIR · GRADLE_REPAIR · SOURCE_COMPLETION · BUILD_VALIDATION",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text("已根据本次工程问题生成修复指令。复制后交给 AI 编程助手，并要求它返回修改后的完整工程。",
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("APK Forge AI Repair Prompt", prompt))
                                    status = "AI 修复指令已复制。"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    Text("  复制完整 AI 修复指令")
                                }
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                if (token.isBlank()) {
                                    status = "请先填写并保存 GitHub Token。"
                                } else {
                                    runCatching { BuildSettingsStore.save(context, owner, repository, token) }.getOrElse {
                                        status = "无法保存构建设置：${it.message ?: "未知错误"}"
                                        return@Button
                                    }
                                    busy = true
                                    activity?.lifecycleScope?.launch {
                                        val result = runCatching {
                                            GitHubActionsClient.buildAndDownload(
                                                owner.trim(), repository.trim(), token, current, context.cacheDir
                                            ) { message ->
                                                withContext(Dispatchers.Main) { status = message }
                                            }
                                        }
                                        result.onSuccess { apk ->
                                            pendingApk = apk
                                            status = "云端编译成功，请选择 APK 保存位置。"
                                            saveApkLauncher.launch(apk.name)
                                        }.onFailure {
                                            status = "构建失败：${it.message ?: "未知错误"}"
                                        }
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && token.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) "正在构建…" else "开始远程构建 APK")
                        }
                    }
                    item {
                        Text("构建通过你配置的 GitHub Actions 仓库执行。请勿使用不受你控制的仓库或 Token 构建敏感工程。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 24.dp))
                    }
                } else if (!busy) {
                    item {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("还没有导入工程", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text("支持包含 settings.gradle(.kts) 的 Android Studio 工程 ZIP。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = tint, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
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
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(issue.code, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(issue.severity.name, style = MaterialTheme.typography.labelSmall, color = tint)
            }
            Text(issue.title, fontWeight = FontWeight.SemiBold)
            Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            issue.file?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}


private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
