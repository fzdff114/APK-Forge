package com.alibaba.mnnllm.api.openai.network.handlers

import OpenAIChatRequest
import OpenAIGenericMessage
import MultiModalContent
import OpenAITextContent
import TextContent
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.modelist.GgufChatSession
import com.alibaba.mnnllm.api.openai.network.compat.AnthropicAdapter
import com.alibaba.mnnllm.api.openai.network.compat.AnthropicUsage
import com.alibaba.mnnllm.api.openai.network.models.Usage
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
import org.json.JSONObject
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Text-only OpenAI and Anthropic API adapter for the llama.cpp GGUF ChatSession.
 * MNN's existing response handler remains unchanged for native MNN LlmSession.
 */
class GgufApiResponseHandler {
    private val formatter = ChatResponseFormatter()

    suspend fun handleOpenAI(
        call: ApplicationCall,
        session: GgufChatSession,
        request: OpenAIChatRequest,
        traceId: String
    ) {
        val prepared = try {
            prepare(request.messages)
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("message" to (error.message ?: "Invalid GGUF API request"))))
            return
        }
        val maxTokens = request.max_tokens ?: 512
        if (request.stream == true) {
            streamOpenAI(call, session, prepared, request.model ?: "local-gguf", maxTokens, traceId)
        } else {
            respondOpenAI(call, session, prepared, request.model ?: "local-gguf", maxTokens)
        }
    }

    suspend fun handleAnthropic(
        call: ApplicationCall,
        session: GgufChatSession,
        request: OpenAIChatRequest,
        model: String,
        maxTokens: Int,
        stream: Boolean,
        traceId: String
    ) {
        val prepared = try {
            prepare(request.messages)
        } catch (error: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("type" to "error", "error" to mapOf("type" to "invalid_request_error", "message" to (error.message ?: "Invalid GGUF API request")))
            )
            return
        }
        if (stream) {
            streamAnthropic(call, session, prepared, model, maxTokens, traceId)
        } else {
            val text = generateText(session, prepared, maxTokens)
            val response = AnthropicAdapter.createNonStreamResponse(
                responseId = "msg_${System.currentTimeMillis()}",
                model = model,
                text = text,
                usage = AnthropicUsage(input_tokens = 0, output_tokens = 0)
            )
            call.respondText(response, ContentType.Application.Json)
        }
    }

    private data class PreparedPrompt(
        val systemPrompt: String?,
        val history: List<Pair<String, String>>,
        val latestUserMessage: String
    )

    private fun prepare(messages: List<OpenAIGenericMessage>): PreparedPrompt {
        if (messages.isEmpty()) throw IllegalArgumentException("messages must contain at least one user message")
        val normalized = messages.mapNotNull { message ->
            val role = when (message.role.lowercase()) {
                "system", "developer" -> "system"
                "assistant" -> "assistant"
                "user" -> "user"
                else -> throw IllegalArgumentException("GGUF text API does not support role: ${message.role}")
            }
            val text = when (val content = message.content) {
                is TextContent -> content.text
                is MultiModalContent -> {
                    if (content.content.any { it !is OpenAITextContent }) {
                        throw IllegalArgumentException("当前 GGUF API 仅支持纯文本消息，图片、音频和文件内容暂不支持。")
                    }
                    content.content.joinToString("\n") { (it as OpenAITextContent).text }
                }
                null -> ""
                else -> throw IllegalArgumentException("GGUF API only accepts text message content")
            }
            role to text
        }
        val latestUserIndex = normalized.indexOfLast { it.first == "user" }
        if (latestUserIndex < 0 || normalized[latestUserIndex].second.isBlank()) {
            throw IllegalArgumentException("messages must end with a non-empty user message")
        }
        val system = normalized.filter { it.first == "system" }
            .joinToString("\n") { it.second }
            .ifBlank { null }
        val history = normalized.take(latestUserIndex).filter { it.first != "system" }
        return PreparedPrompt(system, history, normalized[latestUserIndex].second)
    }

    private suspend fun respondOpenAI(
        call: ApplicationCall,
        session: GgufChatSession,
        prompt: PreparedPrompt,
        model: String,
        maxTokens: Int
    ) {
        val responseText = generateText(session, prompt, maxTokens)
        val usage = Usage(prompt_tokens = 0, completion_tokens = 0, total_tokens = 0)
        val json = formatter.createChatCompletionResponse(
            responseId = "chatcmpl-${System.currentTimeMillis()}",
            created = System.currentTimeMillis() / 1000,
            content = responseText,
            usage = usage
        )
        call.respondText(json.replace("\"mnn-local\"", JSONObject.quote(model)), ContentType.Application.Json)
    }

    private fun generateText(session: GgufChatSession, prompt: PreparedPrompt, maxTokens: Int): String {
        val output = StringBuilder()
        session.generateForApi(
            systemPrompt = prompt.systemPrompt,
            history = prompt.history,
            latestUserMessage = prompt.latestUserMessage,
            params = mapOf("max_new_tokens" to maxTokens.coerceIn(1, 8192)),
            progressListener = object : GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    if (progress != null) output.append(progress)
                    return false
                }
            }
        )
        return output.toString()
    }

    private suspend fun streamOpenAI(
        call: ApplicationCall,
        session: GgufChatSession,
        prompt: PreparedPrompt,
        model: String,
        maxTokens: Int,
        traceId: String
    ) {
        val responseId = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        call.respond(SSEServerContent(call) {
            heartbeat {
                period = 3.seconds
                event = ServerSentEvent(comments = "keep-alive")
            }
            val channel = Channel<ServerSentEvent>(Channel.UNLIMITED)
            launch(Dispatchers.IO) {
                try {
                    session.generateForApi(
                        systemPrompt = prompt.systemPrompt,
                        history = prompt.history,
                        latestUserMessage = prompt.latestUserMessage,
                        params = mapOf("max_new_tokens" to maxTokens.coerceIn(1, 8192)),
                        progressListener = object : GenerateProgressListener {
                            override fun onProgress(progress: String?): Boolean {
                                if (progress.isNullOrEmpty()) return false
                                val json = formatter.createDeltaResponse(responseId, created, progress, false)
                                    .replace("\"mnn-local\"", JSONObject.quote(model))
                                return !channel.trySend(ServerSentEvent(data = json)).isSuccess
                            }
                        }
                    )
                    val finalJson = formatter.createDeltaResponse(
                        responseId,
                        created,
                        "",
                        true,
                        Usage(prompt_tokens = 0, completion_tokens = 0, total_tokens = 0)
                    ).replace("\"mnn-local\"", JSONObject.quote(model))
                    channel.send(ServerSentEvent(data = finalJson))
                    delay(100)
                    channel.send(ServerSentEvent(data = "[DONE]"))
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "GGUF OpenAI streaming generation failed: $traceId", error)
                    val errorJson = """{"error":{"message":${JSONObject.quote(error.message ?: "GGUF generation failed")}}}"""
                    channel.trySend(ServerSentEvent(data = errorJson))
                } finally {
                    channel.close()
                }
            }
            for (event in channel) send(event)
        })
    }

    private suspend fun streamAnthropic(
        call: ApplicationCall,
        session: GgufChatSession,
        prompt: PreparedPrompt,
        model: String,
        maxTokens: Int,
        traceId: String
    ) {
        val responseId = "msg_${System.currentTimeMillis()}"
        call.respond(SSEServerContent(call) {
            heartbeat {
                period = 3.seconds
                event = ServerSentEvent(comments = "keep-alive")
            }
            send(ServerSentEvent(event = "message_start", data = """{"type":"message_start","message":{"id":${JSONObject.quote(responseId)},"type":"message","role":"assistant","model":${JSONObject.quote(model)},"content":[],"usage":{"input_tokens":0,"output_tokens":0}}}"""))
            send(ServerSentEvent(event = "content_block_start", data = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""))
            val channel = Channel<ServerSentEvent>(Channel.UNLIMITED)
            launch(Dispatchers.IO) {
                try {
                    session.generateForApi(
                        systemPrompt = prompt.systemPrompt,
                        history = prompt.history,
                        latestUserMessage = prompt.latestUserMessage,
                        params = mapOf("max_new_tokens" to maxTokens.coerceIn(1, 8192)),
                        progressListener = object : GenerateProgressListener {
                            override fun onProgress(progress: String?): Boolean {
                                if (progress.isNullOrEmpty()) return false
                                val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${JSONObject.quote(progress)}}}"""
                                return !channel.trySend(ServerSentEvent(event = "content_block_delta", data = data)).isSuccess
                            }
                        }
                    )
                    channel.send(ServerSentEvent(event = "content_block_stop", data = """{"type":"content_block_stop","index":0}"""))
                    channel.send(ServerSentEvent(event = "message_delta", data = """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"input_tokens":0,"output_tokens":0}}"""))
                    channel.send(ServerSentEvent(event = "message_stop", data = """{"type":"message_stop"}"""))
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "GGUF Anthropic streaming generation failed: $traceId", error)
                    channel.trySend(ServerSentEvent(event = "error", data = """{"type":"error","error":{"type":"api_error","message":${JSONObject.quote(error.message ?: "GGUF generation failed")}}}"""))
                } finally {
                    channel.close()
                }
            }
            for (event in channel) send(event)
        })
    }

    companion object {
        private const val TAG = "GgufApiResponseHandler"
    }
}
