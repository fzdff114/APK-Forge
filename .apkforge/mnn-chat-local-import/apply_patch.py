#!/usr/bin/env python3
"""Apply the MNN Chat local-model folder picker patch to a clean official checkout."""
from pathlib import Path

FILES = {
    "main_activity": Path("apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/main/MainActivity.kt"),
    "local_provider": Path("apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelsProvider.kt"),
    "main_layout": Path("apps/Android/MnnLlmChat/app/src/main/res/layout/activity_main.xml"),
    "strings_zh": Path("apps/Android/MnnLlmChat/app/src/main/res/values-zh-rCN/strings.xml"),
    "strings_en": Path("apps/Android/MnnLlmChat/app/src/main/res/values/strings.xml"),
}
HELPER_RELATIVE = Path("apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelFolderImporter.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Cannot safely patch {label}: expected one matching anchor, found {count}.")
    return text.replace(old, new, 1)


def read_required(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"Missing source file: {path}")
    return path.read_text(encoding="utf-8")


def main() -> None:
    contents = {key: read_required(path) for key, path in FILES.items()}
    helper_path = HELPER_RELATIVE
    if helper_path.exists() or "localModelFolderPicker" in contents["main_activity"]:
        raise RuntimeError("The file-manager import patch appears to be already applied.")

    main_activity = contents["main_activity"]
    main_activity = replace_once(
        main_activity,
        "import android.content.Intent\n",
        "import android.content.Intent\n"
        "import android.app.ProgressDialog\n"
        "import android.net.Uri\n"
        "import android.provider.OpenableColumns\n"
        "import androidx.activity.result.contract.ActivityResultContracts\n"
        "import androidx.lifecycle.lifecycleScope\n",
        "MainActivity imports (Android)",
    )
    main_activity = replace_once(
        main_activity,
        "import com.alibaba.mnnllm.android.modelist.ModelListFragment\n",
        "import com.alibaba.mnnllm.android.modelist.ModelListFragment\n"
        "import com.alibaba.mnnllm.android.modelist.ModelListManager\n"
        "import com.alibaba.mnnllm.android.modelist.LocalModelFolderImporter\n"
        "import com.alibaba.mnnllm.android.modelist.LocalModelArchiveImporter\n"
        "import com.alibaba.mnnllm.android.modelist.LocalModelFormatInspector\n",
        "MainActivity imports (model list)",
    )
    main_activity = replace_once(
        main_activity,
        "import kotlinx.coroutines.MainScope\n",
        "import kotlinx.coroutines.MainScope\nimport kotlinx.coroutines.Dispatchers\n",
        "MainActivity imports (coroutines dispatcher)",
    )
    main_activity = replace_once(
        main_activity,
        "import kotlinx.coroutines.launch\n",
        "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n",
        "MainActivity imports (withContext)",
    )

    picker_property = '''\n    // SAF folder picker is independent of the existing ADB import flow.\n    private val localModelFolderPicker = registerForActivityResult(\n        ActivityResultContracts.OpenDocumentTree()\n    ) { uri ->\n        if (uri != null) {\n            try {\n                contentResolver.takePersistableUriPermission(\n                    uri,\n                    Intent.FLAG_GRANT_READ_URI_PERMISSION\n                )\n            } catch (e: Exception) {\n                Log.w(TAG, "Could not persist model-folder permission; continuing with the temporary grant", e)\n            }\n            importLocalModelFromUri(uri)\n        }\n    }\n'''
    main_activity = replace_once(
        main_activity,
        "    private lateinit var mainFragmentManager: MainFragmentManager\n",
        "    private lateinit var mainFragmentManager: MainFragmentManager\n" + picker_property,
        "MainActivity folder-picker property",
    )
    file_picker_property = '''\n    // Single-file picker is used for GGUF, ZIP and diagnostic format recognition.\n    private val localModelFilePicker = registerForActivityResult(\n        ActivityResultContracts.OpenDocument()\n    ) { uri ->\n        if (uri != null) {\n            importLocalModelFile(uri)\n        }\n    }\n'''
    main_activity = replace_once(
        main_activity,
        "    private val currentFragment: Fragment?\n",
        file_picker_property + "    private val currentFragment: Fragment?\n",
        "MainActivity model-file picker property",
    )

    import_methods = '''    /** Opens Android's system folder picker and imports a model without requiring a computer. */
    fun addLocalModelFromFiles(view: View?) {
        localModelFolderPicker.launch(null)
    }

    private fun importLocalModelFromUri(treeUri: Uri) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle(R.string.local_model_import_title)
            setMessage(getString(R.string.local_model_import_progress))
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                val importedDirectory = withContext(Dispatchers.IO) {
                    LocalModelFolderImporter.importFromTree(this@MainActivity, treeUri)
                }
                ModelListManager.clearModelCache()
                ModelListManager.notifyModelListMayChange(ModelListManager.ChangeReason.MANUAL_REFRESH)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.local_model_import_success, importedDirectory.name),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import a local model from the file picker", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.local_model_import_failed, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    /** Opens the system file picker. Model tensors remain on-device and are never uploaded. */
    fun addLocalModelFile(view: View?) {
        localModelFilePicker.launch(arrayOf("*/*"))
    }

    private fun queryLocalModelDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "model"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model"
    }

    private fun importLocalModelFile(uri: Uri) {
        val displayName = queryLocalModelDisplayName(uri)
        val progressDialog = ProgressDialog(this).apply {
            setTitle(R.string.local_model_import_title)
            setMessage(getString(R.string.local_model_import_progress))
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val header = contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArray(8)
                        var count = 0
                        while (count < bytes.size) {
                            val read = input.read(bytes, count, bytes.size - count)
                            if (read <= 0) break
                            count += read
                        }
                        bytes.copyOf(count)
                    } ?: byteArrayOf()
                    val detection = LocalModelFormatInspector.inspectFile(displayName, header)
                    val importedDirectory = if (detection.format == LocalModelFormatInspector.Format.ZIP &&
                        detection.state != LocalModelFormatInspector.State.INVALID
                    ) {
                        val zipInput = contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("无法读取所选 ZIP 文件。")
                        zipInput.use {
                            LocalModelArchiveImporter.importMnnZip(this@MainActivity, it, displayName)
                        }
                    } else {
                        null
                    }
                    Pair(importedDirectory, detection)
                }

                val importedDirectory = result.first
                if (importedDirectory != null) {
                    ModelListManager.clearModelCache()
                    ModelListManager.notifyModelListMayChange(ModelListManager.ChangeReason.MANUAL_REFRESH)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.local_model_import_success, importedDirectory.name),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, result.second.details, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inspect or import a local model file", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.local_model_import_failed, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progressDialog.dismiss()
            }
        }
    }

'''
    main_activity = replace_once(
        main_activity,
        "    fun runModel(destModelDir: String?, modelIdParam: String?, sessionId: String?) {",
        import_methods + "    fun runModel(destModelDir: String?, modelIdParam: String?, sessionId: String?) {",
        "MainActivity import methods",
    )
    contents["main_activity"] = main_activity

    old_provider_block = '''        try {
            val modelsDir = File("/data/local/tmp/mnn_models/")
            if (modelsDir.exists() && modelsDir.isDirectory) {
                modelsDir.listFiles()?.forEach { modelDir ->
                    if (modelDir.isDirectory && File(modelDir, "config.json").exists()) {
                        val modelPath = modelDir.absolutePath
                        val modelId = "local/${modelPath}"
                        result.add(ModelItem.fromLocalModel(modelId, modelPath))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalModelsProvider", "Failed to load models from /data/local/tmp/mnn_models/", e)
        }
'''
    new_provider_block = '''        try {
            val appContext = com.alibaba.mnnllm.android.MnnLlmApplication.getAppContext()
            val appModelsDir = appContext.getExternalFilesDir("local_models")
                ?: File(appContext.filesDir, "local_models")

            // Keep the original ADB directory and also scan folders imported from Android's file picker.
            val modelRoots = listOf(
                File("/data/local/tmp/mnn_models/"),
                appModelsDir
            ).distinctBy { it.absolutePath }

            modelRoots.forEach { modelsDir ->
                if (modelsDir.exists() && modelsDir.isDirectory) {
                    modelsDir.listFiles()?.forEach { modelDir ->
                        if (modelDir.isDirectory) {
                            val detection = LocalModelFormatInspector.inspectDirectory(
                                modelDir.listFiles()?.map { it.name } ?: emptyList()
                            )
                            if (detection.format == LocalModelFormatInspector.Format.MNN &&
                                detection.state == LocalModelFormatInspector.State.READY_TO_IMPORT
                            ) {
                                val modelPath = modelDir.absolutePath
                                val modelId = "local/${modelPath}"
                                result.add(ModelItem.fromLocalModel(modelId, modelPath))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalModelsProvider", "Failed to load local models", e)
        }
'''
    contents["local_provider"] = replace_once(
        contents["local_provider"], old_provider_block, new_provider_block, "LocalModelsProvider scanning logic"
    )
    contents["local_provider"] = replace_once(
        contents["local_provider"],
        "import com.alibaba.mls.api.ModelItem\\n",
        "import com.alibaba.mls.api.ModelItem\\n"
        "import com.alibaba.mnnllm.android.modelist.LocalModelFormatInspector\\n",
        "LocalModelsProvider format inspector import",
    )

    fab_anchor = '''            <com.nambimobile.widgets.efab.FabOption
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                app:fab_orientation="portrait"
                app:label_text="@string/add_local_models"
                app:fab_icon="@drawable/ic_model"
                android:onClick="addLocalModels"/>
'''
    fab_addition = fab_anchor + '''            <com.nambimobile.widgets.efab.FabOption
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                app:fab_orientation="portrait"
                app:label_text="@string/add_local_model_from_files"
                app:fab_icon="@drawable/ic_u_folder"
                android:onClick="addLocalModelFromFiles"/>
            <com.nambimobile.widgets.efab.FabOption
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                app:fab_orientation="portrait"
                app:label_text="@string/add_local_model_file"
                app:fab_icon="@drawable/ic_model"
                android:onClick="addLocalModelFile"/>
'''
    contents["main_layout"] = replace_once(contents["main_layout"], fab_anchor, fab_addition, "main-page FAB option")

    zh_entries = '''    <string name="add_local_model_from_files">从文件管理器导入 MNN 文件夹</string>
    <string name="add_local_model_file">识别 GGUF、ZIP 等模型文件</string>
    <string name="local_model_import_title">导入本地模型</string>
    <string name="local_model_import_progress">正在复制模型文件，请保持应用运行…</string>
    <string name="local_model_import_success">本地模型已导入：%1$s</string>
    <string name="local_model_import_failed">导入失败：%1$s</string>
    <string name="local_model_invalid_folder">请选择根目录直接包含 config.json 的 MNN 模型文件夹。</string>
'''
    contents["strings_zh"] = replace_once(
        contents["strings_zh"], "</resources>", zh_entries + "</resources>", "Chinese import strings"
    )

    en_entries = '''    <string name="add_local_model_from_files">Import MNN Folder from File Manager</string>
    <string name="add_local_model_file">Inspect GGUF, ZIP and Model Files</string>
    <string name="local_model_import_title">Import Local Model</string>
    <string name="local_model_import_progress">Copying model files. Keep the app open…</string>
    <string name="local_model_import_success">Model imported: %1$s</string>
    <string name="local_model_import_failed">Import failed: %1$s</string>
    <string name="local_model_invalid_folder">Select an MNN model folder with config.json directly in its root.</string>
'''
    contents["strings_en"] = replace_once(
        contents["strings_en"], "</resources>", en_entries + "</resources>", "English import strings"
    )

    helper_source = Path(__file__).parent / "src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelFolderImporter.kt"
    if not helper_source.is_file():
        raise FileNotFoundError(f"Missing helper source in patch package: {helper_source}")
    helper_text = helper_source.read_text(encoding="utf-8")

    extra_sources = {
        Path(__file__).parent / "src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelFormatInspector.kt":
            Path("apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelFormatInspector.kt"),
        Path(__file__).parent / "src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelArchiveImporter.kt":
            Path("apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelArchiveImporter.kt"),
        Path(__file__).parent / "tests/LocalModelFormatInspectorTest.kt":
            Path("apps/Android/MnnLlmChat/app/src/test/java/com/alibaba/mnnllm/android/modelist/LocalModelFormatInspectorTest.kt"),
        Path(__file__).parent / "tests/LocalModelArchiveImporterTest.kt":
            Path("apps/Android/MnnLlmChat/app/src/test/java/com/alibaba/mnnllm/android/modelist/LocalModelArchiveImporterTest.kt"),
    }
    for source_path, target_path in extra_sources.items():
        if not source_path.is_file():
            raise FileNotFoundError(f"Missing extra patch source: {source_path}")
        if not target_path.parent.is_dir():
            raise FileNotFoundError(f"Missing target directory: {target_path.parent}")

    # Validate every target before writing any files, so an outdated checkout is left untouched.
    for key, path in FILES.items():
        if not path.parent.is_dir():
            raise FileNotFoundError(f"Missing source directory: {path.parent}")
    helper_path.parent.mkdir(parents=True, exist_ok=True)

    for key, path in FILES.items():
        path.write_text(contents[key], encoding="utf-8")
    helper_path.write_text(helper_text, encoding="utf-8")
    for source_path, target_path in extra_sources.items():
        target_path.write_text(source_path.read_text(encoding="utf-8"), encoding="utf-8")

    print("Patch applied successfully. Updated files:")
    for path in [*FILES.values(), helper_path]:
        print(f"  - {path}")
    print("The existing addLocalModels() ADB flow and /data/local/tmp/mnn_models path were retained.")


if __name__ == "__main__":
    main()
