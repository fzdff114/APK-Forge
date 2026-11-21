package com.alibaba.mnnllm.android.modelist

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Reads GGUF metadata and tensor descriptors without loading tensor payloads.
 *
 * Metadata is streamed from the input and is bounded by the actual stream length (EOF), not by
 * an arbitrary total header-size cap. Unneeded long strings are skipped through a small buffer.
 * This supports large tokenizer vocabularies while avoiding allocations proportional to model size.
 */
object GgufMetadataReader {
    // Strings larger than this are streamed past instead of decoded into a Kotlin String.
    // It is not a model/header size limit: large metadata values are still fully accepted.
    private const val MAX_DECODED_STRING_BYTES = 4L * 1024L * 1024L
    private const val MAX_DIMENSIONS = 4

    // Verified against the current upstream MNN transformers/llm/export/gguf2mnn.py mapper.
    private val supportedTensorTypeIds = setOf(0, 1, 2, 3, 6, 7, 8, 12, 14)

    private val tensorTypeNames = mapOf(
        0 to "F32",
        1 to "F16",
        2 to "Q4_0",
        3 to "Q4_1",
        4 to "Q4_2",
        5 to "Q4_3",
        6 to "Q5_0",
        7 to "Q5_1",
        8 to "Q8_0",
        9 to "Q8_1",
        10 to "Q2_K",
        11 to "Q3_K",
        12 to "Q4_K",
        13 to "Q5_K",
        14 to "Q6_K",
        15 to "Q8_K",
        16 to "IQ2_XXS",
        17 to "IQ2_XS",
        18 to "IQ3_XXS",
        19 to "IQ1_S",
        20 to "IQ4_NL",
        21 to "IQ3_S",
        22 to "IQ2_S",
        23 to "IQ4_XS",
        24 to "I8",
        25 to "I16",
        26 to "I32",
        27 to "I64",
        28 to "F64",
        29 to "IQ1_M",
        30 to "BF16",
        31 to "Q4_0_4_4",
        32 to "Q4_0_4_8",
        33 to "Q4_0_8_8",
        34 to "TQ1_0",
        35 to "TQ2_0",
        36 to "IQ4_NL_4_4",
        37 to "IQ4_NL_4_8",
        38 to "IQ4_NL_8_8",
        39 to "MXFP4",
        40 to "NVFP4"
    )

    data class Metadata(
        val version: Int,
        val tensorCount: Long,
        val metadataCount: Long,
        val architecture: String?,
        val modelName: String?,
        val tokenizerModel: String?,
        val blockCount: Long?,
        val tensorTypes: Set<String>,
        val unsupportedTensorTypes: Set<String>,
        val tensorNames: List<String>
    )

    sealed class Result {
        data class Success(val metadata: Metadata) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Does not close [input]. The caller owns the stream lifecycle.
     */
    fun inspect(input: InputStream): Result {
        return try {
            Result.Success(parse(BoundedReader(input)))
        } catch (error: Exception) {
            Result.Failure(error.message?.takeIf { it.isNotBlank() } ?: "GGUF 文件头无效或不完整。")
        }
    }

    private fun parse(reader: BoundedReader): Metadata {
        val magic = reader.readBytes(4)
        if (!magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
            throw IOException("GGUF 文件标识错误。")
        }

        val versionLong = reader.readU32()
        if (versionLong !in 2L..3L) {
            throw IOException("GGUF 版本 $versionLong 暂不支持检查；当前仅接受版本 2 和 3。")
        }
        val tensorCount = reader.readU64()
        val metadataCount = reader.readU64()
        var architecture: String? = null
        var modelName: String? = null
        var tokenizerModel: String? = null
        var blockCount: Long? = null

        var metadataIndex = 0L
        while (metadataIndex < metadataCount) {
            val key = reader.readString()
            val valueType = reader.readU32()
            // Only decode the small metadata values needed by the readiness screen.
            // Token lists, chat templates and other user-controlled strings/arrays are streamed
            // past without allocating large Kotlin Strings or imposing the 4 MiB decode cap.
            val needsValue = key == "general.architecture" ||
                key == "general.name" ||
                key == "tokenizer.ggml.model" ||
                key?.endsWith(".block_count") == true
            val value = if (needsValue) reader.readMetadataValue(valueType) else {
                reader.skipMetadataValue(valueType)
                null
            }
            when {
                key == "general.architecture" -> architecture = value as? String
                key == "general.name" -> modelName = value as? String
                key == "tokenizer.ggml.model" -> tokenizerModel = value as? String
                key?.endsWith(".block_count") == true -> blockCount = value as? Long
            }
            metadataIndex++
        }

        val typeIds = linkedSetOf<Int>()
        val tensorNames = mutableListOf<String>()
        var tensorIndex = 0L
        while (tensorIndex < tensorCount) {
            val tensorName = reader.readString()
            val dimensionCount = reader.readU32()
            if (dimensionCount !in 1L..MAX_DIMENSIONS.toLong()) {
                throw IOException("GGUF 张量维度数量异常：${tensorName ?: "未知张量"}")
            }
            repeat(dimensionCount.toInt()) {
                val dimension = reader.readU64()
                if (dimension <= 0L) throw IOException("GGUF 张量维度无效：${tensorName ?: "未知张量"}")
            }
            val typeIdLong = reader.readU32()
            if (typeIdLong > Int.MAX_VALUE) {
                throw IOException("GGUF 张量类型编号无效：${tensorName ?: "未知张量"}")
            }
            typeIds += typeIdLong.toInt()
            reader.readU64() // relative offset into the tensor payload; payload remains unread
            if (tensorNames.size < 32 && tensorName != null) tensorNames += tensorName
            tensorIndex++
        }

        val typeNames = typeIds.map { tensorTypeNames[it] ?: "GGML_TYPE_$it" }.toSet()
        val unsupported = typeIds
            .filter { it !in supportedTensorTypeIds }
            .map { tensorTypeNames[it] ?: "GGML_TYPE_$it" }
            .toSet()

        return Metadata(
            version = versionLong.toInt(),
            tensorCount = tensorCount,
            metadataCount = metadataCount,
            architecture = architecture,
            modelName = modelName,
            tokenizerModel = tokenizerModel,
            blockCount = blockCount,
            tensorTypes = typeNames,
            unsupportedTensorTypes = unsupported,
            tensorNames = tensorNames
        )
    }

    private class BoundedReader(private val input: InputStream) {
        private var consumed = 0L

        fun readU32(): Long {
            var value = 0L
            repeat(4) { shift ->
                value = value or (readUnsignedByte().toLong() shl (shift * 8))
            }
            return value
        }

        fun readU64(): Long {
            var value = 0L
            repeat(8) { shift ->
                value = value or (readUnsignedByte().toLong() shl (shift * 8))
            }
            if (value < 0L) throw IOException("GGUF 长度字段超过允许范围。")
            return value
        }

        fun readString(): String? {
            val length = readU64()
            if (length > MAX_DECODED_STRING_BYTES) {
                // Keys and tensor names are normally short. If an unusual model contains a very
                // long one, skip it instead of rejecting the model or allocating a huge String.
                skipFully(length)
                return null
            }
            return String(readBytes(length.toInt()), StandardCharsets.UTF_8)
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0) throw IOException("GGUF 数据长度无效。")
            ensureWithinLimit(length.toLong())
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) throw EOFException("GGUF 文件头被截断。")
                if (count == 0) continue
                offset += count
                consumed += count.toLong()
            }
            return bytes
        }

        fun readMetadataValue(type: Long): Any? {
            return when (type) {
                0L, 1L, 7L -> readBytes(1)[0].toInt() and 0xff
                2L, 3L -> readBytes(2)
                4L, 5L -> readU32()
                6L -> readBytes(4)
                8L -> readString()
                9L -> {
                    skipMetadataArray()
                    null
                }
                10L, 11L -> readU64()
                12L -> readBytes(8)
                else -> throw IOException("GGUF 元数据类型 $type 未知。")
            }
        }

        /** Skips an arbitrary metadata value without materializing large strings or arrays. */
        fun skipMetadataValue(type: Long) {
            when (type) {
                0L, 1L, 7L -> skipFully(1)
                2L, 3L -> skipFully(2)
                4L, 5L, 6L -> skipFully(4)
                8L -> skipFully(readU64())
                9L -> skipMetadataArray()
                10L, 11L, 12L -> skipFully(8)
                else -> throw IOException("GGUF 元数据类型 $type 未知。")
            }
        }

        private fun skipMetadataArray() {
            val elementType = readU32()
            val count = readU64()
            if (elementType == 9L) throw IOException("GGUF 不支持嵌套元数据数组。")
            var index = 0L
            while (index < count) {
                skipMetadataElement(elementType)
                index++
            }
        }

        private fun skipMetadataElement(type: Long) {
            when (type) {
                0L, 1L, 7L -> skipFully(1)
                2L, 3L -> skipFully(2)
                4L, 5L, 6L -> skipFully(4)
                8L -> {
                    // Array string values can contain long tokenizer/chat-template entries.
                    // Stream-skip them; reads stop at the actual end of the GGUF input stream.
                    skipFully(readU64())
                }
                10L, 11L, 12L -> skipFully(8)
                else -> throw IOException("GGUF 元数据数组元素类型 $type 不受支持。")
            }
        }

        private fun skipFully(length: Long) {
            if (length < 0L) throw IOException("GGUF 数据长度无效。")
            ensureWithinLimit(length)
            var remaining = length
            val scratch = ByteArray(8192)
            while (remaining > 0L) {
                val request = minOf(remaining, scratch.size.toLong()).toInt()
                val count = input.read(scratch, 0, request)
                if (count < 0) throw EOFException("GGUF 文件头被截断。")
                if (count == 0) continue
                remaining -= count.toLong()
                consumed += count.toLong()
            }
        }

        private fun readUnsignedByte(): Int {
            ensureWithinLimit(1L)
            val value = input.read()
            if (value < 0) throw EOFException("GGUF 文件头被截断。")
            consumed++
            return value
        }

        private fun ensureWithinLimit(additional: Long) {
            // No fixed header-size ceiling: the real InputStream length is the boundary.
            // Keep arithmetic overflow checks before advancing the consumed-byte counter.
            if (additional < 0L || consumed > Long.MAX_VALUE - additional) {
                throw IOException("GGUF 文件偏移量溢出。")
            }
        }
    }
}
