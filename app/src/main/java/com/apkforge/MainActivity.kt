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
    val buildSaved = remember { BuildSettingsStore.load(context) }
    val aiSaved = remember { AiEndpointStore.load(context) }

    var owner by remember { mutableStateOf(buildSaved.owner) }
    var repository by remember { mutableStateOf(buildSaved.repository) }
    var githubToken by remember { mutableStateOf(buildSaved.token) }
    var aiEndpoint by remember { mutableStateOf(aiSaved.endpoint) }
    var aiModel by remember { mutableStateOf(aiSaved.model) }
    var aiKey by remember { mutableStateOf(aiSaved.apiKey) }
    var requirement by remember { mutableStateOf("") }

    var report by remember { mutableStateOf<ProjectReport?>(null) }
    var workspaceRoot by remember { mutableStateOf<File?>(null) }
    var draft by remember { mutableStateOf<AiProjectDraft?>(null) }
    var draftRoot by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("配置 AI 接口后，可以直接描述需求生成 Android 工程。") }
    var repairPrompt by remember { mutableStateOf("") }
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
                    workspaceRoot = it.root
                    draft = null
                    draftRoot = null
                    repairPrompt = AiPromptGenerator.generate(it)
                    status = "工程检查完成：发现 " + it.issues.size + " 项检查结果。"
                }.onFailure {
                    status = "导入失败：" + (it.message ?: "未知错误")
                    report = null
                    workspaceRoot = null
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
                else "保存失败：" + (savedResult.exceptionOrNull()?.message ?: "未知错误")
        }
        pendingApk = null
    }

    fun runAi(repairExisting: Boolean) {
        val selectedRoot = if (repairExisting) workspaceRoot else null
        if (repairExisting && selectedRoot == null) {
            status = "请先导入一个 Android 工程。"
            return
        }
        if (aiModel.isBlank() || aiKey.isBlank() || aiEndpoint.isBlank()) {
            status = "请先填写并保存 AI API 地址、模型名称和 API Key。"
            return
        }
        if (requirement.isBlank()) {
            status = "请先描述要制作或修复的 App 功能。"
            return
        }
        val targetRoot = selectedRoot ?: runCatching {
            AiProjectWorkspace.createNewProject(context)
        }.getOrElse {
            status = "无法创建工程目录：" + (it.message ?: "未知错误")
            return
        }
        runCatching { AiEndpointStore.save(context, aiEndpoint, aiModel, aiKey) }.getOrElse {
            status = "AI 配置保存失败：" + (it.message ?: "未知错误")
            return
        }
        busy = true
        draft = null
        status = "正在准备 AI 工程任务…"
        activity?.lifecycleScope?.launch {
            val result = runCatching {
                AiProjectClient.generateOrRepair(
                    endpoint = aiEndpoint.trim(),
                    model = aiModel.trim(),
                    apiKey = aiKey,
                    requirement = requirement,
                    existingRoot = selectedRoot
                ) { message ->
                    withContext(Dispatchers.Main) { status = message }
                }
            }
            result.onSuccess {
                draft = it
                draftRoot = targetRoot
                status = "AI 已生成 " + it.files.size + " 个候选文件，请检查后再应用。"
            }.onFailure {
                status = "AI 生成失败：" + (it.message ?: "未知错误")
            }
            busy = false
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF246BFD), secondary = Color(0xFF12A8A0),
        background = Color(0xFFF5F7FC), surface = Color.White
    )) {
        Scaffold(topBar = {
            TopAppBar(title = {
                Column {
                    Text("APK Forge", fontWeight = FontWeight.Bold)
                    Text("AI 工程生成 · 修复 · APK 构建", style = MaterialTheme.typography.labelSmall,
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
                            Text("把需求变成真正的 APK", color = Color.White,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("描述你要制作的应用，AI 生成完整工程文件；检查、应用后，由云端 Android 构建环境编译 APK。",
                                color = Color(0xFFD8E2F5), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Text("  导入已有 Android 工程 ZIP")
                            }
                        }
                    }
                }

                item {
                    SectionTitle("① AI 接口配置", "兼容 OpenAI Chat Completions 的服务；模型由你自己填写。")
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = aiEndpoint, onValueChange = { aiEndpoint = it },
                                label = { Text("API Endpoint") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = aiModel, onValueChange = { aiModel = it },
                                label = { Text("模型名称（不预设）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = aiKey, onValueChange = { aiKey = it },
                                label = { Text("AI API Key") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(onClick = {
                                val result = runCatching { AiEndpointStore.save(context, aiEndpoint, aiModel, aiKey) }
                                status = if (result.isSuccess) "AI 接口配置已保存，API Key 使用 Android Keystore 加密。"
                                    else "配置保存失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("保存 AI 配置")
                            }
                            Text("示例地址：https://api.openai.com/v1/chat/completions。使用其他兼容服务时，填写该服务的完整接口地址。API Key 只在你主动生成或修复时发送给该服务。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item {
                    SectionTitle("② 描述 App 需求", "需求越具体，AI 返回的工程越完整。")
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = requirement, onValueChange = { requirement = it },
                                label = { Text("例如：制作一个离线记账 App……") },
                                minLines = 4, maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { runAi(false) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) "AI 正在工作…" else "AI 从需求新建完整 App 工程")
                            }
                            OutlinedButton(
                                onClick = { runAi(true) }, enabled = !busy && report != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("AI 修复 / 扩展当前工程")
                            }
                        }
                    }
                }

                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("③ AI 使用方法", fontWeight = FontWeight.Bold)
                            Text("1. 填写 API 地址、模型名和密钥并保存。")
                            Text("2. 输入应用需求，点击“AI 从需求新建完整 App 工程”。")
                            Text("3. 查看 AI 返回的文件路径、摘要和注意事项。")
                            Text("4. 点击“应用 AI 文件”，再检查工程报告。")
                            Text("5. 设置 Private GitHub 构建仓库，点击远程构建并保存 APK。")
                            Text("也可以先导入 ZIP，再使用“AI 修复 / 扩展当前工程”。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                val pendingDraft = draft
                val targetRoot = draftRoot
                if (pendingDraft != null && targetRoot != null) {
                    item {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("AI 文件预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(pendingDraft.summary, style = MaterialTheme.typography.bodyMedium)
                                Text("共 " + pendingDraft.files.size + " 个文件",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                pendingDraft.files.forEach { file ->
                                    Text("• " + file.path, style = MaterialTheme.typography.bodySmall)
                                }
                                if (pendingDraft.notes.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text("AI 注意事项", fontWeight = FontWeight.SemiBold)
                                    pendingDraft.notes.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
                                }
                                Button(onClick = {
                                    busy = true
                                    activity?.lifecycleScope?.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                AiProjectWorkspace.applyDraft(targetRoot, pendingDraft)
                                                ProjectAnalyzer.analyze(
                                                    targetRoot, AiProjectWorkspace.countFiles(targetRoot)
                                                )
                                            }
                                        }
                                        result.onSuccess {
                                            report = it
                                            workspaceRoot = targetRoot
                                            repairPrompt = AiPromptGenerator.generate(it)
                                            draft = null
                                            draftRoot = null
                                            status = "已应用 AI 文件并重新扫描工程。"
                                        }.onFailure {
                                            status = "应用 AI 文件失败：" + (it.message ?: "未知错误")
                                        }
                                        busy = false
                                    }
                                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("检查并应用 AI 文件")
                                }
                                OutlinedButton(onClick = {
                                    draft = null
                                    draftRoot = null
                                    status = "已丢弃这次 AI 候选文件。"
                                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("丢弃本次 AI 结果")
                                }
                            }
                        }
                    }
                }

                val current = report
                if (current != null) {
                    item { SectionTitle("④ 工程检查报告", current.root.name) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryCard("错误", current.errorCount.toString(), Color(0xFFD94141), Modifier.weight(1f))
                            SummaryCard("警告", current.warningCount.toString(), Color(0xFFE28A22), Modifier.weight(1f))
                            SummaryCard("通过", current.passCount.toString(), Color(0xFF168C65), Modifier.weight(1f))
                        }
                    }
                    items(current.issues) { IssueCard(it) }
                    item {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("AI 修复关键词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("PROJECT_SCAN · DEPENDENCY_CHECK · MANIFEST_REPAIR · GRADLE_REPAIR · SOURCE_COMPLETION · SDK_COMPATIBILITY · BUILD_VALIDATION",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("APK Forge AI Repair Prompt", repairPrompt))
                                    status = "工程修复提示词已复制。"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    Text("  复制工程修复提示词")
                                }
                            }
                        }
                    }
                    item { SectionTitle("⑤ GitHub 云端构建", "目标仓库必须是 Private，并启用 Actions。") }
                    item {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(owner, { owner = it }, label = { Text("仓库所有者") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(repository, { repository = it }, label = { Text("仓库名称") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(githubToken, { githubToken = it }, label = { Text("GitHub Fine-grained Token") },
                                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth())
                                OutlinedButton(onClick = {
                                    val result = runCatching { BuildSettingsStore.save(context, owner, repository, githubToken) }
                                    status = if (result.isSuccess) "构建仓库配置已保存。" else "保存失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("保存构建配置")
                                }
                                Text("Token 需要 Contents 读写、Actions 读写。APK Forge 会拒绝向 Public 仓库上传工程源码；工程 ZIP 限制 12 MiB。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(
                                    onClick = {
                                        if (githubToken.isBlank()) {
                                            status = "请先填写 GitHub Token。"
                                            return@Button
                                        }
                                        val settingsResult = runCatching {
                                            BuildSettingsStore.save(context, owner, repository, githubToken)
                                        }
                                        if (settingsResult.isFailure) {
                                            status = "构建设置保存失败：" + (settingsResult.exceptionOrNull()?.message ?: "未知错误")
                                            return@Button
                                        }
                                        busy = true
                                        activity?.lifecycleScope?.launch {
                                            val result = runCatching {
                                                GitHubActionsClient.buildAndDownload(
                                                    owner.trim(), repository.trim(), githubToken, current, context.cacheDir
                                                ) { message ->
                                                    withContext(Dispatchers.Main) { status = message }
                                                }
                                            }
                                            result.onSuccess { apk ->
                                                pendingApk = apk
                                                status = "云端编译成功，请选择 APK 保存位置。"
                                                saveApkLauncher.launch(apk.name)
                                            }.onFailure {
                                                status = "构建失败：" + (it.message ?: "未知错误")
                                            }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (busy) "正在构建…" else "开始远程构建 APK")
                                }
                            }
                        }
                    }
                } else if (workspaceRoot == null && !busy) {
                    item {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("还没有工程", fontWeight = FontWeight.SemiBold)
                                Text("可以直接输入需求，让 AI 从零生成 Android 项目。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    Text("注意：AI 写入代码不等于 APK 已构建。只有远程构建成功并实际下载到 APK 文件，才算生成完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
