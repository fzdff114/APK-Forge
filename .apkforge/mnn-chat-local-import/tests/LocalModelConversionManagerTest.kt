package com.alibaba.mnnllm.android.modelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalModelConversionManagerTest {
    private val gguf = LocalModelFormatInspector.Detection(
        LocalModelFormatInspector.Format.GGUF,
        LocalModelFormatInspector.State.RECOGNIZED_NEEDS_TEMPLATE,
        "model.gguf",
        "recognized"
    )
    private val validTemplate = LocalModelFormatInspector.TemplateCheck(true, emptyList(), "valid")

    @Test
    fun asksForTemplateBeforeCheckingNativeBackend() {
        val result = LocalModelConversionManager.inspectConversion(gguf, null)

        assertEquals(LocalModelConversionManager.Status.MISSING_TEMPLATE, result.status)
        assertFalse(result.canConvert)
    }

    @Test
    fun reportsMissingNativeBackendEvenWhenTemplateIsValid() {
        val result = LocalModelConversionManager.inspectConversion(gguf, validTemplate)

        assertEquals(LocalModelConversionManager.Status.MISSING_NATIVE_BACKEND, result.status)
        assertFalse(result.canConvert)
        assertTrue(result.reason.contains("尚未编入此 APK"))
    }

    @Test
    fun rejectsUnsupportedFormat() {
        val onnx = LocalModelFormatInspector.Detection(
            LocalModelFormatInspector.Format.ONNX,
            LocalModelFormatInspector.State.RECOGNIZED_NOT_CONVERTIBLE_HERE,
            "model.onnx",
            "onnx"
        )
        val result = LocalModelConversionManager.inspectConversion(onnx, validTemplate)

        assertEquals(LocalModelConversionManager.Status.UNSUPPORTED_SOURCE, result.status)
        assertFalse(result.canConvert)
    }
}
