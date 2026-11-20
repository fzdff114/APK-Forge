package com.alibaba.mnnllm.android.modelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalModelArchiveImporterTest {
    @Test
    fun rejectsAbsoluteAndTraversalPaths() {
        assertNull(LocalModelArchiveImporter.normalizedEntryPath("../outside.txt"))
        assertNull(LocalModelArchiveImporter.normalizedEntryPath("model/../../outside.txt"))
        assertNull(LocalModelArchiveImporter.normalizedEntryPath("/absolute/file"))
        assertNull(LocalModelArchiveImporter.normalizedEntryPath("C:/outside/file"))
        assertNull(LocalModelArchiveImporter.normalizedEntryPath("model\\..\\outside.txt"))
    }

    @Test
    fun acceptsSafeNestedPathsAndDirectoryEntries() {
        assertEquals("Qwen/config.json", LocalModelArchiveImporter.normalizedEntryPath("Qwen/config.json"))
        assertEquals("Qwen", LocalModelArchiveImporter.normalizedEntryPath("Qwen/"))
    }

    @Test
    fun importsOnlyACompleteMnnModelFromZip() = withTemporaryRoot { root ->
        val bytes = modelZip(
            "wrapped/Qwen/config.json",
            "wrapped/Qwen/llm.mnn",
            "wrapped/Qwen/llm.mnn.weight",
            "wrapped/Qwen/llm_config.json",
            "wrapped/Qwen/tokenizer.mtok"
        )

        val imported = LocalModelArchiveImporter.importMnnZipToDirectory(
            ByteArrayInputStream(bytes), root, "qwen-bundle"
        )

        assertTrue(imported.isDirectory)
        assertTrue(File(imported, "config.json").isFile)
        assertTrue(File(imported, "llm.mnn").isFile)
        assertTrue(File(imported, "llm.mnn.weight").isFile)
        assertTrue(File(imported, "llm_config.json").isFile)
        assertTrue(File(imported, "tokenizer.mtok").isFile)
    }

    @Test
    fun rejectsZipWithoutRunnableMnnFiles() = withTemporaryRoot { root ->
        val bytes = modelZip("source/config.json", "source/model.safetensors")

        try {
            LocalModelArchiveImporter.importMnnZipToDirectory(
                ByteArrayInputStream(bytes), root, "source"
            )
            throw AssertionError("Expected archive without MNN files to be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("没有完整的 MNN 模型目录"))
        }
    }

    @Test
    fun rejectsArchiveWhenDuplicatePathWouldOverwriteAnExistingEntry() = withTemporaryRoot { root ->
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf("model/config.json", "model/config.json").forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }

        try {
            LocalModelArchiveImporter.importMnnZipToDirectory(
                ByteArrayInputStream(output.toByteArray()), root, "duplicate"
            )
            throw AssertionError("Expected duplicate archive path to be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("重复"))
        }
    }

    private fun withTemporaryRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mnn-local-model-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun modelZip(vararg paths: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            paths.forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
