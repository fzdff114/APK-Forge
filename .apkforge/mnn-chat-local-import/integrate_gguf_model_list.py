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

# Merge imported GGUF files into the original MNN model list.
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
                            modelName = this@ModelItemCacheDTO.modelName
                                ?: File(path).parentFile?.name
                            this.tags = this@ModelItemCacheDTO.tags ?: emptyList()
                        }
                    }
                } else {
                    LocalModelsProvider.getLocalModels().find { it.modelId == modelId }
                }
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
replace_once(manager, cache_old, cache_new, "GGUF model cache restoration")

load_anchor = '''             // Initialize ModelMarketCache and wait for it to be ready using Flow
'''
load_addition = '''             // Imported GGUF files are local models too. Add them to the same main list,
             // but keep their GGUF source tag and native file path separate from MNN downloads.
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
        // GGUF entries are user-imported language models, not MNN voice-model assets.
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

# Open a GGUF model in the existing ChatActivity from its model card.
router = java / "chat/ChatRouter.kt"
source = router.read_text(encoding="utf-8")
import_anchor = "import com.alibaba.mnnllm.android.model.ModelUtils\n"
if "import com.alibaba.mnnllm.android.modelist.ModelListManager\n" not in source:
    replace_once(
        router,
        import_anchor,
        import_anchor + "import com.alibaba.mnnllm.android.modelist.ModelListManager\n",
        "ChatRouter model-list import"
    )
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

# Use the selected GGUF primary-file path when a model card is tapped.
fragment = java / "modelist/ModelListFragment.kt"
anchor = '''        val modelMarketItem = (modelItem?.modelMarketItem as? com.alibaba.mnnllm.android.modelmarket.ModelMarketItem)

        if (modelMarketItem != null && modelMarketItem.sizeB > 10.0) {
'''
new = '''        val modelMarketItem = (modelItem?.modelMarketItem as? com.alibaba.mnnllm.android.modelmarket.ModelMarketItem)
        val launchPath = if (modelId?.startsWith("local-gguf/") == true) {
            modelItem?.localPath
        } else {
            destPath
        }

        if (modelMarketItem != null && modelMarketItem.sizeB > 10.0) {
'''
replace_once(fragment, anchor, new, "GGUF model-list launch path")
source = fragment.read_text(encoding="utf-8")
old_call = "ChatRouter.startRun(requireContext(), modelId!!, destPath, null)"
if source.count(old_call) != 2:
    raise SystemExit("GGUF model-list launch: expected both ChatRouter branches")
fragment.write_text(source.replace(old_call, "ChatRouter.startRun(requireContext(), modelId!!, launchPath, null)"), encoding="utf-8")

# Make GGUF deletable through the same long-press menu and confirmation as MNN models.
holder = java / "modelist/ModelItemHolder.kt"
source = holder.read_text(encoding="utf-8")
imports = [
    ("import android.view.MenuItem\n", "import android.content.Context\nimport android.view.MenuItem\n"),
    ("import com.alibaba.mnnllm.android.R\n", "import com.alibaba.mnnllm.android.R\nimport com.alibaba.mnnllm.android.chat.model.ChatDataManager\nimport com.alibaba.mnnllm.android.history.HistoryUtils\n"),
    ("import kotlinx.coroutines.MainScope\n", "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.MainScope\nimport kotlinx.coroutines.withContext\n"),
    ("import java.text.SimpleDateFormat\n", "import java.io.File\nimport java.text.SimpleDateFormat\n"),
]
for old, new in imports:
    if new not in source:
        if old not in source:
            raise SystemExit(f"ModelItemHolder: import anchor missing: {old!r}")
        source = source.replace(old, new, 1)
holder.write_text(source, encoding="utf-8")

delete_old = '''                            // Use ModelDeletionHelper for proper cleanup of mmap cache and chat sessions
                            val result = ModelDeletionHelper.deleteModelWithCleanup(v.context, modelId!!)
                            if (!result.modelDeleted) {
                                Log.w(TAG, "Model deletion incomplete: ''' + chr(36) + '''{result.errors.joinToString()}")
                            }
                            // Notify the listener that the model was deleted successfully
                            modelItemListener.onItemDeleted(modelItem)
'''
delete_new = '''                            if (modelId?.startsWith("local-gguf/") == true) {
                                withContext(Dispatchers.IO) {
                                    deleteGgufModel(v.context, modelItem)
                                }
                            } else {
                                // Keep MNN's own cleanup path for downloaded MNN models.
                                val result = ModelDeletionHelper.deleteModelWithCleanup(v.context, modelId!!)
                                if (!result.modelDeleted) {
                                    Log.w(TAG, "Model deletion incomplete")
                                }
                            }
                            modelItemListener.onItemDeleted(modelItem)
'''
replace_once(holder, delete_old, delete_new, "GGUF delete action")

deletable_old = '''        val isDownloaded = modelWrapper.downloadedModelInfo != null
        val isBuiltin = modelItem.isBuiltin || modelItem.modelId?.startsWith("Builtin/") == true
        val isDeletable = isDownloaded && !isBuiltin
'''
deletable_new = '''        val isDownloaded = modelWrapper.downloadedModelInfo != null
        val isBuiltin = modelItem.isBuiltin || modelItem.modelId?.startsWith("Builtin/") == true
        val isGguf = modelItem.modelId?.startsWith("local-gguf/") == true
        val isDeletable = isGguf || (isDownloaded && !isBuiltin)
'''
replace_once(holder, deletable_old, deletable_new, "GGUF delete visibility")
replace_once(
    holder,
    'popupMenu.menu.findItem(R.id.menu_settings).setVisible(true)',
    'popupMenu.menu.findItem(R.id.menu_settings).setVisible(!isGguf)',
    "GGUF settings visibility"
)

method_anchor = '''    companion object {
        const val TAG = "ModelItemHolder"
'''
method = '''    private fun deleteGgufModel(context: Context, modelItem: ModelItem) {
        val modelId = modelItem.modelId ?: throw IllegalStateException("GGUF 模型 ID 丢失。")
        val modelPath = modelItem.localPath ?: throw IllegalStateException("GGUF 模型路径丢失。")
        val primaryFile = File(modelPath)
        val folder = primaryFile.parentFile ?: throw IllegalStateException("GGUF 模型目录无效。")
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "gguf_models")
        val entry = LocalGgufModelLibrary.scan(root).firstOrNull {
            it.folder.canonicalFile == folder.canonicalFile
        } ?: throw IllegalStateException("模型已不在本地模型库中，请刷新列表后重试。")

        val chatDataManager = ChatDataManager.getInstance(context)
        chatDataManager.getSessionsForModel(modelId).forEach { session ->
            HistoryUtils.deleteHistory(context, chatDataManager, session.sessionId)
        }
        if (!GgufChatHistoryStore.clear(GgufChatHistoryStore.fileFor(context.filesDir, entry.primaryFile))) {
            throw IllegalStateException("聊天记录清理失败，模型文件未删除。")
        }
        LocalGgufModelLibrary.delete(root, entry.folder)
    }

''' + method_anchor
replace_once(holder, method_anchor, method, "GGUF model delete helper")

# Keep the original MNN chat screen but hide MNN-only runtime actions for GGUF.
chat_activity = java / "chat/ChatActivity.kt"
source = chat_activity.read_text(encoding="utf-8")
if "isGgufSession" not in source:
    raise SystemExit("ChatActivity: GGUF session marker was not integrated")
source = source.replace("private val isGgufSession: Boolean", "internal val isGgufSession: Boolean", 1)
source = source.replace(
    "        initializeVoiceModelsChecker()",
    "        if (!isGgufSession) initializeVoiceModelsChecker()",
    1
)
source = source.replace(
    "        AudioPlayService.instance!!.destroy()",
    "        if (!isGgufSession) AudioPlayService.instance!!.destroy()",
    1
)
source = source.replace(
    "isAudioModel = ModelTypeUtils.isAudioModel(modelId)",
    "isAudioModel = !isGgufSession && ModelTypeUtils.isAudioModel(modelId)"
)
menu_old = '''        menu.findItem(R.id.menu_item_model_settings).isVisible = !isGgufSession
        menu.findItem(R.id.menu_item_benchmark_test).isVisible =
            benchmarkModule.enabled && !isGgufSession
        // GGUF sessions are text-only and use llama.cpp, not the MNN audio/template backend.
        menu.findItem(R.id.start_voice_chat).isVisible = !isDiffusion && !isGgufSession
'''
menu_new = '''        menu.findItem(R.id.menu_item_model_settings).isVisible = !isGgufSession
        menu.findItem(R.id.menu_item_benchmark_test).isVisible =
            benchmarkModule.enabled && !isGgufSession
        menu.findItem(R.id.menu_item_config_info).isVisible = !isGgufSession
        menu.findItem(R.id.menu_item_api_settings).isVisible = !isGgufSession
        menu.findItem(R.id.menu_item_api_console).isVisible = !isGgufSession
        // GGUF sessions are text-only and use llama.cpp, not the MNN audio/template backend.
        menu.findItem(R.id.start_voice_chat).isVisible = !isDiffusion && !isGgufSession
'''
if source.count(menu_old) != 1:
    raise SystemExit("GGUF chat menu visibility: anchor not unique")
source = source.replace(menu_old, menu_new, 1)
chat_activity.write_text(source, encoding="utf-8")

# Prevent the input component from preparing MNN multimodal/audio handlers for GGUF.
chat_input = java / "chat/input/ChatInputComponent.kt"
source = chat_input.read_text(encoding="utf-8")
source = source.replace(
    "if (ModelTypeUtils.supportAudioOutput(currentModelName)) {",
    "if (!chatActivity.isGgufSession && ModelTypeUtils.supportAudioOutput(currentModelName)) {",
    1
)
source = source.replace(
    "if (!ModelTypeUtils.isVisualModel(currentModelId) && !ModelTypeUtils.isAudioModel(currentModelId)) {",
    "if (chatActivity.isGgufSession || (!ModelTypeUtils.isVisualModel(currentModelId) && !ModelTypeUtils.isAudioModel(currentModelId))) {",
    1
)
source = source.replace(
    "if (!loading && ModelTypeUtils.isAudioModel(currentModelId)) {",
    "if (!loading && !chatActivity.isGgufSession && ModelTypeUtils.isAudioModel(currentModelId)) {",
    1
)
if "chatActivity.isGgufSession ||" not in source:
    raise SystemExit("ChatInputComponent GGUF capability guard was not applied")
chat_input.write_text(source, encoding="utf-8")

# Imported GGUF files return to the original model list instead of opening another Activity.
main = java / "main/MainActivity.kt"
source = main.read_text(encoding="utf-8")
for line in [
    "import com.alibaba.mnnllm.android.modelist.GgufChatActivity\n",
    "import com.alibaba.mnnllm.android.modelist.GgufModelLibraryActivity\n",
]:
    source = source.replace(line, "")
if "import com.alibaba.mnnllm.android.modelist.ModelListManager\n" not in source:
    anchor = "import com.alibaba.mnnllm.android.modelist.LocalGgufModelImporter\n"
    if anchor not in source:
        raise SystemExit("MainActivity: cannot locate GGUF importer import")
    source = source.replace(anchor, anchor + "import com.alibaba.mnnllm.android.modelist.ModelListManager\n", 1)

folder_launch_old = '''                if (ggufModels.isNotEmpty()) {
                    if (ggufModels.size == 1) {
                        startActivity(Intent(this@MainActivity, GgufChatActivity::class.java)
                            .putExtra(GgufChatActivity.EXTRA_MODEL_PATH, ggufModels.single().absolutePath))
                    } else {
                        Toast.makeText(this@MainActivity,
                            "已导入 ''' + chr(36) + '''{ggufModels.size} 个 GGUF 模型，已加入模型库。", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this@MainActivity, GgufModelLibraryActivity::class.java))
                    }
                    return@launch
                }
'''
folder_launch_new = '''                if (ggufModels.isNotEmpty()) {
                    refreshImportedGgufModels(ggufModels.size)
                    return@launch
                }
'''
replace_once(main, folder_launch_old, folder_launch_new, "MainActivity folder import routing")

file_launch_old = '''                    startActivity(Intent(this@MainActivity, GgufChatActivity::class.java)
                        .putExtra(GgufChatActivity.EXTRA_MODEL_PATH, model.absolutePath))
'''
file_launch_new = '''                    refreshImportedGgufModels(1)
'''
replace_once(main, file_launch_old, file_launch_new, "MainActivity single-file import routing")

library_method = '''    fun openGgufModelLibrary(view: android.view.View?) {
        startActivity(Intent(this, GgufModelLibraryActivity::class.java))
    }

'''
source = source.replace(library_method, "")

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
if source.count(run_anchor) != 1:
    raise SystemExit("MainActivity: runModel insertion anchor not unique")
source = source.replace(run_anchor, refresh_method + run_anchor, 1)
main.write_text(source, encoding="utf-8")

# Remove the independent library/chat pages from the app package and manifest.
for activity_name in ["GgufChatActivity.kt", "GgufModelLibraryActivity.kt"]:
    candidate = java / "modelist" / activity_name
    if candidate.exists():
        candidate.unlink()

manifest = app / "app/src/main/AndroidManifest.xml"
value = manifest.read_text(encoding="utf-8")
value = re.sub(
    r'\s*<activity\s+android:name="\.modelist\.GgufChatActivity".*?/>',
    '',
    value,
    flags=re.DOTALL
)
value = re.sub(
    r'\s*<activity\s+android:name="\.modelist\.GgufModelLibraryActivity".*?/>',
    '',
    value,
    flags=re.DOTALL
)
manifest.write_text(value, encoding="utf-8")

for path in [main, router, manager, fragment, holder, chat_activity, chat_input]:
    text = path.read_text(encoding="utf-8")
    if "GgufModelLibraryActivity" in text or "GgufChatActivity" in text:
        raise SystemExit(f"Standalone GGUF activity reference remains in {path}")

print("Integrated GGUF model cards into the original MNN local-model list.")
print("GGUF cards carry a GGUF source tag, open in the original ChatActivity, and are deletable from its MNN-style long-press menu.")
print("Standalone GGUF model-library and chat activities were removed from the APK.")
