package com.alibaba.mnnllm.android.modelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LocalGgufModelLibraryTest {
    @Test
    fun listsSingleFileModelAndReportsItsSize() {
        val root = Files.createTempDirectory("gguf-library-single").toFile()
        try {
            val folder = File(root, "qwen_1234").apply { mkdirs() }
            File(folder, "Qwen2.5-0.5B-Q4_K_M.gguf").writeBytes(byteArrayOf(1, 2, 3, 4))

            val entries = LocalGgufModelLibrary.scan(root)

            assertEquals(1, entries.size)
            assertEquals("Qwen2.5-0.5B-Q4_K_M", entries.single().displayName)
            assertEquals(4L, entries.single().totalBytes)
            assertTrue(entries.single().isComplete)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recognizesCompleteShardsAndFlagsMissingParts() {
        val root = Files.createTempDirectory("gguf-library-shards").toFile()
        try {
            val complete = File(root, "complete").apply { mkdirs() }
            File(complete, "model-00001-of-00002.gguf").writeBytes(byteArrayOf(1))
            File(complete, "model-00002-of-00002.gguf").writeBytes(byteArrayOf(2))
            val incomplete = File(root, "incomplete").apply { mkdirs() }
            File(incomplete, "other-00001-of-00002.gguf").writeBytes(byteArrayOf(3))

            val entries = LocalGgufModelLibrary.scan(root).associateBy { it.folder.name }

            assertTrue(entries.getValue("complete").isComplete)
            assertEquals(2, entries.getValue("complete").files.size)
            assertFalse(entries.getValue("incomplete").isComplete)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteCannotEscapeTheLibraryRoot() {
        val parent = Files.createTempDirectory("gguf-library-delete").toFile()
        try {
            val root = File(parent, "gguf_models").apply { mkdirs() }
            val outside = File(parent, "keep").apply { mkdirs() }
            File(outside, "important.txt").writeText("keep")
            var failed = false
            try {
                LocalGgufModelLibrary.delete(root, outside)
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
            assertTrue(File(outside, "important.txt").isFile)
        } finally {
            parent.deleteRecursively()
        }
    }
}
