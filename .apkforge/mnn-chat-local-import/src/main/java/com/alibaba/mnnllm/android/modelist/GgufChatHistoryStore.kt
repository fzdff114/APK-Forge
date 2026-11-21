package com.alibaba.mnnllm.android.modelist

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Stores the most recent transcript separately for each imported GGUF model folder. */
object GgufChatHistoryStore {
    fun fileFor(filesDirectory: File, modelFile: File): File {
        val modelIdentity = modelFile.parentFile?.name ?: modelFile.name
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(modelIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(File(filesDirectory, "gguf_chat_history"), "$digest.txt")
    }

    fun read(file: File): String =
        if (file.isFile) file.readText(Charsets.UTF_8) else ""

    fun write(file: File, transcript: String) {
        val parent = file.parentFile ?: throw IllegalStateException("Chat history folder is missing.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Cannot create GGUF chat history folder.")
        }
        val temporary = File(parent, file.name + "." + UUID.randomUUID().toString() + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(transcript.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                temporary.delete()
                throw IllegalStateException("Cannot replace GGUF chat history.")
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw IllegalStateException("Cannot save GGUF chat history.")
            }
        }
    }

    fun clear(file: File): Boolean = !file.exists() || file.delete()
}
