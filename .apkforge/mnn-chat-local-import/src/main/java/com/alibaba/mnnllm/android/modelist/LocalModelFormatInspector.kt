package com.alibaba.mnnllm.android.modelist

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Lightweight, allocation-bounded inspection for common model files and folders.
 * It deliberately does not load tensor payloads or claim that a source format is convertible.
 */
object LocalModelFormatInspector {
    enum class Format {
        MNN, GGUF, SAFETENSORS, HUGGINGFACE_SAFETENSORS, ZIP, ONNX, TFLITE, PYTORCH, UNKNOWN
    }

    enum class State {
        READY_TO_IMPORT, RECOGNIZED_NEEDS_TEMPLATE, RECOGNIZED_INCOMPLETE, RECOGNIZED_NOT_CONVERTIBLE_HERE, INVALID, UNKNOWN
    }

    data class Detection(
        val format: Format,
        val state: State,
        val displayName: String,
        val details: String
    )

    data class TemplateCheck(
        val valid: Boolean,
        val missing: List<String>,
        val details: String
    )

    private val shardReference = Regex("\"([^\"]+\\.safetensors)\"")
    private val tokenizerFiles = setOf(
        "tokenizer.mtok", "tokenizer.txt", "tokenizer.json",
        "tokenizer.model", "tokenizer_config.json", "spiece.model", "vocab.json", "vocab.txt"
    )

    fun inspectFile(displayName: String, header: ByteArray): Detection {
        val name = displayName.substringAfterLast('/').substringAfterLast('\\')
        val lower = name.lowercase(Locale.ROOT)
        val extension = lower.substringAfterLast('.', "")
        val isGgufMagic = header.size >= 4 &&
            header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
            header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte()
        val isZipMagic = header.size >= 2 &&
            header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()

        if (extension == "gguf" || isGgufMagic) {
            if (!isGgufMagic || header.size < 8) {
                return Detection(Format.GGUF, State.INVALID, name, "GGUF 文件头无效或文件已损坏。")
            }
            val version = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (version !in 2..3) {
                return Detection(Format.GGUF, State.RECOGNIZED_NOT_CONVERTIBLE_HERE, name,
                    "已识别 GGUF，但版本 $version 不在当前检查器支持的范围内。")
            }
            return Detection(Format.GGUF, State.RECOGNIZED_NEEDS_TEMPLATE, name,
                "已识别 GGUF v$version。此 APK 尚未集成 GGUF→MNN 本地权重转换后端；当前只能识别，不能转换导入。")
        }

        if (extension == "zip" || isZipMagic) {
            return if (isZipMagic) {
                Detection(Format.ZIP, State.RECOGNIZED_NEEDS_TEMPLATE, name,
                    "已识别 ZIP 模型包；解压后只会导入通过校验的 MNN 模型目录。")
            } else {
                Detection(Format.ZIP, State.INVALID, name, "ZIP 文件头无效或文件已损坏。")
            }
        }

        return when (extension) {
            "safetensors" -> {
                if (header.size < 8) {
                    Detection(Format.SAFETENSORS, State.INVALID, name, "Safetensors 文件头不完整。")
                } else {
                    val headerLength = ByteBuffer.wrap(header, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    if (headerLength <= 0L || headerLength > 256L * 1024L * 1024L) {
                        Detection(Format.SAFETENSORS, State.INVALID, name, "Safetensors 文件头长度异常。")
                    } else {
                        Detection(Format.SAFETENSORS, State.RECOGNIZED_INCOMPLETE, name,
                            "已识别 Safetensors 权重文件。请导入包含 config.json、tokenizer 和全部权重分片的整个模型文件夹。")
                    }
                }
            }
            "onnx" -> Detection(Format.ONNX, State.RECOGNIZED_NOT_CONVERTIBLE_HERE, name,
                "已识别 ONNX。它不是可直接运行的 MNN 聊天模型，当前版本不提供 ONNX LLM 转换。")
            "tflite" -> Detection(Format.TFLITE, State.RECOGNIZED_NOT_CONVERTIBLE_HERE, name,
                "已识别 TFLite。当前 MNN Chat 版本不提供 TFLite 聊天模型转换。")
            "pt", "pth", "bin" -> Detection(Format.PYTORCH, State.RECOGNIZED_NOT_CONVERTIBLE_HERE, name,
                "已识别 PyTorch 权重文件。请提供完整 Hugging Face 模型目录；单个 .pt/.bin 文件不能直接导入。")
            else -> Detection(Format.UNKNOWN, State.UNKNOWN, name, "暂不识别此文件格式。")
        }
    }

    /**
     * fileNames are paths relative to the selected root; indexContents contains optional
     * root-level Safetensors index JSON content, never tensor payloads.
     */
    fun inspectDirectory(
        fileNames: List<String>,
        indexContents: Map<String, String> = emptyMap()
    ): Detection {
        val paths = fileNames.map { it.replace('\\', '/').trimStart('/') }
            .filter { it.isNotBlank() }
        val rootNames = paths.filter { '/' !in it }.map { it.lowercase(Locale.ROOT) }.toSet()

        if (hasRunnableMnnFiles(rootNames)) {
            return Detection(Format.MNN, State.READY_TO_IMPORT, "MNN 模型目录",
                "已发现 MNN 推理模型、配置和 tokenizer 文件，可以导入。")
        }

        val weightFiles = paths.filter { it.lowercase(Locale.ROOT).endsWith(".safetensors") }
        if ("config.json" in rootNames && weightFiles.isNotEmpty()) {
            val hasTokenizer = rootNames.any { it in tokenizerFiles }
            if (!hasTokenizer) {
                return Detection(Format.HUGGINGFACE_SAFETENSORS, State.RECOGNIZED_INCOMPLETE,
                    "Hugging Face Safetensors", "缺少 tokenizer 文件（如 tokenizer.json、tokenizer.model 或 tokenizer_config.json）。")
            }
            val referencedShards = indexContents.values.flatMap { contents ->
                shardReference.findAll(contents).map { it.groupValues[1] }.toList()
            }.toSet()
            val missingShards = referencedShards.filter { expected ->
                paths.none { it.equals(expected, ignoreCase = true) }
            }
            if (missingShards.isNotEmpty()) {
                return Detection(Format.HUGGINGFACE_SAFETENSORS, State.RECOGNIZED_INCOMPLETE,
                    "Hugging Face Safetensors",
                    "权重索引引用了缺失的分片：${missingShards.take(3).joinToString()}。")
            }
            return Detection(Format.HUGGINGFACE_SAFETENSORS, State.RECOGNIZED_NEEDS_TEMPLATE,
                "Hugging Face Safetensors",
                "已发现配置、tokenizer 和 ${weightFiles.size} 个权重文件。此 APK 尚未集成 Safetensors→MNN 本地权重转换后端，当前不能转换导入。")
        }

        if (weightFiles.isNotEmpty()) {
            return Detection(Format.HUGGINGFACE_SAFETENSORS, State.RECOGNIZED_INCOMPLETE,
                "Safetensors 文件夹", "缺少根目录 config.json 或 tokenizer 文件，不能作为完整模型转换。")
        }

        if ("config.json" in rootNames) {
            return Detection(Format.UNKNOWN, State.RECOGNIZED_INCOMPLETE, "模型文件夹",
                "发现 config.json，但没有检测到 MNN 推理文件或 Safetensors 权重。")
        }
        return Detection(Format.UNKNOWN, State.UNKNOWN, "未知模型目录", "未找到可识别的模型文件。")
    }

    fun inspectTemplate(fileNames: Set<String>): TemplateCheck {
        val names = fileNames.map { it.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT) }.toSet()
        val required = mutableListOf("config.json", "llm.mnn", "llm.mnn.json", "llm_config.json")
        if (names.none { it == "tokenizer.mtok" || it == "tokenizer.txt" }) {
            required += "tokenizer.mtok 或 tokenizer.txt"
        }
        val missing = required.filter { it !in names }
        return TemplateCheck(
            valid = missing.isEmpty(),
            missing = missing,
            details = if (missing.isEmpty()) "MNN 结构模板文件齐全。" else "模板缺少：${missing.joinToString()}。"
        )
    }

    private fun hasRunnableMnnFiles(rootNames: Set<String>): Boolean {
        return "config.json" in rootNames &&
            "llm.mnn" in rootNames &&
            "llm.mnn.weight" in rootNames &&
            "llm_config.json" in rootNames &&
            rootNames.any { it == "tokenizer.mtok" || it == "tokenizer.txt" }
    }
}
