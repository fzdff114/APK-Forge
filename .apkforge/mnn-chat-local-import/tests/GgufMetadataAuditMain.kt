package com.alibaba.mnnllm.android.modelist

import java.io.File
import kotlin.system.exitProcess

/**
 * Runs the exact Kotlin GGUF header reader used by the Android app against a real downloaded model.
 * This catches parser/import compatibility gaps that a host llama.cpp smoke test cannot catch.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: GgufMetadataAudit <model.gguf>" }
    val model = File(args[0])
    require(model.isFile) { "Model file does not exist: ${model.absolutePath}" }

    println("MODEL_PATH=${model.absolutePath}")
    println("MODEL_SIZE_BYTES=${model.length()}")
    when (val result = GgufMetadataReader.inspect(model.inputStream().buffered())) {
        is GgufMetadataReader.Result.Success -> {
            val m = result.metadata
            println("GGUF_HEADER_RESULT=PASS")
            println("GGUF_VERSION=${m.version}")
            println("GGUF_ARCHITECTURE=${m.architecture}")
            println("GGUF_MODEL_NAME=${m.modelName}")
            println("GGUF_TENSOR_COUNT=${m.tensorCount}")
            println("GGUF_METADATA_COUNT=${m.metadataCount}")
            println("GGUF_TENSOR_TYPES=${m.tensorTypes.sorted().joinToString(",")}")
        }
        is GgufMetadataReader.Result.Failure -> {
            System.err.println("GGUF_HEADER_RESULT=FAIL")
            System.err.println("GGUF_HEADER_ERROR=${result.reason}")
            exitProcess(2)
        }
    }
}
