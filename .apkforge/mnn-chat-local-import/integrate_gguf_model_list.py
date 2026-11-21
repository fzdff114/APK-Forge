from pathlib import Path
import re
import sys

app = Path(sys.argv[1])
java = app / "app/src/main/java/com/alibaba/mnnllm/android"

def replace_once(path: Path, old: str, new: str, label: str):
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

# Add GGUF files to the same model list data used by MNN.
manager = java / "modelist/ModelListManager.kt"
cache_old = '''                val localItem = LocalModelsProvider.getLocalModels().find { it.modelId == modelId }
                if (localItem != null) {
                    if (!tags.isNullOrEmpty()) {
                        localItem.tags = tags
                    }
                    if (localItem.modelMarketItem == null && this.modelMarketItem != null) {
                         localItem.modelMarketItem = this.modelMarketItem
                    }
                    if (!this.modelName.isNullOrEmpty()) {
                        localItem.modelName = this.modelName
                    }
                }
                localItem
'''
cache_new = '''                val localItem = if (modelId.startsWith("local-gguf/")) {
                    val savedPath = modelPath?.takeIf { File(it).isFile }
                    savedPath?.let { path ->
                        ModelItem.fromLocalModel(modelId, path).apply {
                            modelName = this@ModelItemCacheDTO.modelName ?: File(path).parentFile?.name
                            this.tags = this@ModelItemCacheDTO.tags ?: emptyList()
                        }
                    }
                } else {
                    LocalModelsProvider.getLocalModels().find { it.modelId == modelId }
                }
                if (localItem != null) {
                    if (!tags.isNullOrEmpty()) localItem.tags = tags
                    if (localItem.modelMarketItem == null && this.modelMarketItem != null) {
                        localItem.modelMarketItem = this.modelMarketItem
                    }
                    if (!this.modelName.isNullOrEmpty()) localItem.modelName = this.modelName
                }
                localItem
'''
replace_once(manager, cache_old, cache_new, "GGUF model cache restoration")

load_anchor = '''            // Initialize ModelMarketCache and wait for it to be ready using Flow
'''
load_addition = '''             // Imported GGUF files appear in the same main list as local MNN models.
             val ggufRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "gguf_models")
             LocalGgufModelLibrary.scan(ggufRoot).forEach { entry ->
                 if (entry.isComplete && entry.primaryFile.isFile) {
                     val ggufId = "local-gguf/" + entry.folder.name
                     val ggufItem = ModelItem.fromLocalModel(ggufId, entry.primaryFile.absolutePath).apply {
                         modelName = entry.displayName
                         tags = emptyList()
                     }
                     modelWrappers.add(
                         ModelItemWrapper(
                             modelItem = ggufItem,
                             downloadedModelInfo = null,
                             downloadSize = entry.totalBytes,
                             isPinned = pinnedModels.contains(ggufId),
                             sourceTag = "GGUF"
                         )
                     )
                 }
             }

''' + load_anchor
replace_once(manager, load_anchor, load_addition, "GGUF model-list scan")

exclude_old = '''    private fun shouldExcludeFromPrimaryList(modelItem: ModelItem): Boolean {
        val modelId = modelItem.modelId ?: return false
        val tags = modelItem.getTags()
'''
exclude_new = '''    private fun shouldExcludeFromPrimaryList(modelItem: ModelItem): Boolean {
        val modelId = modelItem.modelId ?: return false
        if (modelId.startsWith("local-gguf/")) return false
        val tags = modelItem.getTags()
'''
replace_once(manager, exclude_old, exclude_new, "GGUF main-list visibility")

source_old = '''    private fun getSourceLabel(context: Context, modelId: String?, isLocal: Boolean): String? {
        if (modelId == null || modelId.isBlank() || modelId.startsWith("local/")) return null
        return when {
'''
source_new = '''    private fun getSourceLabel(context: Context, modelId: String?, isLocal: Boolean): String? {
        if (modelId == null || modelId.isBlank()) return null
        if (modelId.startsWith("local-gguf/")) return "GGUF"
        if (modelId.startsWith("local/")) return null
        return when {
'''
replace_once(manager, source_old, source_new, "GGUF source tag")

# Route a GGUF card into the original MNN ChatActivity.
router = java / "chat/ChatRouter.kt"
router_source = router.read_text(encoding="utf-8")
import_anchor = "import com.alibaba.mnnllm.android.model.ModelUtils\n"
if "import com.alibaba.mnnllm.android.modelist.ModelListManager\n" not in router_source:
    router_source = router_source.replace(import_anchor, import_anchor + "import com.alibaba.mnnllm.android.modelist.ModelListManager\n", 1)
run_anchor = '''    fun startRun(context: Context, modelIdParam: String, destModelDir:String?, sessionId: String?) {
        Log.d(TAG, "startRun modelIdParam: $modelIdParam destModelDir: $destModelDir sessionId: $sessionId")
'''
run_addition = '''    fun startRun(context: Context, modelIdParam: String, destModelDir:String?, sessionId: String?) {
        Log.d(TAG, "startRun modelIdParam: $modelIdParam destModelDir: $destModelDir sessionId: $sessionId")
        if (modelIdParam.startsWith("local-gguf/")) {
            val modelItem = ModelListManager.getModelIdModelMap()[modelIdParam]
            val modelPath = destModelDir ?: modelItem?.localPath
            val modelFile = modelPath?.let { File(it) }
            if (modelFile == null || !modelFile.isFile) {
                Toast.makeText(context, "GGUF 模型文件不存在或已被移动，请刷新模型列表。", Toast.LENGTH_LONG).show()
                return
            }
            CrashReportContext.setCurrentModel(modelIdParam, sessionId)
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra("chatSessionId", sessionId)
                putExtra("modelId", modelIdParam)
                putExtra("modelName", modelItem?.modelName ?: modelFile.nameWithoutExtension)
                putExtra("ggufModelPath", modelFile.absolutePath)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }
'''
replace_once(router, run_anchor, run_addition, "GGUF ChatActivity route")

# App-imported local MNN and GGUF models use the same long-press Delete action.
holder = java / "modelist/ModelItemHolder.kt"
holder_source = holder.read_text(encoding="utf-8")
imports = "import kotlinx.coroutines.MainScope\nimport kotlinx.coroutines.launch\n"
if imports not in holder_source:
    raise SystemExit("ModelItemHolder: coroutine imports changed")
holder_source = holder_source.replace(
    imports,
    "import kotlinx.coroutines.MainScope\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n",
    1
)
holder.write_text(holder_source, encoding="utf-8")
delete_old = '''                            // Use ModelDeletionHelper for proper cleanup of mmap cache and chat sessions
                            val result = ModelDeletionHelper.deleteModelWithCleanup(v.context, modelId!!)
                            if (!result.modelDeleted) {
                                Log.w(TAG, "Model deletion incomplete: ${result.errors.joinToString()}")
                            }
                            // Notify the listener that the model was deleted successfully
                            modelItemListener.onItemDeleted(modelItem)
'''
delete_new = '''                            if (LocalImportedModelDeleter.isManagedLocal(v.context, modelItem)) {
                                withContext(Dispatchers.IO) {
                                    LocalImportedModelDeleter.delete(v.context, modelItem)
                                }
                            } else {
                                val result = ModelDeletionHelper.deleteModelWithCleanup(v.context, modelId!!)
                                if (!result.modelDeleted) {
                                    throw IllegalStateException("Model deletion incomplete")
                                }
                            }
                            modelItemListener.onItemDeleted(modelItem)
'''
replace_once(holder, delete_old, delete_new, "Local model delete action")
deletable_old = '''        val isDownloaded = modelWrapper.downloadedModelInfo != null
        val isBuiltin = modelItem.isBuiltin || modelItem.modelId?.startsWith("Builtin/") == true
        val isDeletable = isDownloaded && !isBuiltin
'''
deletable_new = '''        val isDownloaded = modelWrapper.downloadedModelInfo != null
        val isManagedLocal = LocalImportedModelDeleter.isManagedLocal(v.context, modelItem)
        val isBuiltin = modelItem.isBuiltin || modelItem.modelId?.startsWith("Builtin/") == true
        val isDeletable = (isDownloaded || isManagedLocal) && !isBuiltin
'''
replace_once(holder, deletable_old, deletable_new, "Local model Delete menu visibility")

# After import, refresh the main MNN Local Models tab instead of opening a GGUF-only Activity.
main = java / "main/MainActivity.kt"
main_source = main.read_text(encoding="utf-8")
for line in [
    "import com.alibaba.mnnllm.android.modelist.GgufChatActivity\n",
    "import com.alibaba.mnnllm.android.modelist.GgufModelLibraryActivity\n",
]:
    main_source = main_source.replace(line, "")
if "import com.alibaba.mnnllm.android.modelist.ModelListManager\n" not in main_source:
    importer_anchor = "import com.alibaba.mnnllm.android.modelist.LocalGgufModelImporter\n"
    if importer_anchor not in main_source:
        raise SystemExit("MainActivity: cannot find GGUF importer import")
    main_source = main_source.replace(importer_anchor, importer_anchor + "import com.alibaba.mnnllm.android.modelist.ModelListManager\n", 1)

folder_start = main_source.index('                if (ggufModels.isNotEmpty()) {')
folder_end = main_source.index('                val imported = withContext(Dispatchers.IO) {', folder_start)
main_source = (
    main_source[:folder_start]
    + '''                if (ggufModels.isNotEmpty()) {
                    refreshImportedGgufModels(ggufModels.size)
                    return@launch
                }
'''
    + main_source[folder_end:]
)
file_start = main_source.index('                    startActivity(Intent(this@MainActivity, GgufChatActivity::class.java)')
file_end = main_source.index("                } catch (_: OperationCanceledException)", file_start)
main_source = main_source[:file_start] + '''                    refreshImportedGgufModels(1)''' + main_source[file_end:]

library_method = '''    fun openGgufModelLibrary(view: android.view.View?) {
        startActivity(Intent(this, GgufModelLibraryActivity::class.java))
    }

'''
main_source = main_source.replace(library_method, "")
refresh_method = '''    private suspend fun refreshImportedGgufModels(count: Int) {
        ModelListManager.clearModelCache()
        ModelListManager.notifyModelListMayChange(ModelListManager.ChangeReason.MANUAL_REFRESH)
        bottomNav.select(BottomTabBar.Tab.LOCAL_MODELS)
        val message = if (count == 1) "GGUF 模型已导入，并已加入本地模型列表。"
            else "已导入 $count 个 GGUF 模型，并已加入本地模型列表。"
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
    }

'''
run_anchor = "    fun runModel(destModelDir: String?, modelIdParam: String?, sessionId: String?) {"
if main_source.count(run_anchor) != 1:
    raise SystemExit("MainActivity: runModel anchor changed")
main.write_text(main_source.replace(run_anchor, refresh_method + run_anchor, 1), encoding="utf-8")

# Remove the separate GGUF-only screens and their manifest entries.
for activity_name in ["GgufChatActivity.kt", "GgufModelLibraryActivity.kt"]:
    activity_file = java / "modelist" / activity_name
    if activity_file.exists():
        activity_file.unlink()
manifest = app / "app/src/main/AndroidManifest.xml"
manifest_source = manifest.read_text(encoding="utf-8")
manifest_source = re.sub(
    r'\\s*<activity\\s+android:name="\\.modelist\\.GgufChatActivity".*?/>', "",
    manifest_source, flags=re.DOTALL
)
manifest_source = re.sub(
    r'\\s*<activity\\s+android:name="\\.modelist\\.GgufModelLibraryActivity".*?/>', "",
    manifest_source, flags=re.DOTALL
)
manifest.write_text(manifest_source, encoding="utf-8")

for path in [main, router, manager, holder]:
    source = path.read_text(encoding="utf-8")
    if "GgufModelLibraryActivity" in source or "GgufChatActivity" in source:
        raise SystemExit(f"Standalone GGUF page reference remains in {path}")

print("GGUF models now appear in the original MNN local-model list.")
print("GGUF cards open in the original ChatActivity.")
print("App-imported local MNN and GGUF models expose the MNN-style long-press Delete action.")
print("Standalone GGUF pages and their manifest entries are removed.")
