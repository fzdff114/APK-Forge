# AI 生成与构建使用说明

APK Forge 使用 OpenAI-compatible Chat Completions 接口调用你自行选择的 AI 服务。它不会强制指定模型，也不要求安装 Google Play 服务。

## 1. 准备 AI 接口

在 APK Forge 的 AI 设置中填写：

- API Endpoint：完整的 Chat Completions 地址，例如 https://api.openai.com/v1/chat/completions；其他厂商请填写该厂商兼容的完整地址。
- Model：填写你账号实际可用的模型 ID。应用不替你固定模型名称。
- API Key：填写该服务提供的密钥。

点击“保存 AI 配置”。API Key 使用 Android Keystore 中的 AES-GCM 密钥加密后保存在应用私有设置中。不要把密钥发给其他人，也不要写进工程文件。

## 2. 从一句话生成 APK 工程

1. 打开 APK Forge，进入“描述 App 需求”。
2. 输入要制作的应用，例如：制作一个离线记账 App，支持分类、月度统计、导出 CSV，使用 Material 3，数据保存在本机。
3. 点击“AI 从需求新建完整 App 工程”。
4. 等待 AI 返回工程文件清单。此时文件尚未写入工程，也没有开始编译。
5. 检查 AI 摘要、文件路径和注意事项，点击“检查并应用 AI 文件”。
6. APK Forge 将文件写入新的应用工程目录，并重新扫描 Gradle、Manifest 和源码。
7. 配置 Private GitHub 构建仓库，点击“开始远程构建 APK”。
8. GitHub Actions 返回真实构建结果后，APK Forge 下载 APK，并让你选择保存位置。

## 3. 修复现有 AI 工程

1. 点击“导入已有 Android 工程 ZIP”，选择完整 Android Studio 项目。
2. 查看工程扫描报告。
3. 在需求输入框描述错误现象或要补充的功能。
4. 点击“AI 修复 / 扩展当前工程”。
5. 检查本次 AI 提议的修改文件，再点击“检查并应用 AI 文件”。
6. 重新扫描并执行远程构建。

AI 会读取工程中的部分 Gradle、Manifest、Kotlin/Java 和资源文本内容。只有你主动点击 AI 生成或修复后，这些内容才会发送到所填写的 AI 服务。不要导入或发送含有密码、令牌、签名密钥、私钥或客户数据的工程。

## 4. AI 构建关键词

- PROJECT_SCAN：扫描模块、入口、源码和资源。
- APP_REQUIREMENTS：把自然语言需求拆成功能和页面。
- PROJECT_SCAFFOLD：创建完整工程骨架。
- DEPENDENCY_CHECK：检查依赖是否真实存在、版本是否相容。
- MANIFEST_REPAIR：检查组件、权限和 exported。
- GRADLE_REPAIR：修复插件、Wrapper、仓库和模块配置。
- SOURCE_COMPLETION：补齐真实业务逻辑，不允许空类占位。
- RESOURCE_CHECK：检查资源文件和引用 ID。
- SDK_COMPATIBILITY：检查 JDK、Gradle、AGP、Kotlin 和 SDK 版本关系。
- BUILD_VALIDATION：根据真实 Gradle 构建结果验证。
- BUILD_ERROR_REPAIR：定位真实编译错误并提出源文件修复。
- APK_EXPORT：检查 APK 文件是否实际生成。

## 5. 安全与能力边界

AI 返回的文件先显示预览，只有你点击应用后才写入磁盘。应用会拒绝绝对路径、路径穿越、签名文件、本机 SDK 配置和 Gradle Wrapper 二进制，并保留被覆盖文本文件的备份。

APK Forge 不会执行 AI 输出的任意 Shell 命令。Gradle 构建脚本本身可能执行项目代码，所以只构建你信任并已审查的工程。AI 生成代码也可能有错误；成功写入文件不代表 APK 已编译成功。只有 GitHub Actions 构建成功且 APK Artifact 已下载，才算完成 APK 构建。

远程构建需要使用 Private GitHub 仓库。当前公开的 fzdff114/APK-Forge 仓库不能作为私有源码的构建上传目标。
