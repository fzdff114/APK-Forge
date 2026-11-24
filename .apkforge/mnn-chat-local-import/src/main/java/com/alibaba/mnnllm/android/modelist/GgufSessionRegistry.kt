package com.alibaba.mnnllm.android.modelist

/**
 * Shares the currently opened GGUF session with the local API service.
 *
 * The chat screen and the API service may have different lifetimes. Keep the
 * model alive while either owner is attached, and release it when both detach.
 */
object GgufSessionRegistry {
    private val lock = Any()
    @Volatile private var chatSession: GgufChatSession? = null
    @Volatile private var chatModelId: String? = null
    @Volatile private var chatAttached: Boolean = false
    @Volatile private var apiModelId: String? = null

    fun registerChatSession(modelId: String, session: GgufChatSession) {
        val oldSession: GgufChatSession?
        synchronized(lock) {
            oldSession = chatSession?.takeIf { it !== session && apiModelId != chatModelId }
            chatSession = session
            chatModelId = modelId
            chatAttached = true
        }
        oldSession?.release()
    }

    fun getSession(modelId: String? = null): GgufChatSession? = synchronized(lock) {
        val current = chatSession ?: return@synchronized null
        if (modelId != null && modelId != chatModelId) return@synchronized null
        current
    }

    fun setApiAttached(modelId: String?) {
        val releaseOld: GgufChatSession?
        synchronized(lock) {
            val previousApiModelId = apiModelId
            apiModelId = modelId
            releaseOld = if (
                previousApiModelId != null &&
                previousApiModelId != modelId &&
                !chatAttached &&
                previousApiModelId == chatModelId
            ) chatSession else null
            if (releaseOld != null) {
                chatSession = null
                chatModelId = null
            }
        }
        releaseOld?.release()
    }

    fun detachChatSession(session: GgufChatSession) {
        val release: GgufChatSession?
        synchronized(lock) {
            if (chatSession !== session) return
            chatAttached = false
            release = if (apiModelId != chatModelId) {
                chatSession.also {
                    chatSession = null
                    chatModelId = null
                }
            } else null
        }
        release?.release()
    }

    fun detachApiService() {
        val release: GgufChatSession?
        synchronized(lock) {
            val previousApiModelId = apiModelId
            apiModelId = null
            release = if (!chatAttached && previousApiModelId != null && previousApiModelId == chatModelId) {
                chatSession.also {
                    chatSession = null
                    chatModelId = null
                }
            } else null
        }
        release?.release()
    }
}
