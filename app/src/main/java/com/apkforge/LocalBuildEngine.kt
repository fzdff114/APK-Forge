package com.apkforge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class LocalBuildJob(
    val id: String,
    val statusUri: Uri,
    val logUri: Uri,
    val apkUri: Uri?
)

data class ApkVerification(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
    val manifestPresent: Boolean,
    val dexFiles: List<String>
)

private data class SharedFile(val uri: Uri, val path: String)

object LocalBuildEngine {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val EXTERNAL_COMMAND_SETUP =
        "mkdir -p ~/.termux && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings && termux-setup-storage"

    private const val DOWNLOAD_SUBFOLDER = "Download/APKForge/"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val MAX_PROJECT_BYTES = 512L * 1024L * 1024L
    private val skippedDirectories = setOf(
        ".git", ".gradle", ".idea", ".kotlin", "build", ".apkforge",
        "captures", "node_modules", ".venv"
    )
    private val sensitiveNames = setOf(
        "local.properties", "key.properties", ".env", ".env.local",
        "credentials.json"
    )
    private val sensitiveSuffixes = setOf(".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")

    fun isTermuxInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.isSuccess

    fun hasRunCommandPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    fun startToolchainSetup(context: Context): LocalBuildJob {
        requireReadyToCallTermux(context)
        require(Build.VERSION.SDK_INT >= 29) {
            "本机自动构建目前需要 Android 10 或更高版本，以安全交换工程和 APK 文件。"
        }
        val id = newJobId()
        val status = createDownloadFile(context, "$id.status", "text/plain")
        val log = createDownloadFile(context, "$id.log", "text/plain")
        val script = createDownloadFile(context, "$id-setup.sh", "text/x-shellscript")
        writeText(context, status.uri, "QUEUED\n")
        writeText(context, script.uri, toolchainSetupScript(id))
        dispatchTermux(context, script.path)
        return LocalBuildJob(id, status.uri, log.uri, null)
    }

    fun startBuild(context: Context, projectRoot: File): LocalBuildJob {
        requireReadyToCallTermux(context)
        require(Build.VERSION.SDK_INT >= 29) {
            "本机自动构建目前需要 Android 10 或更高版本。"
        }
        require(projectRoot.isDirectory) { "工程目录不存在。" }

        val id = newJobId()
        val archive = createDownloadFile(context, "$id-project.zip", "application/zip")
        writeProjectArchive(projectRoot, context, archive.uri)

        val status = createDownloadFile(context, "$id.status", "text/plain")
        val log = createDownloadFile(context, "$id.log", "text/plain")
        val apk = createDownloadFile(
            context, "$id-app-debug.apk", "application/vnd.android.package-archive"
        )
        val script = createDownloadFile(context, "$id-build.sh", "text/x-shellscript")
        writeText(context, status.uri, "QUEUED\n")
        writeText(context, script.uri, buildScript(id))
        dispatchTermux(context, script.path)
        return LocalBuildJob(id, status.uri, log.uri, apk.uri)
    }

    fun readStatus(context: Context, job: LocalBuildJob): String =
        readText(context, job.statusUri).trim()

    fun readLog(context: Context, job: LocalBuildJob, maxChars: Int = 24_000): String {
        val text = readText(context, job.logUri)
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    fun copyAndVerifyApk(context: Context, job: LocalBuildJob, destination: File): ApkVerification {
        val uri = requireNotNull(job.apkUri) { "此任务没有 APK 输出。" }
        destination.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取 Termux 输出的 APK。" }
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        require(destination.isFile && destination.length() > 0L) { "构建输出 APK 为空。" }

        val dexFiles: List<String>
        ZipFile(destination).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            require("AndroidManifest.xml" in names) { "APK 缺少 AndroidManifest.xml。" }
            dexFiles = names.filter { Regex("classes\\d*\\.dex").matches(File(it).name) }.sorted()
            require(dexFiles.isNotEmpty()) { "APK 缺少 classes*.dex，文件可能不是有效 APK。" }
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (stream.read(buffer) >= 0) Unit
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        destination.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return ApkVerification(destination, destination.length(), hash, true, dexFiles)
    }

    fun openTermux(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun requireReadyToCallTermux(context: Context) {
        check(isTermuxInstalled(context)) {
            "尚未安装 Termux 本地构建运行环境。请先安装官方 Termux，再返回 APK Forge。"
        }
        check(hasRunCommandPermission(context)) {
            "尚未授予 APK Forge「在 Termux 环境运行命令」权限。请在 Android 应用权限设置中允许。"
        }
    }

    private fun dispatchTermux(context: Context, scriptPath: String) {
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "$TERMUX_PACKAGE.RUN_COMMAND"
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", TERMUX_BASH)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", "bash \"$scriptPath\""))
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", true)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_COMMAND_LABEL", "APK Forge 本地构建")
        }
        context.startService(intent)
    }

    private fun createDownloadFile(context: Context, name: String, mime: String): SharedFile {
        check(Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_SUBFOLDER)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: error("无法在 Download/APKForge 创建文件：$name")
        val path = "${Environment.getExternalStorageDirectory().absolutePath}/$DOWNLOAD_SUBFOLDER$name"
        return SharedFile(uri, path)
    }

    private fun writeText(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "无法写入本地构建任务文件。" }
            output.writer(Charsets.UTF_8).use { it.write(text) }
        }
    }

    private fun readText(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
        }.getOrDefault("")

    private fun writeProjectArchive(rootDir: File, context: Context, uri: Uri) {
        val root = findProjectRoot(rootDir).canonicalFile
        var total = 0L
        val prefix = root.path + File.separator
        context.contentResolver.openOutputStream(uri, "wt").use { raw ->
            requireNotNull(raw) { "无法写入工程压缩包。" }
            ZipOutputStream(raw.buffered()).use { zip ->
                root.walkTopDown().onEnter { dir ->
                    dir == root || (dir.name !in skippedDirectories && !dir.isSymbolicLink())
                }.forEach { file ->
                    if (!file.isFile || file.isSymbolicLink()) return@forEach
                    if (file.name.lowercase() in sensitiveNames ||
                        file.extension.lowercase() in sensitiveSuffixes ||
                        file.extension.lowercase() in setOf("apk", "aab", "log", "iml")) return@forEach
                    val canonical = runCatching { file.canonicalPath }.getOrDefault("")
                    if (!canonical.startsWith(prefix)) return@forEach
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    if (relative.isBlank()) return@forEach
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_PROJECT_BYTES) {
                                "工程文件超过 512 MiB 安全上限。请先移除缓存或大型无关文件。"
                            }
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun findProjectRoot(root: File): File =
        root.walkTopDown().onEnter { it == root || it.name !in skippedDirectories }
            .maxDepth(5)
            .firstOrNull {
                it.isDirectory &&
                    (File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile)
            } ?: root

    private fun File.isSymbolicLink(): Boolean =
        runCatching { canonicalFile != absoluteFile }.getOrDefault(true)

    private fun newJobId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun commonScriptHeader(id: String): String {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val shared = "$root/$DOWNLOAD_SUBFOLDER"
        return """
            #!/data/data/com.termux/files/usr/bin/bash
            set -Eeuo pipefail
            PREFIX=/data/data/com.termux/files/usr
            SHARED='__SHARED_ROOT__'
            JOB_ID='__JOB_ID__'
            STATUS="§SHARED/§JOB_ID.status"
            LOG="§SHARED/§JOB_ID.log"
            mkdir -p "§HOME/.apkforge/jobs/§JOB_ID"
            : > "§LOG"
            exec >> "§LOG" 2>&1
            on_exit() {
              code=§?
              if [ "§code" -ne 0 ]; then
                echo "Build or setup failed (exit §code)"
                printf 'FAILED\n%s\n' "§code" > "§STATUS"
              fi
            }
            trap on_exit EXIT
            echo "APK Forge local task: §JOB_ID"
            echo "Termux prefix: §PREFIX"
            printf 'RUNNING\n' > "§STATUS"
        """.trimIndent()
            .replace("__SHARED_ROOT__", shared)
            .replace("__JOB_ID__", id)
            .replace("§", "$") + "\n"
    }

    private fun toolchainSetupScript(id: String): String {
        return commonScriptHeader(id) + """
            export JAVA_HOME="§PREFIX/lib/jvm/java-21-openjdk"
            export ANDROID_HOME="§PREFIX/opt/android-sdk"
            export ANDROID_SDK_ROOT="§ANDROID_HOME"
            export PATH="§JAVA_HOME/bin:§ANDROID_HOME/platform-tools:§PREFIX/bin:§PATH"

            echo "[1/4] Updating Termux package lists..."
            pkg update -y
            echo "[2/4] Installing JDK, Gradle and Android build tools..."
            pkg install -y python git openjdk-21 gradle android-sdk apksigner d8 zipalign unzip

            if [ ! -x "§JAVA_HOME/bin/java" ]; then
              JAVA_BIN="§(command -v java)"
              JAVA_HOME="§(dirname "§(dirname "§(readlink -f "§JAVA_BIN")")")"
              export JAVA_HOME
            fi
            export ANDROID_HOME="§PREFIX/opt/android-sdk"
            export ANDROID_SDK_ROOT="§ANDROID_HOME"
            export PATH="§JAVA_HOME/bin:§ANDROID_HOME/platform-tools:§PREFIX/bin:§PATH"

            echo "[3/4] Checking Android SDK manager..."
            SDKMANAGER="§(command -v sdkmanager || true)"
            if [ -z "§SDKMANAGER" ]; then
              echo "sdkmanager is missing after android-sdk installation."
              echo "Install Android command-line tools for Termux, then retry."
              exit 20
            fi
            mkdir -p "§ANDROID_HOME"
            set +o pipefail
            yes | "§SDKMANAGER" --sdk_root="§ANDROID_HOME" --licenses
            set -o pipefail
            "§SDKMANAGER" --sdk_root="§ANDROID_HOME" \
              "platforms;android-35" "build-tools;36.1.0" "platform-tools"

            echo "[4/4] Verifying native Android build tools..."
            test -x "§JAVA_HOME/bin/java"
            command -v gradle
            test -x "§ANDROID_HOME/build-tools/36.1.0/aapt2"
            command -v zipalign
            command -v apksigner
            java -version
            gradle --version | head -n 8
            echo "Local Android build environment is ready."
            printf 'SUCCESS\n' > "§STATUS"
            trap - EXIT
        """.trimIndent().replace("§", "$") + "\n"
    }

    private fun buildScript(id: String): String {
        return commonScriptHeader(id) + """
            export JAVA_HOME="§PREFIX/lib/jvm/java-21-openjdk"
            export ANDROID_HOME="§PREFIX/opt/android-sdk"
            export ANDROID_SDK_ROOT="§ANDROID_HOME"
            export PATH="§JAVA_HOME/bin:§ANDROID_HOME/platform-tools:§PREFIX/bin:§PATH"
            export GRADLE_OPTS="-Xmx1536m -Dorg.gradle.daemon=false -Dfile.encoding=UTF-8"

            JOB_HOME="§HOME/.apkforge/jobs/§JOB_ID"
            PROJECT_DIR="§JOB_HOME/project"
            ARCHIVE="§SHARED/§JOB_ID-project.zip"
            APK_OUT="§SHARED/§JOB_ID-app-debug.apk"
            rm -rf "§PROJECT_DIR"
            mkdir -p "§PROJECT_DIR"

            echo "[1/6] Checking local toolchain..."
            test -x "§JAVA_HOME/bin/java" || { echo "JDK missing. Initialize the local build environment first."; exit 21; }
            test -d "§ANDROID_HOME/platforms/android-35" || { echo "Android SDK 35 missing. Initialize the local build environment first."; exit 22; }
            test -x "§ANDROID_HOME/build-tools/36.1.0/aapt2" || { echo "Native aapt2 missing. Initialize the local build environment first."; exit 23; }

            echo "[2/6] Extracting generated Android project..."
            unzip -t "§ARCHIVE" >/dev/null
            unzip -q "§ARCHIVE" -d "§PROJECT_DIR"
            ROOT="§PROJECT_DIR"
            SETTINGS="§(find "§PROJECT_DIR" -maxdepth 6 -type f -name 'settings.gradle*' -print -quit)"
            if [ -n "§SETTINGS" ]; then ROOT="§(dirname "§SETTINGS")"; fi
            cd "§ROOT"
            test -f settings.gradle -o -f settings.gradle.kts || { echo "Gradle settings file missing."; exit 24; }

            echo "[3/6] Preparing Android SDK and native aapt2..."
            printf 'sdk.dir=%s\n' "§ANDROID_HOME" > local.properties
            {
              echo "android.aapt2FromMavenOverride=§ANDROID_HOME/build-tools/36.1.0/aapt2"
              echo "android.overridePathCheck=true"
              echo "org.gradle.daemon=false"
              echo "org.gradle.parallel=false"
              echo "org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
              echo "kotlin.compiler.execution.strategy=in-process"
            } >> gradle.properties

            echo "[4/6] Building debug APK locally with Gradle..."
            if [ -f gradle/wrapper/gradle-wrapper.jar ] && [ -f gradle/wrapper/gradle-wrapper.properties ] && [ -f gradlew ]; then
              bash ./gradlew --no-daemon --stacktrace assembleDebug
            else
              gradle --no-daemon --stacktrace assembleDebug
            fi

            echo "[5/6] Locating and validating APK..."
            APK="§(find . -type f -path '*/build/outputs/apk/debug/*.apk' -print | head -n 1)"
            test -n "§APK" || { echo "Gradle succeeded but no debug APK was found."; exit 25; }
            unzip -t "§APK" >/dev/null
            unzip -Z1 "§APK" | grep -qx 'AndroidManifest.xml'
            unzip -Z1 "§APK" | grep -Eq '(^|/)classes[0-9]*\.dex
            sha256sum "§APK_OUT"

            echo "[6/6] APK created in Download/APKForge."
            printf 'SUCCESS\n' > "§STATUS"
            trap - EXIT
        """.trimIndent().replace("§", "$") + "\n"
    }
}

            apksigner verify --verbose "§APK"
            cp -f "§APK" "§APK_OUT"
            sha256sum "§APK_OUT"

            echo "[6/6] APK created in Download/APKForge."
            printf 'SUCCESS\n' > "§STATUS"
            trap - EXIT
        """.trimIndent().replace("§", "$") + "\n"
    }
}
