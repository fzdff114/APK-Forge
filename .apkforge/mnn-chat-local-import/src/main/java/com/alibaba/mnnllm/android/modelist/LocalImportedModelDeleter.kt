package com.alibaba.mnnllm.android.modelist

import android.content.Context
import com.alibaba.mls.api.ModelItem
import com.alibaba.mnnllm.android.chat.model.ChatDataManager
import com.alibaba.mnnllm.android.history.HistoryUtils
import java.io.File
import java.io.IOException

/**
 * Deletes only model folders imported into this app's own local-model directories.
 * ADB-managed models and paths outside app-owned roots are intentionally not deletable here.
 */
object LocalImportedModelDeleter {
    private const val GGUF_ID_PREFIX = "local-gguf/"

    private data class ManagedTarget(
        val root: File,
        val target: File,
        val isGguf: Boolean,
        val modelFile: File?
    )

    private fun roots(context: Context, directory: String): List<File> {
        val result = mutableListOf<File>()
        context.getExternalFilesDir(directory)?.let { result += it }
        result += File(context.filesDir, directory)
        return result.map { it.canonicalFile }.distinctBy { it.path }
    }

    private fun resolve(context: Context, modelItem: ModelItem): ManagedTarget? {
        val modelId = modelItem.modelId ?: return null
        val localPath = modelItem.localPath ?: return null
        val path = File(localPath).canonicalFile
        val isGguf = modelId.startsWith(GGUF_ID_PREFIX)

        if (isGguf) {
            if (!path.isFile || !path.extension.equals("gguf", ignoreCase = true)) return null
            val folder = path.parentFile ?: return null
            val root = roots(context, "gguf_models").firstOrNull {
                folder.parentFile?.canonicalFile == it && folder != it
            } ?: return null
            return ManagedTarget(root, folder, true, path)
        }

        if (!modelItem.isLocal || !path.isDirectory) return null
        val root = roots(context, "local_models").firstOrNull {
            path.parentFile?.canonicalFile == it && path != it
        } ?: return null
        return ManagedTarget(root, path, false, null)
    }

    fun isManagedLocal(context: Context, modelItem: ModelItem): Boolean =
        resolve(context, modelItem) != null

    @Throws(IOException::class)
    fun delete(context: Context, modelItem: ModelItem): Boolean {
        val target = resolve(context, modelItem)
            ?: throw IOException("只允许删除应用通过本地导入功能保存的模型。")
        val modelId = modelItem.modelId
            ?: throw IOException("模型 ID 丢失，无法清理对应记录。")

        if (target.isGguf) {
            LocalGgufModelLibrary.delete(target.root, target.target)
            target.modelFile?.let { modelFile ->
                GgufChatHistoryStore.clear(
                    GgufChatHistoryStore.fileFor(context.filesDir, modelFile)
                )
            }
        } else if (!target.target.deleteRecursively()) {
            throw IOException("无法删除本地模型文件夹：${target.target.name}")
        }

        val chatDataManager = ChatDataManager.getInstance(context)
        chatDataManager.getSessionsForModel(modelId).forEach { session ->
            HistoryUtils.deleteHistory(context, chatDataManager, session.sessionId)
        }
        return true
    }
}
