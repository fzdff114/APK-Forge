import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))
import apkforge


class ApkForgeToolTests(unittest.TestCase):
    def test_scaffold_creates_buildable_android_structure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "generated-project"
            apkforge.scaffold("Notes App", "com.example.notes", root)
            self.assertTrue((root / "settings.gradle.kts").is_file())
            self.assertTrue((root / "app/build.gradle.kts").is_file())
            self.assertTrue((root / "app/src/main/AndroidManifest.xml").is_file())
            report = apkforge.scan_project(root)
            self.assertEqual(report.errors, 0, apkforge.render_report(report))
            self.assertIn("app", report.android_modules)

    def test_scanner_detects_missing_project_and_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("not an Android project", encoding="utf-8")
            report = apkforge.scan_project(root)
            self.assertGreaterEqual(report.errors, 3)
            codes = {issue.code for issue in report.issues if issue.severity == "ERROR"}
            self.assertIn("PROJECT_SCAN", codes)
            self.assertIn("GRADLE_REPAIR", codes)
            self.assertIn("MANIFEST_REPAIR", codes)
            self.assertIn("SOURCE_COMPLETION", codes)

    def test_manifest_exported_error_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apkforge.scaffold("Test App", "com.example.test", root)
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.write_text(
                '<?xml version="1.0"?><manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<application><activity android:name=".MainActivity"><intent-filter>'
                '<action android:name="android.intent.action.MAIN"/></intent-filter></activity></application></manifest>',
                encoding="utf-8",
            )
            report = apkforge.scan_project(root)
            self.assertTrue(any(
                issue.code == "MANIFEST_REPAIR" and issue.severity == "ERROR"
                and "exported" in issue.title.lower()
                for issue in report.issues
            ))

    def test_scaffold_rejects_invalid_application_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                apkforge.scaffold("Bad App", "not a package", Path(tmp) / "out")

    def test_verify_checks_apk_manifest_and_dex(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "sample.apk"
            with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("AndroidManifest.xml", b"binary-xml-placeholder")
                archive.writestr("classes.dex", b"dex-placeholder")
            result = apkforge.verify_apk(apk)
            self.assertTrue(result["verified"])
            self.assertEqual(result["dex_files"], ["classes.dex"])
            self.assertEqual(len(result["sha256"]), 64)

    def test_verify_rejects_zip_without_dex(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "broken.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
            with self.assertRaises(RuntimeError):
                apkforge.verify_apk(apk)

    def test_archive_excludes_local_files_and_build_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "project"
            apkforge.scaffold("Archive App", "com.example.archive", root)
            (root / "local.properties").write_text("sdk.dir=/private/sdk", encoding="utf-8")
            (root / "app/build/outputs/apk/debug").mkdir(parents=True)
            (root / "app/build/outputs/apk/debug/app-debug.apk").write_bytes(b"not a source file")
            result = apkforge.archive_project(root, Path(tmp) / "source.zip", max_mib=2)
            self.assertTrue(Path(result["archive"]).is_file())
            with zipfile.ZipFile(result["archive"]) as archive:
                names = archive.namelist()
                self.assertIn("app/build.gradle.kts", names)
                self.assertNotIn("local.properties", names)
                self.assertFalse(any("/build/" in "/" + name for name in names))
                self.assertFalse(any(name.endswith(".apk") for name in names))


if __name__ == "__main__":
    unittest.main()
