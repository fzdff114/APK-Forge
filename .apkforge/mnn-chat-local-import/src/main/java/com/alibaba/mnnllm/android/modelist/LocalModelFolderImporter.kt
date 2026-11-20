package com.alibaba.mnnllm.android.modelist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Imports an MNN model folder selected with Android's Storage Access Framework.
 * The selected folder must contain config.json directly under its root.
 */
object LocalModelFolderImporter {
    private data class ChildDocument(
        val id: String,
        val name: String,
        val mimeType: String?
    )

    @Throws(IOException::class)
    fun importFromTree(context: Context, treeUri: Uri): File {
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            throw IOException("Unable to read the selected model folder", e)
        }
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)

        val rootChildren = queryChildren(context, treeUri, rootDocumentId)
        val indexDocument = rootChildren.firstOrNull {
            it.name.endsWith(".safetensors.index.json", ignoreCase = true)
        }
        val indexContents = if (indexDocument == null) {
            emptyMap()
        } else {
            mapOf(indexDocument.name to readBoundedIndex(context, treeUri, indexDocument.id))
        }
        val rootDetection = LocalModelFormatInspector.inspectDirectory(
            rootChildren.map { it.name },
            indexContents
        )
        if (rootDetection.format != LocalModelFormatInspector.Format.MNN ||
            rootDetection.state != LocalModelFormatInspector.State.READY_TO_IMPORT
        ) {
            throw IllegalArgumentException(rootDetection.details)
        }

        val rootName = queryDisplayName(context, rootDocumentUri)
        val safeRootName = safeFileName(rootName)
        val destinationRoot = context.getExternalFilesDir("local_models")
            ?: File(context.filesDir, "local_models")
        if (!destinationRoot.exists() && !destinationRoot.mkdirs()) {
            throw IOException("Unable to create the app's local model directory")
        }

        val destination = uniqueDestination(destinationRoot, safeRootName)
        val staging = File(destinationRoot, ".${destination.name}.${UUID.randomUUID()}.importing")
        if (!staging.mkdirs()) {
            throw IOException("Unable to create a temporary import directory")
        }

        try {
            copyChildren(context, treeUri, rootDocumentId, staging)
            val copiedDetection = LocalModelFormatInspector.inspectDirectory(
                staging.listFiles()?.map { it.name } ?: emptyList()
            )
            if (copiedDetection.format != LocalModelFormatInspector.Format.MNN ||
                copiedDetection.state != LocalModelFormatInspector.State.READY_TO_IMPORT
            ) {
                throw IOException(copiedDetection.details)
            }
            if (!staging.renameTo(destination)) {
                throw IOException("Unable to finish importing the model folder")
            }
            return destination
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun containsRootConfig(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String
    ): Boolean {
        val children = queryChildren(context, treeUri, parentDocumentId)
        return children.any {
            it.name == "config.json" && it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR
        }
    }

    private fun readBoundedIndex(context: Context, treeUri: Uri, documentId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取 Safetensors 权重索引。")
        val output = ByteArrayOutputStream()
        input.use { source ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                if (total > 4 * 1024 * 1024) {
                    throw IOException("Safetensors 权重索引超过 4 MB 安全上限。")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun queryDisplayName(context: Context, documentUri: Uri): String {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val cursor = context.contentResolver.query(documentUri, projection, null, null, null)
            ?: throw IOException("Unable to read the selected folder name")
        cursor.use {
            if (!it.moveToFirst()) throw IOException("The selected folder is unavailable")
            val index = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index < 0) throw IOException("The selected folder name is unavailable")
            return it.getString(index) ?: "LocalModel"
        }
    }

    private fun queryChildren(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String
    ): List<ChildDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Unable to read a folder in the selected model")

        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val result = mutableListOf<ChildDocument>()
            while (it.moveToNext()) {
                result += ChildDocument(
                    id = it.getString(idIndex),
                    name = it.getString(nameIndex) ?: "",
                    mimeType = it.getString(mimeIndex)
                )
            }
            result
        }
    }

    private fun copyChildren(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
        destinationDirectory: File
    ) {
        for (child in queryChildren(context, treeUri, parentDocumentId)) {
            val target = File(destinationDirectory, safeFileName(child.name))
            if (target.exists()) {
                throw IOException("Duplicate item name in the selected model folder: ${child.name}")
            }
            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)

            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (!target.exists() && !target.mkdirs()) {
                    throw IOException("Unable to create model subfolder: ${child.name}")
                }
                copyChildren(context, treeUri, child.id, target)
            } else {
                val input = context.contentResolver.openInputStream(childUri)
                    ?: throw IOException("Unable to read model file: ${child.name}")
                input.use { source ->
                    target.outputStream().buffered().use { output ->
                        source.copyTo(output, 1024 * 1024)
                    }
                }
            }
        }
    }

    private fun safeFileName(name: String): String {
        val safeName = name.replace('/', '_').replace('\\', '_')
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw IOException("The selected model contains an invalid file name")
        }
        return safeName
    }

    private fun uniqueDestination(parent: File, baseName: String): File {
        var candidate = File(parent, baseName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(parent, "$baseName ($suffix)")
            suffix++
        }
        return candidate
    }
}
