package com.apkforge

object AiPromptGenerator {
    fun generate(report: ProjectReport): String = buildString {
        appendLine("你现在是资深 Android 构建工程师。请修复我提供的 APK Forge 工程，目标是生成可编译的 Android APK。")
        appendLine()
        appendLine("【强制规则】")
        appendLine("1. 先完整扫描模块、源码、资源、Manifest、Gradle 插件和依赖。")
        appendLine("2. 不要只修改版本号或创建空文件；缺失功能必须补齐真实实现。")
        appendLine("3. 保留现有 UI、业务逻辑、包名和已实现功能，不要无故重写。")
        appendLine("4. 只修复有证据的问题；不确定的地方先说明，不要猜测。")
        appendLine("5. 检查 AGP、Gradle Wrapper、Kotlin、JDK、compileSdk、minSdk 和 namespace 兼容性。")
        appendLine("6. 检查 Manifest 组件、权限、intent-filter、android:exported 和资源引用。")
        appendLine("7. 检查源码包名、import、类引用、资源 ID 和依赖声明。")
        appendLine("8. 不要把本机 local.properties 的 SDK 路径写入最终工程。")
        appendLine("9. 输出新增或修改文件的完整内容，不得用省略号代替代码。")
        appendLine()
        appendLine("【构建关键词】PROJECT_SCAN DEPENDENCY_CHECK MANIFEST_REPAIR GRADLE_REPAIR SOURCE_COMPLETION RESOURCE_CHECK SDK_COMPATIBILITY BUILD_VALIDATION APK_EXPORT")
        appendLine()
        appendLine("【本次扫描结果】")
        appendLine("工程根目录：${report.root.name}")
        appendLine("扫描文件数：${report.fileCount}")
        appendLine("错误：${report.errorCount}；警告：${report.warningCount}；通过：${report.passCount}")
        report.issues.forEach { issue ->
            appendLine("- [${issue.severity}] ${issue.code}：${issue.title}")
            appendLine("  说明：${issue.detail}")
            issue.file?.let { appendLine("  文件：$it") }
        }
        appendLine()
        appendLine("【交付要求】")
        appendLine("A. 先给出缺失/冲突文件清单和修复方案。")
        appendLine("B. 按原目录结构输出新增或修改文件的完整内容。")
        appendLine("C. 检查所有引用是否存在，并检查 Gradle 任务。")
        appendLine("D. 执行 assembleDebug；若失败，依据真实日志继续修复，不能伪报成功。")
        appendLine("E. 最终提供 APK 路径、构建变更摘要和未解决问题。")
    }
}
