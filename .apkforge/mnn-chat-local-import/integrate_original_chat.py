from pathlib import Path
import sys

app = Path(sys.argv[1])
java = app / "app/src/main/java/com/alibaba/mnnllm/android"

def replace_once(path: Path, old: str, new: str, label: str):
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

presenter = java / "chat/ChatPresenter.kt"
source = presenter.read_text(encoding="utf-8")
import_anchor = "import com.alibaba.mnnllm.android.llm.ChatSession\n"
if source.count(import_anchor) != 1:
    raise SystemExit("ChatPresenter: ChatSession import anchor not unique")
if "import com.alibaba.mnnllm.android.modelist.GgufChatSession" not in source:
    source = source.replace(
        import_anchor,
        import_anchor + "import com.alibaba.mnnllm.android.modelist.GgufChatSession\n",
        1
    )
if "import com.alibaba.mnnllm.android.modelist.GgufApiSessionRegistry" not in source:
    source = source.replace(
        import_anchor + "import com.alibaba.mnnllm.android.modelist.GgufChatSession\n",
        import_anchor
        + "import com.alibaba.mnnllm.android.modelist.GgufChatSession\n"
        + "import com.alibaba.mnnllm.android.modelist.GgufApiSessionRegistry\n",
        1
    )
old_session_branch = '''        if (ModelTypeUtils.isDiffusionModel(modelName) || ModelTypeUtils.isSanaModel(modelName)) {
            val chatService = ChatService.provide()
            chatSession = chatService.createSession(
                modelId, modelName, sessionId, chatDataItemList, configPath, false
            )
        } else {
            val result = ServiceLocator.getLlmRuntimeController().ensureSession(
                modelId = modelId,
                forceReload = false,
                useAppConfig = true,
                configPath = configPath,
                sessionId = sessionId,
                historyList = chatDataItemList,
                deferLoad = true
            )
            if (!result.success || result.session == null) {
                throw IllegalStateException(result.reason ?: "Failed to ensure LLM session")
            }
            chatSession = result.session!!
        }
'''
new_session_branch = '''        val ggufModelPath = intent.getStringExtra("ggufModelPath")
        if (!ggufModelPath.isNullOrBlank()) {
            chatSession = GgufChatSession(
                chatActivity.applicationContext,
                ggufModelPath,
                sessionId,
                chatDataItemList
            )
        } else if (ModelTypeUtils.isDiffusionModel(modelName) || ModelTypeUtils.isSanaModel(modelName)) {
            val chatService = ChatService.provide()
            chatSession = chatService.createSession(
                modelId, modelName, sessionId, chatDataItemList, configPath, false
            )
        } else {
            val result = ServiceLocator.getLlmRuntimeController().ensureSession(
                modelId = modelId,
                forceReload = false,
                useAppConfig = true,
                configPath = configPath,
                sessionId = sessionId,
                historyList = chatDataItemList,
                deferLoad = true
            )
            if (!result.success || result.session == null) {
                throw IllegalStateException(result.reason ?: "Failed to ensure LLM session")
            }
            chatSession = result.session!!
        }
'''
if source.count(old_session_branch) != 1:
    raise SystemExit("ChatPresenter: original session factory block changed")
source = source.replace(old_session_branch, new_session_branch, 1)
registry_anchor = "        sessionId = chatSession.sessionId\n"
if source.count(registry_anchor) != 1:
    raise SystemExit("ChatPresenter: session assignment anchor not unique")
source = source.replace(
    registry_anchor,
    registry_anchor
    + "        if (chatSession is GgufChatSession) {\n"
    + "            GgufApiSessionRegistry.register(modelId, chatSession)\n"
    + "        } else {\n"
    + "            GgufApiSessionRegistry.clear()\n"
    + "        }\n",
    1
)
presenter.write_text(source, encoding="utf-8")

activity = java / "chat/ChatActivity.kt"
source = activity.read_text(encoding="utf-8")
field_anchor = "    var isDiffusion = false\n    var chatSession: ChatSession? = null\n"
if source.count(field_anchor) != 1:
    raise SystemExit("ChatActivity: state field anchor not unique")
source = source.replace(
    field_anchor,
    field_anchor + "    private val isGgufSession: Boolean\n        get() = !intent.getStringExtra(\"ggufModelPath\").isNullOrBlank()\n",
    1
)
click_old = '''        binding.modelSwitcher.setOnClickListener {
            if (!isDiffusion) {
                showModelSelectionDialog()
            }
        }
'''
click_new = '''        binding.modelSwitcher.setOnClickListener {
            if (!isDiffusion && !isGgufSession) {
                showModelSelectionDialog()
            }
        }
'''
if source.count(click_old) != 1:
    raise SystemExit("ChatActivity: model switch click anchor not unique")
source = source.replace(click_old, click_new, 1)

source = source.replace(
    "        if (isDiffusion) {\n            binding.modelSwitcher.isClickable = false",
    "        if (isDiffusion || isGgufSession) {\n            binding.modelSwitcher.isClickable = false",
    1
)

thinking_old = '''            setOnThinkingModeChanged {isThinking ->
                Log.d(TAG, "isThinking: $isThinking")
                (chatSession as LlmSession).updateThinking(isThinking)
            }
'''
thinking_new = '''            setOnThinkingModeChanged {isThinking ->
                Log.d(TAG, "isThinking: $isThinking")
                (chatSession as? LlmSession)?.updateThinking(isThinking)
            }
'''
if source.count(thinking_old) != 1:
    raise SystemExit("ChatActivity: thinking callback anchor not unique")
source = source.replace(thinking_old, thinking_new, 1)

input_anchor = '''        setupChatListComponent()
        setupInputModule()
        binding.modelSwitcher.text = modelName
'''
input_new = '''        setupChatListComponent()
        setupInputModule()
        if (isGgufSession) {
            // Keep the original MNN chat surface, while hiding controls that need MNN-only
            // audio, vision, or mutable model-template backends.
            binding.btnToggleThinking.visibility = View.GONE
            binding.btnToggleAudioOutput.visibility = View.GONE
            binding.btSwitchAudio.visibility = View.GONE
            binding.btPlus.visibility = View.GONE
        }
        binding.modelSwitcher.text = modelName
'''
if source.count(input_anchor) != 1:
    raise SystemExit("ChatActivity: input module initialization anchor not unique")
source = source.replace(input_anchor, input_new, 1)

menu_old = '''        menu.findItem(R.id.menu_item_model_settings).isVisible = true
        menu.findItem(R.id.menu_item_benchmark_test).isVisible = benchmarkModule.enabled
        // Voice chat is only available for non-diffusion models
        menu.findItem(R.id.start_voice_chat).isVisible = !isDiffusion
'''
menu_new = '''        menu.findItem(R.id.menu_item_model_settings).isVisible = !isGgufSession
        menu.findItem(R.id.menu_item_benchmark_test).isVisible =
            benchmarkModule.enabled && !isGgufSession
        // GGUF currently has no trustworthy MNN-compatible prefill/decode token counters.
        menu.findItem(R.id.show_performance_metrics).isVisible = !isGgufSession
        // GGUF uses the app-level OpenAI API adapter; model-specific MNN config remains hidden.
        menu.findItem(R.id.menu_item_api_settings).isVisible = true
        menu.findItem(R.id.menu_item_api_console).isVisible = true
        menu.findItem(R.id.menu_item_config_info).isVisible = !isGgufSession
        // GGUF sessions are text-only and use llama.cpp, not the MNN audio/template backend.
        menu.findItem(R.id.start_voice_chat).isVisible = !isDiffusion && !isGgufSession
'''
if source.count(menu_old) != 1:
    raise SystemExit("ChatActivity: menu visibility block not unique")
source = source.replace(menu_old, menu_new, 1)
api_old = '''            if (isApiServiceEnabled(this)) {
                ApiServiceManager.startApiService(this, modelId)
            }
'''
api_new = '''            if (isApiServiceEnabled(this)) {
                ApiServiceManager.startApiService(this, modelId)
            }
'''
if source.count(api_old) != 1:
    raise SystemExit("ChatActivity: API service startup anchor not unique")
source = source.replace(api_old, api_new, 1)

benchmark_old = '''        recentItem.benchmarkInfo = ModelUtils.generateBenchMarkString(benchMarkResult)'''
benchmark_new = '''        // MNN-specific performance fields are not available from the current llama.cpp adapter.
        recentItem.benchmarkInfo = if (isGgufSession) null
            else ModelUtils.generateBenchMarkString(benchMarkResult)'''
if source.count(benchmark_old) != 1:
    raise SystemExit("ChatActivity: benchmark result anchor not unique")
source = source.replace(benchmark_old, benchmark_new, 1)

activity.write_text(source, encoding="utf-8")

print("GGUF ChatSession is now hosted by the original MNN ChatActivity.")
print("Original ActivityChatBinding, ChatListComponent, ChatInputComponent and database history are retained.")\nprint("OpenAI-compatible local API settings and server startup remain enabled for GGUF.")
