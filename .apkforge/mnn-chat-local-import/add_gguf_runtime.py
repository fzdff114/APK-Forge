#!/usr/bin/env python3
from pathlib import Path
import re, shutil, sys

root = Path(sys.argv[1]).resolve()
llama = Path(sys.argv[2]).resolve()
app = root / "app"
java = app / "src/main/java"
cpp = app / "src/main/cpp"

def copy(src, dst):
    if not src.is_file():
        raise SystemExit(f"Missing llama.cpp Android source: {src}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)

copy(llama / "examples/llama.android/lib/src/main/cpp/ai_chat.cpp", cpp / "ai_chat.cpp")
# Preserve native GGML diagnostics and retry file-mapping failures without mmap.
ai_chat_path = cpp / "ai_chat.cpp"
ai_chat = ai_chat_path.read_text(encoding="utf-8")
ai_chat = ai_chat.replace(
    "#include <string>\n#include <unistd.h>",
    "#include <string>\n#include <mutex>\n#include <algorithm>\n#include <cctype>\n#include <unistd.h>",
    1
)
log_anchor = """extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init"""
log_bridge = r'''static std::mutex g_load_log_mutex;
static std::string g_load_log;

static std::string load_log_snapshot() {
    std::lock_guard<std::mutex> guard(g_load_log_mutex);
    return g_load_log;
}

static void clear_load_log() {
    std::lock_guard<std::mutex> guard(g_load_log_mutex);
    g_load_log.clear();
}

static void aichat_capture_log_callback(enum ggml_log_level level, const char * text, void * user) {
    aichat_android_log_callback(level, text, user);
    if (text == nullptr || text[0] == '\0') return;
    std::lock_guard<std::mutex> guard(g_load_log_mutex);
    g_load_log.append(text);
    constexpr size_t MAX_CAPTURED_LOG = 32768;
    if (g_load_log.size() > MAX_CAPTURED_LOG) {
        g_load_log.erase(0, g_load_log.size() - MAX_CAPTURED_LOG);
    }
}

'''
if ai_chat.count(log_anchor) != 1:
    raise SystemExit("Could not locate native init anchor for GGUF error capture")
ai_chat = ai_chat.replace(log_anchor, log_bridge + log_anchor, 1)
if ai_chat.count("llama_log_set(aichat_android_log_callback, nullptr);") != 1:
    raise SystemExit("Could not locate llama.cpp Android log handler")
ai_chat = ai_chat.replace(
    "llama_log_set(aichat_android_log_callback, nullptr);",
    "llama_log_set(aichat_capture_log_callback, nullptr);",
    1
)
load_anchor = '''Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();'''
load_repl = '''Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    clear_load_log();
    llama_model_params model_params = llama_model_default_params();'''
if ai_chat.count(load_anchor) != 1:
    raise SystemExit("Could not locate native model load function")
ai_chat = ai_chat.replace(load_anchor, load_repl, 1)
old_model_load = '''    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}'''
new_model_load = r'''    auto *model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        // Some Android document providers reject file-backed mmap.
        std::string first_log = load_log_snapshot();
        std::string lower_log = first_log;
        std::transform(lower_log.begin(), lower_log.end(), lower_log.begin(),
            [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        const bool mapping_failure =
            lower_log.find("mmap") != std::string::npos ||
            lower_log.find("memory map") != std::string::npos ||
            lower_log.find("failed to map") != std::string::npos ||
            lower_log.find("operation not permitted") != std::string::npos ||
            lower_log.find("permission denied") != std::string::npos;
        if (mapping_failure) {
            LOGw("File mapping failed; retrying GGUF load with mmap disabled.");
            {
                std::lock_guard<std::mutex> guard(g_load_log_mutex);
                g_load_log.append("\nAndroid compatibility fallback: retrying with use_mmap=false.\n");
            }
            model_params.load_mode = LLAMA_LOAD_MODE_NONE;
            model_params.lazy_mode = LLAMA_LAZY_MODE_OFF;
            model = llama_model_load_from_file(model_path, model_params);
        }
    }
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        LOGe("GGUF model load failed; returning captured llama.cpp diagnostics to Java.");
        return 1;
    }
    g_model = model;
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_lastLoadError(JNIEnv *env, jobject) {
    const std::string details = load_log_snapshot();
    if (details.empty()) {
        return env->NewStringUTF("llama.cpp returned no model-load diagnostics. Check GGUF integrity and available RAM.");
    }
    return env->NewStringUTF(details.c_str());
}'''
if ai_chat.count(old_model_load) != 1:
    raise SystemExit("Could not locate native model load return anchor")
ai_chat = ai_chat.replace(old_model_load, new_model_load, 1)
ai_chat_path.write_text(ai_chat, encoding="utf-8")
upstream_logging = llama / "examples/llama.android/lib/src/main/cpp/logging.h"
copy(upstream_logging, cpp / "logging.h")
# ai_chat.cpp is compiled from the llama.cpp checkout, so its quoted include resolves
# beside that source file. Patch BOTH the copied app header and the actual included header.
old_filter = """static inline int ai_should_log(int prio) {
    return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);
}"""
new_filter = """static inline int ai_should_log(int prio) {
    return prio >= LOG_MIN_LEVEL;
}"""
for logging_path in (cpp / "logging.h", upstream_logging):
    logging = logging_path.read_text(encoding="utf-8")
    if logging.count(old_filter) != 1:
        raise SystemExit(f"Could not locate upstream Android-30 log filter in {logging_path}")
    logging_path.write_text(logging.replace(old_filter, new_filter, 1), encoding="utf-8")
print("Patched the actual llama.cpp include and the mirrored app logging.h for API 26+.")
copy(llama / "examples/llama.android/lib/src/main/java/com/arm/aichat/AiChat.kt", java / "com/arm/aichat/AiChat.kt")
copy(llama / "examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt", java / "com/arm/aichat/InferenceEngine.kt")
interface_path = java / "com/arm/aichat/InferenceEngine.kt"
interface_source = interface_path.read_text(encoding="utf-8")
old_exception = "class UnsupportedArchitectureException : Exception()"
new_exception = "class UnsupportedArchitectureException(message: String? = null) : Exception(message)"
if interface_source.count(old_exception) != 1:
    raise SystemExit("Could not patch GGUF load exception message")
interface_path.write_text(interface_source.replace(old_exception, new_exception, 1), encoding="utf-8")

engine_path = java / "com/arm/aichat/internal/InferenceEngineImpl.kt"
copy(llama / "examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt", engine_path)

engine = engine_path.read_text()
native_decl_anchor = """    @FastNative
    private external fun load(modelPath: String): Int

    @FastNative
    private external fun prepare(): Int"""
native_decl_replacement = """    @FastNative
    private external fun load(modelPath: String): Int

    @FastNative
    private external fun lastLoadError(): String

    @FastNative
    private external fun prepare(): Int"""
if engine.count(native_decl_anchor) != 1:
    raise SystemExit("Could not add native lastLoadError declaration")
engine = engine.replace(native_decl_anchor, native_decl_replacement, 1)
old_load_error = "if (it != 0) throw UnsupportedArchitectureException()"
new_load_error = """if (it != 0) {
                        val nativeDetails = lastLoadError().trim()
                        throw UnsupportedArchitectureException(
                            nativeDetails.ifBlank {
                                "llama.cpp 无法载入 GGUF。文件可能不完整、量化/架构不兼容，或设备内存不足。"
                            }
                        )
                    }"""
if engine.count(old_load_error) != 1:
    raise SystemExit("Could not update native model error propagation")
engine = engine.replace(old_load_error, new_load_error, 1)
anchor = """            }
    }

    /**
     * JNI methods"""
replacement = """            }

        internal fun releaseInstance(expected: InferenceEngineImpl) {
            synchronized(this) {
                if (instance === expected) instance = null
            }
        }
    }

    /**
     * JNI methods"""
if engine.count(anchor) != 1:
    raise SystemExit("Could not patch llama.cpp engine singleton")
engine = engine.replace(anchor, replacement, 1)
anchor = """        llamaScope.cancel()
    }
}"""
if engine.count(anchor) != 1:
    raise SystemExit("Could not patch llama.cpp engine destroy")
engine_path.write_text(engine.replace(anchor, """        llamaScope.cancel()
        Companion.releaseInstance(this)
    }
}""", 1))

importer = r'''package com.alibaba.mnnllm.android.modelist
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

object LocalGgufModelImporter {
    private val shardPattern = Regex("(?i)-(\\d{5})-of-(\\d{5})\\.gguf$")

    fun copyFromUri(context: Context, uri: Uri, displayName: String): File {
        if (shardPattern.containsMatchIn(displayName))
            throw IOException("检测到 GGUF 分片文件。请直接选择包含全部分片的文件夹，不要只选择单个分片。")
        val folder = createFolder(context, displayName)
        try {
            val target = File(folder, safeName(displayName))
            copyChecked(context, uri, target)
            return target
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    }

    fun copyFromTree(context: Context, uri: Uri): File? {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("无法读取所选文件夹。")
        val found = mutableListOf<Pair<DocumentFile, String>>()
        collect(tree, "", 0, found)
        if (found.isEmpty()) return null
        if (found.any { it.first.name?.contains("mmproj", true) == true })
            throw IOException("当前版本先支持纯文本 GGUF。所选目录包含 mmproj 多模态投影文件，视觉/多模态推理尚未接入。")
        val groups = found.groupBy { it.second.substringBeforeLast('/', "") }
        if (groups.size != 1) throw IOException("所选文件夹包含多个 GGUF 模型，请选择单个模型文件夹。")
        val files = groups.values.single()
        val names = files.map { it.first.name ?: throw IOException("GGUF 文件缺少名称。") }
        val shards = names.mapNotNull { name -> shardPattern.find(name)?.let { name to it } }
        val mainName: String
        if (shards.isNotEmpty()) {
            val total = shards.first().second.groupValues[2].toInt()
            if (shards.size != files.size || shards.size != total ||
                shards.any { it.second.groupValues[2].toInt() != total })
                throw IOException("GGUF 分片集合不完整或属于不同模型。")
            val indices = shards.map { it.second.groupValues[1].toInt() }.toSet()
            if (indices != (1..total).toSet()) throw IOException("GGUF 分片不完整：应有 1 到 $total 号分片。")
            mainName = shards.minBy { it.second.groupValues[1].toInt() }.first
        } else {
            if (files.size != 1) throw IOException("发现多个 GGUF 文件，但无法确认它们属于同一个分片模型。")
            mainName = names.single()
        }
        val folder = createFolder(context, mainName)
        try {
            files.forEach { (doc, _) ->
                val name = doc.name ?: throw IOException("GGUF 文件缺少名称。")
                copyChecked(
                    context, doc.uri, File(folder, safeName(name)),
                    doc.length().takeIf { it > 0L } ?: reportedSize(context, doc.uri)
                )
            }
            return File(folder, safeName(mainName))
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    }

    private fun collect(
        dir: DocumentFile, relative: String, depth: Int,
        out: MutableList<Pair<DocumentFile, String>>
    ) {
        if (depth > 4) return
        dir.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) collect(child, if (relative.isEmpty()) name else "$relative/$name", depth + 1, out)
            else if (child.isFile && name.endsWith(".gguf", true))
                out += child to if (relative.isEmpty()) name else "$relative/$name"
        }
    }

    private fun reportedSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun copyChecked(
        context: Context, uri: Uri, target: File, expectedSize: Long = reportedSize(context, uri)
    ) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开 GGUF 文件。")
        input.use { source ->
            val magic = ByteArray(4)
            var offset = 0
            while (offset < 4) {
                val n = source.read(magic, offset, 4 - offset)
                if (n < 0) throw IOException("GGUF 文件头不完整。")
                if (n > 0) offset += n
            }
            if (!magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46)))
                throw IOException("文件头不是 GGUF 格式。")
            FileOutputStream(target).use { output ->
                output.write(magic)
                source.copyTo(output, 1024 * 1024)
                output.fd.sync()
            }
        }
        val actualSize = target.length()
        if (actualSize <= 8L) throw IOException("GGUF 文件为空或已损坏。")
        if (expectedSize > 0L && actualSize != expectedSize) {
            throw IOException(
                "GGUF 复制不完整：来源文件 " + expectedSize + " 字节，实际保存 " +
                    actualSize + " 字节。请重新下载模型后再导入。"
            )
        }
    }

    private fun createFolder(context: Context, model: String): File {
        val parent = context.getExternalFilesDir("gguf_models") ?: File(context.filesDir, "gguf_models")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建 GGUF 模型目录。")
        val base = safeName(model.substringBeforeLast('.', model)).take(80)
        val folder = File(parent, base + "_" + UUID.randomUUID().toString().take(8))
        if (!folder.mkdirs()) throw IOException("无法创建 GGUF 模型存储目录。")
        return folder
    }

    private fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().trim('.')
        .take(180).ifBlank { "model.gguf" }
}
'''
p = java / "com/alibaba/mnnllm/android/modelist/LocalGgufModelImporter.kt"
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text(importer)

activity = r'''package com.alibaba.mnnllm.android.modelist
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.io.File

class GgufChatActivity : AppCompatActivity() {
    companion object { const val EXTRA_MODEL_PATH = "com.alibaba.mnnllm.android.EXTRA_GGUF_MODEL_PATH" }
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private var engine: InferenceEngine? = null
    private var generation: Job? = null
    private var ready = false
    private var metadataSummary = ""
    private var modelHeader = ""
    private var historyFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (path.isNullOrBlank() || !File(path).isFile) { finish(); return }
        val modelFile = File(path)
        historyFile = GgufChatHistoryStore.fileFor(filesDir, modelFile)
        buildUi(modelFile.name)
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    GgufChatHistoryStore.read(historyFile ?: return@withContext "")
                }
                if (saved.isNotBlank()) {
                    transcript.text = saved
                    scrollToBottom()
                }
                status.text = "正在校验 GGUF 元数据…"
                val metadata = withContext(Dispatchers.IO) {
                    modelFile.inputStream().buffered().use { stream ->
                        when (val result = GgufMetadataReader.inspect(stream)) {
                            is GgufMetadataReader.Result.Success -> result.metadata
                            is GgufMetadataReader.Result.Failure -> throw java.io.IOException(result.reason)
                        }
                    }
                }
                metadataSummary = "GGUF 版本：" + metadata.version +
                    "\n架构：" + (metadata.architecture ?: "未知") +
                    "\n张量：" + metadata.tensorCount +
                    "\n量化类型：" + metadata.tensorTypes.sorted().joinToString(", ") +
                    "\n文件大小：" + formatSize(modelFile.length())
                status.text = "GGUF 元数据校验通过，正在启动推理引擎…"
                val modelEngine = withContext(Dispatchers.Default) {
                    AiChat.getInferenceEngine(applicationContext)
                }
                engine = modelEngine
                val initial = modelEngine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }
                if (initial is InferenceEngine.State.Error) throw initial.exception
                withContext(Dispatchers.IO) {
                    modelEngine.loadModel(path)
                    modelEngine.setSystemPrompt("你是一个有帮助的中文 AI 助手。请清晰、准确地回答问题。")
                }
                ready = true
                status.text = "模型已加载，可以开始对话"
                send.isEnabled = true
                modelHeader = "模型已就绪：" + modelFile.name +
                    "\n文件大小：" + formatSize(modelFile.length()) +
                    "\nGGUF 版本：" + metadata.version +
                    "\n架构：" + (metadata.architecture ?: "未知") +
                    "\n张量：" + metadata.tensorCount +
                    "\n量化类型：" + metadata.tensorTypes.sorted().joinToString(", ") +
                    "\n\n"
                if (transcript.text.isNullOrEmpty()) transcript.append(modelHeader)
                persistHistory()
                scrollToBottom()
            } catch (e: Exception) {
                status.text = "模型加载失败，详细原因见下方"
                transcript.append(
                    "模型加载失败\n" + metadataSummary + "\n异常： " +
                        (e.message ?: e.javaClass.simpleName) + "\n\n"
                )
                persistHistory()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                lifecycleScope.launch {
                    generation?.cancelAndJoin()
                    persistHistory()
                    val current = engine
                    if (current != null) withContext(Dispatchers.IO) {
                        try {
                            val state = current.state.value
                            if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error)
                                current.cleanUp()
                        } catch (_: Exception) {}
                    }
                    finish()
                }
            }
        })
    }

    override fun onStop() {
        val target = historyFile
        val snapshot = if (::transcript.isInitialized) transcript.text.toString() else ""
        if (target != null && snapshot.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { GgufChatHistoryStore.write(target, snapshot) }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        generation?.cancel()
        try { engine?.destroy() } catch (_: Exception) {}
        engine = null
        val target = historyFile
        if (target != null && ::transcript.isInitialized) {
            runCatching { GgufChatHistoryStore.write(target, transcript.text.toString()) }
        }
        super.onDestroy()
    }

    private fun buildUi(modelName: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = modelName
            textSize = 17f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Button(this).apply {
            text = "模型库"
            setOnClickListener {
                startActivity(Intent(this@GgufChatActivity, GgufModelLibraryActivity::class.java))
            }
        })
        top.addView(Button(this).apply {
            text = "清空记录"
            setOnClickListener { confirmClearHistory() }
        })
        root.addView(top)
        status = TextView(this).apply { text = "正在初始化 GGUF 推理引擎…"; textSize = 13f }
        root.addView(status)
        transcript = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        transcriptScroll = ScrollView(this).apply { addView(transcript) }
        root.addView(transcriptScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply {
            hint = "输入消息"
            minLines = 1
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendPrompt()
                    true
                } else false
            }
        }
        send = Button(this).apply {
            text = "发送"
            isEnabled = false
            setOnClickListener { sendPrompt() }
        }
        stop = Button(this).apply {
            text = "停止"
            isEnabled = false
            setOnClickListener { generation?.cancel() }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(send)
            addView(stop)
        })
        setContentView(root)
    }

    private fun sendPrompt() {
        val message = input.text?.toString()?.trim().orEmpty()
        val active = engine
        if (message.isEmpty() || active == null || !ready || generation?.isActive == true) return
        input.text?.clear()
        transcript.append("你：" + message + "\n助手：")
        scrollToBottom()
        send.isEnabled = false
        stop.isEnabled = true
        generation = lifecycleScope.launch(Dispatchers.Default) {
            try {
                active.sendUserPrompt(message, 512).collect { token ->
                    withContext(Dispatchers.Main.immediate) {
                        transcript.append(token)
                        scrollToBottom()
                    }
                }
                withContext(Dispatchers.Main.immediate) { transcript.append("\n\n") }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main.immediate) { transcript.append("\n\n[已停止生成]\n\n") }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    status.text = "生成失败：" + (e.message ?: e.javaClass.simpleName)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    send.isEnabled = ready
                    stop.isEnabled = false
                }
                withContext(NonCancellable) { persistHistory() }
            }
        }
    }

    private suspend fun persistHistory() {
        val target = historyFile ?: return
        val content = withContext(Dispatchers.Main.immediate) {
            if (::transcript.isInitialized) transcript.text.toString() else ""
        }
        withContext(Dispatchers.IO) {
            GgufChatHistoryStore.write(target, content)
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空聊天记录")
            .setMessage("只清除当前模型的对话记录，不会删除模型文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val target = historyFile
                    if (target != null) withContext(Dispatchers.IO) {
                        GgufChatHistoryStore.clear(target)
                    }
                    transcript.text = modelHeader.ifBlank { "新对话\n" }
                    persistHistory()
                }
            }
            .show()
    }

    private fun scrollToBottom() {
        if (::transcriptScroll.isInitialized) {
            transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun formatSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(java.util.Locale.ROOT, "%.2f %s", value, units[index])
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
'''
p = java / "com/alibaba/mnnllm/android/modelist/GgufChatActivity.kt"
p.write_text(activity)

p = java / "com/alibaba/mnnllm/android/modelist/GgufChatActivity.kt"
p.write_text(activity)

main_path = java / "com/alibaba/mnnllm/android/main/MainActivity.kt"
main = main_path.read_text()
anchor = "import com.alibaba.mnnllm.android.modelist.LocalModelFormatInspector\n"
if anchor not in main: raise SystemExit("Cannot locate MainActivity model imports")
for line in [
    "import com.alibaba.mnnllm.android.modelist.LocalGgufModelImporter\n",
    "import com.alibaba.mnnllm.android.modelist.GgufChatActivity\n",
    "import com.alibaba.mnnllm.android.modelist.GgufModelLibraryActivity\n",
]:
    if line not in main: main = main.replace(anchor, anchor + line, 1)

a = main.index("    private fun importLocalModelFromUri(treeUri: Uri) {")
b = main.index("    /** Opens the system file picker.", a)
main = main[:a] + r'''    private fun importLocalModelFromUri(treeUri: Uri) {
        val dialog = ProgressDialog(this).apply {
            setTitle(R.string.local_model_import_title)
            setMessage(getString(R.string.local_model_import_progress))
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            try {
                val gguf = withContext(Dispatchers.IO) {
                    LocalGgufModelImporter.copyFromTree(this@MainActivity, treeUri)
                }
                if (gguf != null) {
                    startActivity(Intent(this@MainActivity, GgufChatActivity::class.java)
                        .putExtra(GgufChatActivity.EXTRA_MODEL_PATH, gguf.absolutePath))
                    return@launch
                }
                val imported = withContext(Dispatchers.IO) {
                    LocalModelFolderImporter.importFromTree(this@MainActivity, treeUri)
                }
                ModelListManager.clearModelCache()
                ModelListManager.notifyModelListMayChange(ModelListManager.ChangeReason.MANUAL_REFRESH)
                Toast.makeText(this@MainActivity,
                    getString(R.string.local_model_import_success, imported.name), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import local model folder", e)
                Toast.makeText(this@MainActivity,
                    getString(R.string.local_model_import_failed, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            } finally { dialog.dismiss() }
        }
    }

''' + main[b:]

a = main.index("    private fun importLocalModelFile(uri: Uri) {")
b = main.index("    fun runModel(", a)
main = main[:a] + r'''    private fun importLocalModelFile(uri: Uri) {
        val displayName = queryLocalModelDisplayName(uri)
        if (displayName.endsWith(".gguf", ignoreCase = true)) {
            val dialog = ProgressDialog(this).apply {
                setTitle(R.string.local_model_import_title)
                setMessage(getString(R.string.local_model_import_progress))
                setCancelable(false)
                show()
            }
            lifecycleScope.launch {
                try {
                    val model = withContext(Dispatchers.IO) {
                        LocalGgufModelImporter.copyFromUri(this@MainActivity, uri, displayName)
                    }
                    startActivity(Intent(this@MainActivity, GgufChatActivity::class.java)
                        .putExtra(GgufChatActivity.EXTRA_MODEL_PATH, model.absolutePath))
                } catch (e: Exception) {
                    Log.e(TAG, "GGUF import failed", e)
                    Toast.makeText(this@MainActivity,
                        "GGUF 导入失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                } finally { dialog.dismiss() }
            }
            return
        }

        val dialog = ProgressDialog(this).apply {
            setTitle(R.string.local_model_import_title)
            setMessage(getString(R.string.local_model_import_progress))
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val header = contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArray(8)
                        var count = 0
                        while (count < bytes.size) {
                            val read = input.read(bytes, count, bytes.size - count)
                            if (read <= 0) break
                            count += read
                        }
                        bytes.copyOf(count)
                    } ?: byteArrayOf()
                    val detection = LocalModelFormatInspector.inspectFile(displayName, header)
                    val imported = if (detection.format == LocalModelFormatInspector.Format.ZIP &&
                        detection.state != LocalModelFormatInspector.State.INVALID) {
                        val stream = contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("无法读取所选 ZIP 文件。")
                        stream.use { LocalModelArchiveImporter.importMnnZip(this@MainActivity, it, displayName) }
                    } else null
                    Pair(imported, detection)
                }
                if (result.first != null) {
                    ModelListManager.clearModelCache()
                    ModelListManager.notifyModelListMayChange(ModelListManager.ChangeReason.MANUAL_REFRESH)
                    Toast.makeText(this@MainActivity,
                        getString(R.string.local_model_import_success, result.first!!.name), Toast.LENGTH_LONG).show()
                } else Toast.makeText(this@MainActivity, result.second.details, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inspect/import local model", e)
                Toast.makeText(this@MainActivity,
                    getString(R.string.local_model_import_failed, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            } finally { dialog.dismiss() }
        }
    }

''' + main[b:]
library_entry_method = """    fun openGgufModelLibrary(view: android.view.View?) {
        startActivity(Intent(this, GgufModelLibraryActivity::class.java))
    }

"""
run_anchor = "    fun runModel("
if main.count(run_anchor) != 1:
    raise SystemExit("Cannot locate MainActivity runModel entry point")
if "fun openGgufModelLibrary(" not in main:
    main = main.replace(run_anchor, library_entry_method + run_anchor, 1)
main_path.write_text(main)

# Force app name in all locale resources and manifest.
res = app / "src/main/res"
for strings in res.glob("values*/strings.xml"):
    value = strings.read_text()
    if re.search(r'<string\s+name="app_name">', value):
        value, n = re.subn(r'(<string\s+name="app_name"\s*>).*?(</string>)',
            r'\1MNN Chat\2', value, count=1, flags=re.DOTALL)
        if n != 1: raise SystemExit(f"Could not restore name: {strings}")
        strings.write_text(value)
manifest = app / "src/main/AndroidManifest.xml"
value = manifest.read_text()
value, n = re.subn(r'android:label="@string/app_name"', 'android:label="MNN Chat"', value, count=1)
if n != 1: raise SystemExit("Could not set exact Android app label")
decl = '''        <activity
            android:name=".modelist.GgufChatActivity"
            android:configChanges="orientation|screenSize"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
'''
if ".modelist.GgufChatActivity" not in value:
    pos = value.rfind("    </application>")
    if pos < 0: raise SystemExit("No application end in manifest")
    value = value[:pos] + decl + value[pos:]
library_decl = '''        <activity
            android:name=".modelist.GgufModelLibraryActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
 '''
if ".modelist.GgufModelLibraryActivity" not in value:
    pos = value.rfind("    </application>")
    if pos < 0: raise SystemExit("No application end in manifest")
    value = value[:pos] + library_decl + value[pos:]
manifest.write_text(value)

# Remove custom icon overlay from the previous build.
for rel in ["drawable/localimport_badge.xml", "drawable/ic_launcher_foreground_localimport.xml"]:
    f = res / rel
    if f.exists(): f.unlink()
for rel in ["mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml"]:
    f = res / rel
    f.write_text(f.read_text().replace("@drawable/ic_launcher_foreground_localimport", "@mipmap/ic_launcher_foreground"))

gradle = app / "build.gradle"
value = gradle.read_text()
for old, new in [
    ('ndkVersion "27.2.12479018"', 'ndkVersion "29.0.13113456"'),
    ("versionCode 835", "versionCode 841"),
    ('versionName "0.8.7"', 'versionName "0.9.2"'),
    ("version '3.22.1'", "version '3.31.6'"),
]:
    if value.count(old) != 1: raise SystemExit(f"Missing Gradle anchor: {old}")
    value = value.replace(old, new, 1)
dep = "    implementation 'androidx.appcompat:appcompat:1.7.0'\n"
if dep not in value: raise SystemExit("Missing appcompat dependency")
if "androidx.documentfile:documentfile" not in value:
    value = value.replace(dep, dep + "    implementation 'androidx.documentfile:documentfile:1.0.1'\n", 1)
gradle.write_text(value)

cmake = cpp / "CMakeLists.txt"
value = cmake.read_text()
if "GGUF_DIRECT_LLAMA_CPP_RUNTIME" not in value:
    value = value.replace("cmake_minimum_required(VERSION 3.22.1)",
                          "cmake_minimum_required(VERSION 3.31.6)", 1)
    value += r'''

# GGUF_DIRECT_LLAMA_CPP_RUNTIME: llama.cpp direct text-GGUF inference beside MNN.
set(LLAMA_SRC "@D@ENV{LLAMA_CPP_SOURCE_DIR}")
if(NOT LLAMA_SRC OR NOT EXISTS "@D@{LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_CPP_SOURCE_DIR missing; cannot build GGUF runtime.")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_SHARED_LINKER_FLAGS "@D@{CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH ARM)
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
else()
    message(FATAL_ERROR "GGUF runtime is configured for arm64-v8a only.")
endif()
add_subdirectory("@D@{LLAMA_SRC}" "@D@{CMAKE_BINARY_DIR}/llama.cpp")
add_library(ai-chat SHARED "@D@{CMAKE_CURRENT_LIST_DIR}/ai_chat.cpp")
target_compile_definitions(ai-chat PRIVATE
    GGML_SYSTEM_ARCH=@D@{GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:@D@{GGML_CPU_KLEIDIAI}>
    GGML_OPENMP=$<BOOL:@D@{GGML_OPENMP}>
)
target_include_directories(ai-chat PRIVATE
    "@D@{LLAMA_SRC}" "@D@{LLAMA_SRC}/common" "@D@{LLAMA_SRC}/include"
    "@D@{LLAMA_SRC}/ggml/include" "@D@{LLAMA_SRC}/ggml/src"
    "@D@{LLAMA_SRC}/examples/llama.android/lib/src/main/cpp"
)
target_link_options(ai-chat PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(ai-chat PRIVATE llama llama-common android log)
'''
    cmake.write_text(value.replace("@D@", "$"))

print("GGUF direct Android runtime, file/folder import and chat activity integrated.")
print("Restored exact MNN Chat label and original adaptive icon, no edition badge.")
print("Version set to 0.9.2/code 841; safe side-by-side applicationId retained.")
