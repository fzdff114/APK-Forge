package com.alibaba.mnnllm.api.openai.network.services

import OpenAIChatRequest
import MultiModalContent
import OpenAITextContent
import TextContent
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.modelist.GgufChatSession
import com.alibaba.mnnllm.api.openai.network.queue.RequestQueueManager
import com.alibaba.mnnllm.api.openai.network.utils.ChatResponseFormatter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.heartbeat
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * OpenAI-compatible, text-only API adapter for the currently active GGUF session.
 * It deliberately does not route GGUF requests through MNN's LlmSession/JNI backend.
 */
class GgufApiChatService {
    private val formatter = ChatResponseFormatter()
    private val requestQueue = RequestQueueManager.getInstance()

    suspend fun processChatCompletion(
        call: ApplicationCall,
        request: OpenAIChatRequest,
        traceId: String,
        modelId: String,
        session: GgufChatSession
    ) {
        val prompt = try {
            buildPrompt(request)
        } catch (error: UnsupportedOperationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to mapOf("message" to (error.message ?: "GGUF API accepts text messages only.")))
            )
            return
        } catch (error: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to mapOf("message" to (error.message ?: "Invalid chat request.")))
            )
            return
        }

        val requestId = UUID.randomUUID().toString()
        val maxTokens = (request.max_completion_tokens ?: request.max_tokens ?: 512)
            .coerceIn(1, MAX_COMPLETION_TOKENS)

        try {
            requestQueue.submitRequest(
                requestId = requestId,
                traceId = traceId,
                task = {
                    try {
                        if (request.stream == true) {
                            streamResponse(call, session, modelId, prompt, maxTokens)
                        } else {
                            nonStreamResponse(call, session, modelId, prompt, maxTokens)
                        }
                    } catch (error: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to mapOf(
                                "message" to (error.message ?: "GGUF inference failed"),
                                "type" to "server_error"
                            ))
                        )
                    }
                }
            )
        } catch (error: Exception) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to mapOf("message" to (error.message ?: "GGUF request queue unavailable.")))
            )
        }
    }

    private fun buildPrompt(request: OpenAIChatRequest): ApiPrompt {
        require(request.messages.isNotEmpty()) { "messages must contain at least one message." }

        val systemParts = mutableListOf<String>()
        val dialogue = StringBuilder()
        var hasUserMessage = false

        for (message in request.messages) {
            val text = messageContentToText(message.content)
            when (message.role.lowercase()) {
                "system", "developer" -> if (text.isNotBlank()) systemParts += text
                "user" -> {
                    hasUserMessage = true
                    dialogue.append("用户：\n").append(text).append("\n\n")
                }
                "assistant" -> dialogue.append("助手：\n").append(text).append("\n\n")
                else -> throw IllegalArgumentException(
                    "GGUF text API supports system, developer, user and assistant roles; unsupported role: ${message.role}"
                )
            }
        }

        require(hasUserMessage) { "At least one user message is required." }
        return ApiPrompt(
            system = systemParts.joinToString("\n\n").ifBlank { DEFAULT_SYSTEM_PROMPT },
            prompt = dialogue.toString().trim()
        )
    }

    private fun messageContentToText(content: Any?): String {
        return when (content) {
            null -> ""
            is String -> content
            is TextContent -> content.text
            is MultiModalContent -> buildString {
                for (part in content.content) {
                    when (part) {
                        is OpenAITextContent -> append(part.text)
                        else -> throw UnsupportedOperationException(
                            "This GGUF backend is text-only. Image, audio, video and file inputs are not supported by the GGUF API yet."
                        )
                    }
                }
            }
            else -> throw UnsupportedOperationException(
                "This GGUF backend accepts text content only."
            )
        }
    }

    private suspend fun nonStreamResponse(
        call: ApplicationCall,
        session: GgufChatSession,
        modelId: String,
        prompt: ApiPrompt,
        maxTokens: Int
    ) {
        val responseText = StringBuilder()
        val metadata = createMetadata()
        val metrics = session.generateApiRequest(
            prompt.system,
            prompt.prompt,
            maxTokens,
            object : GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    if (progress != null) responseText.append(progress)
                    return false
                }
            }
        )
        val usage = formatter.createUsageFromMetrics(metrics)
        val response = formatter.createChatCompletionResponse(
            metadata.first,
            metadata.second,
            responseText.toString(),
            usage,
            modelId
        )
        call.respondText(response, ContentType.Application.Json)
    }

    private suspend fun streamResponse(
        call: ApplicationCall,
        session: GgufChatSession,
        modelId: String,
        prompt: ApiPrompt,
        maxTokens: Int
    ) {
        val metadata = createMetadata()
        call.respond(SSEServerContent(call) {
            heartbeat {
                period = 3.seconds
                event = ServerSentEvent(comments = "keep-alive")
            }

            val channel = Channel<ServerSentEvent>(Channel.UNLIMITED)
            launch(Dispatchers.IO) {
                try {
                    val metrics = session.generateApiRequest(
                        prompt.system,
                        prompt.prompt,
                        maxTokens,
                        object : GenerateProgressListener {
                            override fun onProgress(progress: String?): Boolean {
                                if (progress == null) return false
                                val json = formatter.createDeltaResponse(
                                    metadata.first,
                                    metadata.second,
                                    progress,
                                    model = modelId
                                )
                                return channel.trySend(ServerSentEvent(data = json)).isFailure
                            }
                        }
                    )
                    val usage = formatter.createUsageFromMetrics(metrics)
                    val finalChunk = formatter.createDeltaResponse(
                        metadata.first,
                        metadata.second,
                        "",
                        isLast = true,
                        usage = usage,
                        model = modelId
                    )
                    channel.send(ServerSentEvent(data = finalChunk))
                    delay(50)
                    channel.send(ServerSentEvent(data = "[DONE]"))
                } catch (error: Exception) {
                    val errorText = error.message ?: "GGUF inference failed"
                    val escaped = errorText
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                    channel.trySend(ServerSentEvent(
                        data = "{\"error\":{\"message\":\"$escaped\",\"type\":\"server_error\"}}"
                    ))
                } finally {
                    channel.close()
                }
            }

            for (event in channel) send(event)
        })
    }

    private fun createMetadata(): Pair<String, Long> {
        val now = System.currentTimeMillis()
        return "chatcmpl-$now" to (now / 1000)
    }

    private data class ApiPrompt(
        val system: String,
        val prompt: String
    )

    companion object {
        private const val MAX_COMPLETION_TOKENS = 8192
        private const val DEFAULT_SYSTEM_PROMPT =
            "你是一个有帮助的 AI 助手。请准确、清晰地回答用户问题。"
    }
}
