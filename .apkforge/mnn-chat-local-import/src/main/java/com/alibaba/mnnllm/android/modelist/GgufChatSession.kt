package com.alibaba.mnnllm.android.modelist

import android.content.Context
import android.util.Log
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.ChatSession
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts the native llama.cpp GGUF engine to MNN Chat's existing ChatSession contract.
 * ChatActivity, ChatListComponent and ChatInputComponent therefore remain the original UI.
 */
class GgufChatSession(
    context: Context,
    private val modelPath: String,
    sessionId: String?,
    history: List<ChatDataItem>?
) : ChatSession {
    private val appContext = context.applicationContext
    @Volatile private var currentSessionId: String =
        sessionId?.takeIf { it.isNotBlank() } ?: System.currentTimeMillis().toString()
    @Volatile private var inferenceEngine: InferenceEngine? = null
    @Volatile private var savedHistory: List<ChatDataItem>? = history
    @Volatile private var keepHistory = true
    private val lock = Any()
    private val released = AtomicBoolean(false)

    override val sessionId: String?
        get() = currentSessionId

    override val supportOmni: Boolean = false

    override val debugInfo: String
        get() = buildString {
            append("Backend: llama.cpp (GGUF)")
            append("\nModel: ").append(File(modelPath).name)
            append("\nBytes: ").append(File(modelPath).length())
            append("\nLoaded: ").append(inferenceEngine != null)
        }

    override fun load() {
        ensureLoaded()
    }

    private fun ensureLoaded(): InferenceEngine {
        inferenceEngine?.let { return it }
        synchronized(lock) {
            inferenceEngine?.let { return it }
            if (!File(modelPath).isFile) {
                throw IllegalStateException("GGUF 模型文件不存在：$modelPath")
            }
            try {
                val loaded = runBlocking(Dispatchers.IO) {
                    val candidate = withContext(Dispatchers.Default) {
                        AiChat.getInferenceEngine(appContext)
                    }
                    val initial = candidate.state.first {
                        it is InferenceEngine.State.Initialized ||
                            it is InferenceEngine.State.Error
                    }
                    if (initial is InferenceEngine.State.Error) {
                        throw initial.exception
                    }
                    withContext(Dispatchers.IO) {
                        candidate.loadModel(modelPath)
                        candidate.setSystemPrompt("你是一个有帮助的中文 AI 助手。请清晰、准确地回答问题。")
                    }
                    candidate
                }
                inferenceEngine = loaded
                released.set(false)
                Log.i(TAG, "Loaded GGUF through llama.cpp: $modelPath")
                return loaded
            } catch (error: Exception) {
                inferenceEngine = null
                throw IllegalStateException(
                    "GGUF 模型加载失败：${error.message ?: error.javaClass.simpleName}",
                    error
                )
            }
        }
    }

    override fun generate(
        prompt: String,
        params: Map<String, Any>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        val engine = ensureLoaded()
        val maxNewTokens = (params["max_new_tokens"] as? Number)?.toInt()
            ?.coerceAtLeast(1) ?: 512
        val startedAt = System.nanoTime()
        val rawResponse = StringBuilder()
        runBlocking(Dispatchers.IO) {
            engine.sendUserPrompt(prompt, maxNewTokens)
                .takeWhile { token ->
                    rawResponse.append(token)
                    !progressListener.onProgress(token)
                }
                .collect { }
        }
        val elapsedMicros = ((System.nanoTime() - startedAt) / 1_000L).coerceAtLeast(0L)
        return hashMapOf(
            "response" to rawResponse.toString(),
            "prompt_len" to 0L,
            "decode_len" to rawResponse.length.toLong(),
            "prefill_time" to 0L,
            "decode_time" to elapsedMicros
        )
    }

    override fun reset(): String {
        release()
        currentSessionId = System.currentTimeMillis().toString()
        savedHistory = emptyList()
        return currentSessionId
    }

    override fun release() {
        val old = synchronized(lock) {
            val current = inferenceEngine
            inferenceEngine = null
            current
        } ?: return
        if (!released.compareAndSet(false, true)) return
        try {
            runCatching {
                val state = old.state.value
                if (state is InferenceEngine.State.ModelReady ||
                    state is InferenceEngine.State.Error
                ) {
                    old.cleanUp()
                }
            }
            old.destroy()
        } catch (error: Exception) {
            Log.w(TAG, "Error releasing GGUF engine", error)
        }
    }

    override fun setKeepHistory(keepHistory: Boolean) {
        this.keepHistory = keepHistory
    }

    override fun setEnableAudioOutput(enable: Boolean) {
        // Text-only GGUF sessions do not expose an audio output backend.
    }

    override fun getHistory(): List<ChatDataItem>? = savedHistory

    override fun setHistory(history: List<ChatDataItem>?) {
        savedHistory = history
    }

    override fun updateThinking(thinking: Boolean) {
        // Thinking mode is defined by the GGUF model's own chat template.
    }

    companion object {
        private const val TAG = "GgufChatSession"
    }
}
