package com.alibaba.mnnllm.android.modelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalModelFormatInspectorTest {
    @Test
    fun recognizesValidGgufHeaderWithoutClaimingItIsAlreadyConverted() {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            .putInt(3)
            .array()

        val result = LocalModelFormatInspector.inspectFile("tiny.gguf", header)

        assertEquals(LocalModelFormatInspector.Format.GGUF, result.format)
        assertEquals(LocalModelFormatInspector.State.RECOGNIZED_NEEDS_TEMPLATE, result.state)
    }

    @Test
    fun rejectsGgufExtensionWithInvalidMagic() {
        val result = LocalModelFormatInspector.inspectFile("weights.gguf", byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0))

        assertEquals(LocalModelFormatInspector.State.INVALID, result.state)
    }

    @Test
    fun requiresAllMnnRuntimeRootFilesBeforeMarkingDirectoryImportable() {
        val valid = LocalModelFormatInspector.inspectDirectory(
            listOf("config.json", "llm.mnn", "llm.mnn.weight", "llm_config.json", "tokenizer.mtok")
        )
        val missingTokenizer = LocalModelFormatInspector.inspectDirectory(
            listOf("config.json", "llm.mnn", "llm.mnn.weight", "llm_config.json")
        )

        assertEquals(LocalModelFormatInspector.State.READY_TO_IMPORT, valid.state)
        assertEquals(LocalModelFormatInspector.State.RECOGNIZED_INCOMPLETE, missingTokenizer.state)
    }

    @Test
    fun detectsCompleteHuggingFaceSafetensorsDirectory() {
        val result = LocalModelFormatInspector.inspectDirectory(
            listOf("config.json", "tokenizer.json", "model.safetensors", "model.safetensors.index.json")
        )

        assertEquals(LocalModelFormatInspector.Format.HUGGINGFACE_SAFETENSORS, result.format)
        assertEquals(LocalModelFormatInspector.State.RECOGNIZED_NEEDS_TEMPLATE, result.state)
    }

    @Test
    fun rejectsSafetensorsDirectoryWithMissingIndexedShard() {
        val result = LocalModelFormatInspector.inspectDirectory(
            listOf("config.json", "tokenizer.json", "model-00001-of-00002.safetensors", "model.safetensors.index.json"),
            mapOf("model.safetensors.index.json" to
                """{"weight_map":{"layer.0":"model-00001-of-00002.safetensors","layer.1":"model-00002-of-00002.safetensors"}}""")
        )

        assertEquals(LocalModelFormatInspector.State.RECOGNIZED_INCOMPLETE, result.state)
        assertTrue(result.details.contains("model-00002-of-00002.safetensors"))
    }

    @Test
    fun checksMnnTemplatePrerequisites() {
        val result = LocalModelFormatInspector.inspectTemplate(
            setOf("config.json", "llm.mnn", "llm.mnn.json", "llm_config.json", "tokenizer.mtok")
        )
        val missing = LocalModelFormatInspector.inspectTemplate(setOf("config.json", "llm.mnn"))

        assertTrue(result.valid)
        assertFalse(missing.valid)
        assertTrue(missing.missing.contains("llm.mnn.json"))
    }

    @Test
    fun readsTemplateArchitectureFromModelConfig() {
        val result = LocalModelFormatInspector.inspectTemplate(
            setOf("config.json", "llm.mnn", "llm.mnn.json", "llm_config.json", "tokenizer.mtok"),
            """{"model_type":"llama"}"""
        )

        assertTrue(result.valid)
        assertEquals("llama", result.architecture)
    }

    @Test
    fun identifiesOtherCommonFormatsWithoutClaimingDirectImport() {
        val onnx = LocalModelFormatInspector.inspectFile("model.onnx", byteArrayOf())
        val tflite = LocalModelFormatInspector.inspectFile("model.tflite", byteArrayOf())
        val pytorch = LocalModelFormatInspector.inspectFile("pytorch_model.bin", byteArrayOf())

        assertEquals(LocalModelFormatInspector.Format.ONNX, onnx.format)
        assertEquals(LocalModelFormatInspector.State.RECOGNIZED_NOT_CONVERTIBLE_HERE, onnx.state)
        assertEquals(LocalModelFormatInspector.Format.TFLITE, tflite.format)
        assertEquals(LocalModelFormatInspector.Format.PYTORCH, pytorch.format)
    }
}
