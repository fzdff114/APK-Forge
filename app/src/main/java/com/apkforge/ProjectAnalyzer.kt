package com.apkforge

import java.io.File

enum class Severity { ERROR, WARNING, PASS }
data class ProjectIssue(val code: String, val severity: Severity, val title: String, val detail: String, val file: String? = null)
data class ProjectReport(val root: File, val fileCount: Int, val issues: List<ProjectIssue>) {
    val errorCount get() = issues.count { it.severity == Severity.ERROR }
    val warningCount get() = issues.count { it.severity == Severity.WARNING }
    val passCount get() = issues.count { it.severity == Severity.PASS }
}

object ProjectAnalyzer {
    private val ignored = setOf(".git", ".gradle", "build", ".idea")

    fun analyze(importRoot: File, fileCount: Int): ProjectReport {
        val root = findProjectRoot(importRoot)
        val issues = mutableListOf<ProjectIssue>()
        val settings = listOf("settings.gradle.kts", "settings.gradle")
            .map { File(root, it) }.firstOrNull { it.isFile }
        if (settings == null) issues += ProjectIssue("PROJECT_SCAN", Severity.ERROR,
            "缺少 Gradle 项目入口", "没有找到 settings.gradle 或 settings.gradle.kts。请确认 ZIP 包含完整 Android 工程。")
        else issues += ProjectIssue("PROJECT_SCAN", Severity.PASS, "已找到 Gradle 项目入口",
            "工程入口文件存在。", settings.relativeTo(root).path)

        val gradleFiles = root.walkTopDown().onEnter { it == root || it.name !in ignored }
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }.toList()
        val appBuild = gradleFiles.firstOrNull {
            val text = it.readTextSafe()
            text.contains("com.android.application") || text.contains("com.android.library")
        }
        if (appBuild == null) issues += ProjectIssue("GRADLE_REPAIR", Severity.ERROR,
            "没有识别到 Android 模块", "未发现 com.android.application 或 com.android.library 插件声明。")
        else {
            issues += ProjectIssue("GRADLE_REPAIR", Severity.PASS, "已识别 Android 模块",
                "找到 Android Gradle 插件声明。", appBuild.relativeTo(root).path)
            val body = appBuild.readTextSafe()
            if (!Regex("""compileSdk(?:Version)?\s*(?:=\s*)?\d+""").containsMatchIn(body))
                issues += ProjectIssue("SDK_COMPATIBILITY", Severity.WARNING, "未能识别 compileSdk",
                    "可能使用变量或版本目录。请确认 compileSdk 已配置。", appBuild.relativeTo(root).path)
            if (!body.contains("namespace"))
                issues += ProjectIssue("GRADLE_REPAIR", Severity.WARNING, "未发现 namespace",
                    "较新的 Android Gradle Plugin 通常要求显式声明 namespace。", appBuild.relativeTo(root).path)
        }

        val manifest = root.walkTopDown().onEnter { it == root || it.name !in ignored }
            .firstOrNull { it.isFile && it.name == "AndroidManifest.xml" &&
                it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
        if (manifest == null) issues += ProjectIssue("MANIFEST_REPAIR", Severity.ERROR,
            "缺少主 Manifest", "未找到模块 src/main/AndroidManifest.xml。")
        else {
            issues += ProjectIssue("MANIFEST_REPAIR", Severity.PASS, "已找到 AndroidManifest.xml",
                "主清单文件存在。", manifest.relativeTo(root).path)
            val body = manifest.readTextSafe()
            if (body.contains("intent-filter") && !Regex("""android:exported\s*=""").containsMatchIn(body))
                issues += ProjectIssue("MANIFEST_REPAIR", Severity.WARNING, "检查 android:exported",
                    "清单含 intent-filter，请逐个检查相应组件是否显式声明 exported。", manifest.relativeTo(root).path)
        }

        val sourceFiles = root.walkTopDown().onEnter { it == root || it.name !in ignored }.filter {
            it.isFile && (it.extension == "kt" || it.extension == "java") &&
                it.path.contains("${File.separator}src${File.separator}main${File.separator}")
        }.toList()
        if (sourceFiles.isEmpty()) issues += ProjectIssue("SOURCE_COMPLETION", Severity.ERROR,
            "没有发现主源码", "src/main/java 或 src/main/kotlin 中没有 Kotlin/Java 文件。不要创建空文件代替实现。")
        else issues += ProjectIssue("SOURCE_COMPLETION", Severity.PASS, "已发现主源码",
            "找到 ${sourceFiles.size} 个 Kotlin/Java 主源码文件。")

        val wrapper = File(root, "gradle/wrapper/gradle-wrapper.properties")
        if (!wrapper.isFile) issues += ProjectIssue("GRADLE_REPAIR", Severity.WARNING,
            "缺少 Gradle Wrapper 配置", "建议补齐与 Android Gradle Plugin 兼容的 Wrapper。")
        else issues += ProjectIssue("GRADLE_REPAIR", Severity.PASS, "Gradle Wrapper 配置存在",
            wrapper.readTextSafe().lineSequence().firstOrNull { it.startsWith("distributionUrl=") } ?: "未找到 distributionUrl。",
            wrapper.relativeTo(root).path)

        if (File(root, "local.properties").isFile)
            issues += ProjectIssue("PROJECT_SCAN", Severity.WARNING, "工程包含 local.properties",
                "其中可能有原电脑的 sdk.dir。不要把其他设备的 SDK 路径写入最终工程。", "local.properties")
        if (gradleFiles.isEmpty()) issues += ProjectIssue("DEPENDENCY_CHECK", Severity.ERROR,
            "缺少 Gradle 构建脚本", "没有找到 build.gradle 或 build.gradle.kts。")
        else issues += ProjectIssue("DEPENDENCY_CHECK", Severity.PASS, "已发现 Gradle 构建脚本",
            "共找到 ${gradleFiles.size} 个 Gradle 构建脚本。")

        return ProjectReport(root, fileCount, issues)
    }

    private fun findProjectRoot(dir: File): File =
        dir.walkTopDown().onEnter { it == dir || it.name !in ignored }.maxDepth(5)
            .firstOrNull { it.isDirectory && (File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile) }
            ?: dir

    private fun File.readTextSafe(): String = runCatching { readText(Charsets.UTF_8).take(500_000) }.getOrDefault("")
}
