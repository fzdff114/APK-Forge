package com.alibaba.mnnllm.android.modelist

/**
 * Guards the conversion path. The current APK package contains the MNN inference runtime,
 * but does not yet bundle a tested GGUF/Safetensors-to-MNN native weight converter.
 */
object LocalModelConversionManager {
    enum class Status {
        MISSING_TEMPLATE,
        MISSING_NATIVE_BACKEND,
        UNSUPPORTED_SOURCE,
        SOURCE_INCOMPLETE,
        SOURCE_INVALID
    }

    data class ConversionReadiness(
        val status: Status,
        val canConvert: Boolean,
        val reason: String
    )

    fun inspectConversion(
        source: LocalModelFormatInspector.Detection,
        template: LocalModelFormatInspector.TemplateCheck?
    ): ConversionReadiness {
        if (source.format != LocalModelFormatInspector.Format.GGUF &&
            source.format != LocalModelFormatInspector.Format.HUGGINGFACE_SAFETENSORS
        ) {
            return ConversionReadiness(
                Status.UNSUPPORTED_SOURCE,
                false,
                "当前只检查 GGUF 和完整的 Hugging Face Safetensors 模型目录。"
            )
        }

        if (source.state == LocalModelFormatInspector.State.INVALID) {
            return ConversionReadiness(Status.SOURCE_INVALID, false, source.details)
        }
        if (source.state == LocalModelFormatInspector.State.RECOGNIZED_INCOMPLETE) {
            return ConversionReadiness(Status.SOURCE_INCOMPLETE, false, source.details)
        }
        if (template == null || !template.valid) {
            val reason = template?.details ?: "尚未选择匹配的 MNN 模型结构模板。"
            return ConversionReadiness(Status.MISSING_TEMPLATE, false, reason)
        }

        // Do not expose a fake Convert action: this APK does not ship the required
        // native weight mapping/quantization backend yet.
        return ConversionReadiness(
            Status.MISSING_NATIVE_BACKEND,
            false,
            "MNN 推理库已集成，但 GGUF/Safetensors 权重转换器尚未编入此 APK。未生成任何转换文件。"
        )
    }
}
