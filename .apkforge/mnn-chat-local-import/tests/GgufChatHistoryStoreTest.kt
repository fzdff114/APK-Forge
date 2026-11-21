package com.alibaba.mnnllm.android.modelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GgufChatHistoryStoreTest {
    @Test
    fun savesRestoresAndClearsTranscript() {
        val root = Files.createTempDirectory("gguf-chat-history").toFile()
        try {
            val modelDir = File(File(root, "models"), "deepseek_1234").apply { mkdirs() }
            val model = File(modelDir, "DeepSeek-R1-Distill-Qwen-1.5B-Q2_K.gguf")
            val history = GgufChatHistoryStore.fileFor(root, model)
            val transcript = "模型已就绪\n你：你好\n助手：你好！\n"

            GgufChatHistoryStore.write(history, transcript)

            assertEquals(transcript, GgufChatHistoryStore.read(history))
            assertTrue(GgufChatHistoryStore.clear(history))
            assertEquals("", GgufChatHistoryStore.read(history))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsHistoryFilesSeparateForDifferentModelFolders() {
        val root = Files.createTempDirectory("gguf-chat-history-models").toFile()
        try {
            val first = File(File(root, "models"), "model_a")
            val second = File(File(root, "models"), "model_b")
            val a = File(first, "model.gguf")
            val b = File(second, "model.gguf")

            val aHistory = GgufChatHistoryStore.fileFor(root, a)
            val bHistory = GgufChatHistoryStore.fileFor(root, b)

            assertNotEquals(aHistory.absolutePath, bHistory.absolutePath)
            GgufChatHistoryStore.write(aHistory, "first")
            GgufChatHistoryStore.write(bHistory, "second")
            assertEquals("first", GgufChatHistoryStore.read(aHistory))
            assertEquals("second", GgufChatHistoryStore.read(bHistory))
        } finally {
            root.deleteRecursively()
        }
    }
}
