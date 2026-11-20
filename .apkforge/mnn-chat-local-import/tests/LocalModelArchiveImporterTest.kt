package com.alibaba.mnnllm.android.modelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
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
    fun importsOnlyACompleteMnnModelFromZip() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = modelZip(
            "wrapped/Qwen/config.json",
            "wrapped/Qwen/llm.mnn",
            "wrapped/Qwen/llm.mnn.weight",
            "wrapped/Qwen/llm_config.json",
            "wrapped/Qwen/tokenizer.mtok"
        )

        val imported = LocalModelArchiveImporter.importMnnZip(
            context, ByteArrayInputStream(bytes), "qwen-bundle.zip"
        )

        assertTrue(imported.isDirectory)
        assertTrue(java.io.File(imported, "config.json").isFile)
        assertTrue(java.io.File(imported, "llm.mnn").isFile)
        assertTrue(java.io.File(imported, "llm.mnn.weight").isFile)
        assertTrue(java.io.File(imported, "llm_config.json").isFile)
        assertTrue(java.io.File(imported, "tokenizer.mtok").isFile)
    }

    @Test
    fun rejectsZipWithoutRunnableMnnFiles() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = modelZip("source/config.json", "source/model.safetensors")

        try {
            LocalModelArchiveImporter.importMnnZip(context, ByteArrayInputStream(bytes), "source.zip")
            throw AssertionError("Expected archive without MNN files to be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("没有完整的 MNN 模型目录"))
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
