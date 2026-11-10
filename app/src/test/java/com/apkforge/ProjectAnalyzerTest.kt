package com.apkforge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAnalyzerTest {
    @Test
    fun reportsCompleteMinimalAndroidProject() {
        val root = createTempDir(prefix = "apkforge-complete-")
        try {
            File(root, "settings.gradle.kts").writeText("rootProject.name = \"Sample\"")
            val module = File(root, "app").apply { mkdirs() }
            File(module, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\nandroid { namespace = \"test.app\"; compileSdk = 35 }"
            )
            val source = File(module, "src/main/java/test/app").apply { mkdirs() }
            File(source, "MainActivity.kt").writeText("package test.app\nclass MainActivity")
            val manifestDir = File(module, "src/main").apply { mkdirs() }
            File(manifestDir, "AndroidManifest.xml").writeText(
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application><activity android:name=\".MainActivity\" android:exported=\"true\" /></application></manifest>"
            )
            val wrapper = File(root, "gradle/wrapper").apply { mkdirs() }
            File(wrapper, "gradle-wrapper.properties").writeText("distributionUrl=https://services.gradle.org/distributions/gradle-8.11.1-bin.zip")

            val report = ProjectAnalyzer.analyze(root, 5)
            assertEquals(0, report.errorCount)
            assertTrue(report.passCount >= 4)
            assertTrue(report.issues.any { it.title == "Gradle Wrapper 不完整" && it.severity == Severity.WARNING })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun flagsMissingGradleManifestAndSourceFiles() {
        val root = createTempDir(prefix = "apkforge-incomplete-")
        try {
            File(root, "README.md").writeText("incomplete project")
            val report = ProjectAnalyzer.analyze(root, 1)
            assertTrue(report.errorCount >= 3)
            assertTrue(report.issues.any { it.code == "PROJECT_SCAN" && it.severity == Severity.ERROR })
            assertTrue(report.issues.any { it.code == "MANIFEST_REPAIR" && it.severity == Severity.ERROR })
            assertTrue(report.issues.any { it.code == "SOURCE_COMPLETION" && it.severity == Severity.ERROR })
        } finally {
            root.deleteRecursively()
        }
    }
}
