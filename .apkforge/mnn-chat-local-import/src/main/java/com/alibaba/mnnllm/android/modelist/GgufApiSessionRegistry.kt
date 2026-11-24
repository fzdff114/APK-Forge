package com.alibaba.mnnllm.android.modelist

/**
 * Shares the selected GGUF session between the chat screen and the local API service.
 * The session remains alive while either side owns it.
 */
object GgufApiSessionRegistry {
    private val lock = Any()
    @Volatile private var activeModelId: String? = null
    @Volatile private var activeSession: GgufChatSession? = null
    @Volatile private var chatAttached: Boolean = false
    @Volatile private var apiModelId: String? = null

    fun register(modelId: String, session: GgufChatSession) {
        if (!modelId.startsWith("local-gguf/")) return
        val previous: GgufChatSession?
        synchronized(lock) {
            previous = activeSession?.takeIf { it !== session && apiModelId != activeModelId }
            activeModelId = modelId
            activeSession = session
            chatAttached = true
        }
        previous?.release()
    }

    fun currentModelId(): String? = activeModelId

    fun getActiveSession(modelId: String): GgufChatSession? = synchronized(lock) {
        if (modelId.startsWith("local-gguf/") && activeModelId == modelId) activeSession else null
    }

    fun attachApiService(modelId: String?) {
        synchronized(lock) {
            apiModelId = modelId?.takeIf { it.startsWith("local-gguf/") && it == activeModelId }
        }
    }

    fun detachChat(session: GgufChatSession) {
        val release: GgufChatSession?
        synchronized(lock) {
            if (activeSession !== session) return
            chatAttached = false
            release = if (apiModelId != activeModelId) {
                activeSession.also {
                    activeSession = null
                    activeModelId = null
                }
            } else null
        }
        release?.release()
    }

    fun detachApiService() {
        val release: GgufChatSession?
        synchronized(lock) {
            apiModelId = null
            release = if (!chatAttached) {
                activeSession.also {
                    activeSession = null
                    activeModelId = null
                }
            } else null
        }
        release?.release()
    }

    fun clear() {
        val release: GgufChatSession?
        synchronized(lock) {
            release = activeSession
            activeSession = null
            activeModelId = null
            chatAttached = false
            apiModelId = null
        }
        release?.release()
    }
}
