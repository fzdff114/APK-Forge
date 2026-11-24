package com.alibaba.mnnllm.android.modelist

/**
 * Holds the GGUF session currently selected in the original MNN Chat UI for the local API.
 * The API exposes only this active session; it never guesses a filesystem path from a request.
 */
object GgufApiSessionRegistry {
    @Volatile
    private var activeModelId: String? = null

    @Volatile
    private var activeSession: GgufChatSession? = null

    @Synchronized
    fun register(modelId: String, session: GgufChatSession) {
        if (!modelId.startsWith("local-gguf/")) return
        activeModelId = modelId
        activeSession = session
    }

    @Synchronized
    fun clear() {
        activeSession = null
        activeModelId = null
    }

    fun currentModelId(): String? = activeModelId

    fun getActiveSession(modelId: String): GgufChatSession? {
        if (!modelId.startsWith("local-gguf/")) return null
        return if (activeModelId == modelId) activeSession else null
    }
}
