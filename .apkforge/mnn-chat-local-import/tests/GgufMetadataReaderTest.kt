package com.alibaba.mnnllm.android.modelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class GgufMetadataReaderTest {
    @Test
    fun readsArchitectureBlockCountAndTensorQuantizationWithoutReadingTensorPayload() {
        val bytes = ggufFixture(listOf(2, 14), includeTokenArray = true)
        val result = GgufMetadataReader.inspect(bytes.inputStream())

        assertTrue(result is GgufMetadataReader.Result.Success)
        val metadata = (result as GgufMetadataReader.Result.Success).metadata
        assertEquals(3, metadata.version)
        assertEquals("llama", metadata.architecture)
        assertEquals("Tiny Llama Fixture", metadata.modelName)
        assertEquals(2L, metadata.blockCount)
        assertEquals(2L, metadata.tensorCount)
        assertEquals(setOf("Q4_0", "Q6_K"), metadata.tensorTypes)
        assertEquals(listOf("blk.0.attn_q.weight", "output_norm.weight"), metadata.tensorNames)
        assertTrue(metadata.unsupportedTensorTypes.isEmpty())
    }

    @Test
    fun skipsLargeUnneededScalarStringsAndStringArrayEntries() {
        // GGUF token metadata may contain long individual strings. The reader only needs
        // architecture/name/block count, so these values must be streamed past, not decoded.
        val oversized = 4 * 1024 * 1024 + 1
        val bytes = ggufFixture(
            types = listOf(2, 14),
            includeTokenArray = true,
            longArrayEntryBytes = oversized,
            longScalarStringBytes = oversized
        )

        val result = GgufMetadataReader.inspect(bytes.inputStream())

        assertTrue("Long unrelated metadata must not stop header inspection: $result",
            result is GgufMetadataReader.Result.Success)
        val metadata = (result as GgufMetadataReader.Result.Success).metadata
        assertEquals("llama", metadata.architecture)
        assertEquals(2L, metadata.tensorCount)
        assertEquals(setOf("Q4_0", "Q6_K"), metadata.tensorTypes)
    }

    @Test
    fun acceptsMetadataHeaderLargerThan64MiBByStreamingUnneededValues() {
        // Reproduce the failing Qwen/DeepSeek pattern without allocating a 64 MiB test array.
        // The chat-template value is streamed through the parser's small skip buffer.
        val longValueBytes = 64L * 1024L * 1024L + 1L
        val prefix = ByteArrayOutputStream()
        prefix.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        writeU32(prefix, 3)
        writeU64(prefix, 0L) // no tensor descriptors
        writeU64(prefix, 4L) // four metadata entries

        writeString(prefix, "general.architecture")
        writeU32(prefix, 8)
        writeString(prefix, "qwen2")

        writeString(prefix, "general.name")
        writeU32(prefix, 8)
        writeString(prefix, "DeepSeek-R1-Distill-Qwen-1.5B")

        writeString(prefix, "llama.block_count")
        writeU32(prefix, 4)
        writeU32(prefix, 28)

        writeString(prefix, "tokenizer.chat_template")
        writeU32(prefix, 8)
        writeU64(prefix, longValueBytes)

        val prefixInput = prefix.toByteArray().inputStream()
        val input = object : InputStream() {
            private var remaining = longValueBytes

            override fun read(): Int {
                val prefixByte = prefixInput.read()
                if (prefixByte >= 0) return prefixByte
                if (remaining <= 0L) return -1
                remaining--
                return 'x'.code
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val prefixCount = prefixInput.read(buffer, offset, length)
                if (prefixCount > 0) return prefixCount
                if (remaining <= 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 'x'.code.toByte())
                remaining -= count.toLong()
                return count
            }
        }

        val result = GgufMetadataReader.inspect(input)

        assertTrue("Metadata larger than 64 MiB must be accepted when the stream is complete: $result",
            result is GgufMetadataReader.Result.Success)
        val metadata = (result as GgufMetadataReader.Result.Success).metadata
        assertEquals("qwen2", metadata.architecture)
        assertEquals("DeepSeek-R1-Distill-Qwen-1.5B", metadata.modelName)
        assertEquals(0L, metadata.tensorCount)
    }

    @Test
    fun reportsTensorTypesNotHandledByOfficialMnnGgufWeightMapper() {
        val result = GgufMetadataReader.inspect(ggufFixture(listOf(39), includeTokenArray = false).inputStream())

        assertTrue(result is GgufMetadataReader.Result.Success)
        val metadata = (result as GgufMetadataReader.Result.Success).metadata
        assertEquals(setOf("MXFP4"), metadata.unsupportedTensorTypes)
    }

    @Test
    fun exposesArchitectureAndSupportedWeightTypesInReadinessInspection() {
        val result = LocalModelFormatInspector.inspectGguf("fixture.gguf", ggufFixture(listOf(2, 14), false).inputStream())

        assertEquals(LocalModelFormatInspector.Format.GGUF, result.format)
        assertEquals(LocalModelFormatInspector.State.RECOGNIZED_NEEDS_TEMPLATE, result.state)
        assertEquals("llama", result.architecture)
        assertEquals(setOf("Q4_0", "Q6_K"), result.tensorTypes)
        assertTrue(result.details.contains("GGUF v3"))
        assertTrue(result.details.contains("尚未编入"))
    }

    @Test
    fun flagsUnsupportedQuantizationInReadinessInspection() {
        val result = LocalModelFormatInspector.inspectGguf("fixture.gguf", ggufFixture(listOf(39), false).inputStream())

        assertEquals(LocalModelFormatInspector.State.RECOGNIZED_NOT_CONVERTIBLE_HERE, result.state)
        assertEquals(setOf("MXFP4"), result.unsupportedTensorTypes)
    }

    @Test
    fun rejectsTruncatedGgufHeaderGracefully() {
        val result = GgufMetadataReader.inspect(byteArrayOf(
            'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(),
            3, 0, 0, 0
        ).inputStream())

        assertTrue(result is GgufMetadataReader.Result.Failure)
        assertFalse((result as GgufMetadataReader.Result.Failure).reason.isBlank())
    }

    @Test
    fun rejectsUnreasonableMetadataCountsBeforeAllocating() {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        writeU32(output, 3)
        writeU64(output, 0)
        writeU64(output, Long.MAX_VALUE)

        val result = GgufMetadataReader.inspect(output.toByteArray().inputStream())

        assertTrue(result is GgufMetadataReader.Result.Failure)
    }

    private fun ggufFixture(
        types: List<Int>,
        includeTokenArray: Boolean,
        longArrayEntryBytes: Int = 0,
        longScalarStringBytes: Int = 0
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        writeU32(output, 3)
        writeU64(output, types.size.toLong())
        writeU64(output, ((if (includeTokenArray) 4 else 3) + if (longScalarStringBytes > 0) 1 else 0).toLong())

        writeString(output, "general.architecture")
        writeU32(output, 8)
        writeString(output, "llama")

        writeString(output, "general.name")
        writeU32(output, 8)
        writeString(output, "Tiny Llama Fixture")

        writeString(output, "llama.block_count")
        writeU32(output, 4)
        writeU32(output, 2)

        if (includeTokenArray) {
            writeString(output, "tokenizer.ggml.tokens")
            writeU32(output, 9)
            writeU32(output, 8)
            writeU64(output, if (longArrayEntryBytes > 0) 3 else 2)
            writeString(output, "<s>")
            writeString(output, "</s>")
            if (longArrayEntryBytes > 0) {
                writeString(output, "x".repeat(longArrayEntryBytes))
            }
        }

        if (longScalarStringBytes > 0) {
            writeString(output, "tokenizer.chat_template")
            writeU32(output, 8)
            writeString(output, "y".repeat(longScalarStringBytes))
        }

        types.forEachIndexed { index, type ->
            writeString(output, if (index == 0) "blk.0.attn_q.weight" else "output_norm.weight")
            writeU32(output, 2)
            writeU64(output, 4)
            writeU64(output, 4)
            writeU32(output, type)
            writeU64(output, 0)
        }
        return output.toByteArray()
    }

    private fun writeString(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeU64(output, bytes.size.toLong())
        output.write(bytes)
    }

    private fun writeU32(output: ByteArrayOutputStream, value: Int) {
        repeat(4) { index -> output.write((value ushr (8 * index)) and 0xff) }
    }

    private fun writeU64(output: ByteArrayOutputStream, value: Long) {
        repeat(8) { index -> output.write(((value ushr (8 * index)) and 0xff).toInt()) }
    }
}
