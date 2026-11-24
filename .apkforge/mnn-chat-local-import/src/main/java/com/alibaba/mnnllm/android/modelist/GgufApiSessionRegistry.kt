package com.alibaba.mnnllm.android.modelist

/**
 * Shares GGUF sessions between the original chat screen and the local API service.
 * A model stays loaded while either the chat screen or the API service owns it.
 */
object GgufApiSessionRegistry {
    private val lock = Any()
    private val sessions = linkedMapOf<String, GgufChatSession>()
    @Volatile private var activeModelId: String? = null
    @Volatile private var chatModelId: String? = null
    @Volatile private var apiModelId: String? = null

    fun register(modelId: String, session: GgufChatSession) {
        if (!modelId.startsWith("local-gguf/")) return
        val previous: GgufChatSession?
        synchronized(lock) {
            previous = sessions.put(modelId, session)?.takeIf { it !== session && apiModelId != modelId }
            chatModelId = modelId
            activeModelId = modelId
        }
        previous?.release()
    }

    fun currentModelId(): String? = synchronized(lock) {
        apiModelId ?: chatModelId ?: activeModelId
    }

    fun getActiveSession(modelId: String): GgufChatSession? = synchronized(lock) {
        if (modelId.startsWith("local-gguf/")) sessions[modelId] else null
    }

    fun attachApiService(modelId: String?) {
        synchronized(lock) {
            apiModelId = modelId?.takeIf { it.startsWith("local-gguf/") && sessions.containsKey(it) }
            activeModelId = apiModelId ?: chatModelId
        }
    }

    fun detachChat(session: GgufChatSession) {
        val release: GgufChatSession?
        synchronized(lock) {
            val modelId = sessions.entries.firstOrNull { it.value === session }?.key ?: return
            if (chatModelId == modelId) chatModelId = null
            release = if (apiModelId != modelId) sessions.remove(modelId) else null
            activeModelId = apiModelId ?: chatModelId
        }
        release?.release()
    }

    fun detachApiService() {
        val release: GgufChatSession?
        synchronized(lock) {
            val oldModelId = apiModelId
            apiModelId = null
            release = if (oldModelId != null && oldModelId != chatModelId) sessions.remove(oldModelId) else null
            activeModelId = chatModelId
        }
        release?.release()
    }

    /**
     * Drop the chat-side model when a non-GGUF session is opened.
     * Keep the previous GGUF session only if the API service is still using it.
     */
    fun clear() {
        val release: GgufChatSession?
        synchronized(lock) {
            val oldChatModelId = chatModelId
            chatModelId = null
            release = if (oldChatModelId != null && oldChatModelId != apiModelId) {
                sessions.remove(oldChatModelId)
            } else null
            activeModelId = apiModelId
        }
        release?.release()
    }
}
