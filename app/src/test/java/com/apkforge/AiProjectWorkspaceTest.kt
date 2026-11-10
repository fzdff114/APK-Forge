package com.apkforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProjectWorkspaceTest {
    @Test
    fun acceptsSafeAndroidProjectPaths() {
        val draft = AiProjectDraft(
            summary = "minimal app",
            files = listOf(
                AiGeneratedFile("settings.gradle.kts", "rootProject.name = \"Sample\""),
                AiGeneratedFile("app/build.gradle.kts", "plugins { }"),
                AiGeneratedFile("app/src/main/AndroidManifest.xml", "<manifest />"),
                AiGeneratedFile("app/src/main/java/sample/MainActivity.kt", "package sample\nclass MainActivity")
            ),
            notes = emptyList()
        )
        assertEquals(4, AiProjectWorkspace.validateDraft(draft).size)
    }

    @Test
    fun rejectsPathTraversal() {
        val draft = AiProjectDraft(
            summary = "bad path",
            files = listOf(AiGeneratedFile("../outside.txt", "not safe")),
            notes = emptyList()
        )
        val result = runCatching { AiProjectWorkspace.validateDraft(draft) }
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsLocalSdkAndSigningFiles() {
        val paths = listOf("local.properties", "app/keystore.jks", "app/gradlew")
        paths.forEach { path ->
            val result = runCatching {
                AiProjectWorkspace.validateDraft(
                    AiProjectDraft("unsafe", listOf(AiGeneratedFile(path, "x")), emptyList())
                )
            }
            assertTrue("Should reject $path", result.isFailure)
        }
    }
}
