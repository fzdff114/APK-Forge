package com.alibaba.mnnllm.android.modelist

import android.system.Os
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

    /**
     * Writes and fsyncs a sibling temporary file, then atomically renames it over the old
     * transcript. If the commit fails, the previous history remains untouched.
     */
    fun write(file: File, transcript: String) {
        val parent = file.parentFile ?: throw IllegalStateException("Chat history folder is missing.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Cannot create GGUF chat history folder.")
        }
        val temporary = File(parent, file.name + "." + UUID.randomUUID().toString() + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(transcript.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Os.rename(temporary.absolutePath, file.absolutePath)
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Cannot commit GGUF chat history; the previous transcript was preserved.",
                    error
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun clear(file: File): Boolean = !file.exists() || file.delete()
}
