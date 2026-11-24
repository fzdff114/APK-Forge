from pathlib import Path
import sys

app = Path(sys.argv[1])
java = app / "app/src/main/java/com/alibaba/mnnllm"

def replace_once(path: Path, old: str, new: str, label: str):
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

# Add OpenAI max token fields accepted by standard local API clients.
request_models = app / "app/src/main/java/com/alibaba/mnnllm/api/openai/network/models/OpenAIChatRequestModels.kt"
replace_once(
    request_models,
    "    val presence_penalty: Double? = null,\n)",
    "    val presence_penalty: Double? = null,\n"
    "    val max_tokens: Int? = null,\n"
    "    val max_completion_tokens: Int? = null,\n)",
    "OpenAI request max token fields"
)

# Preserve the requested GGUF model id in OpenAI completion payloads.
formatter = java / "api/openai/network/utils/ChatResponseFormatter.kt"
replace_once(
    formatter,
    "        usage: Usage? = null\n    ): String {",
    "        usage: Usage? = null,\n"
    "        model: String = \"mnn-local\"\n"
    "    ): String {",
    "stream response formatter model parameter"
)
replace_once(
    formatter,
    "        usage: Usage\n    ): String {",
    "        usage: Usage,\n"
    "        model: String = \"mnn-local\"\n"
    "    ): String {",
    "non-stream response formatter model parameter"
)
formatter_text = formatter.read_text(encoding="utf-8")
if formatter_text.count('model = "mnn-local",') != 2:
    raise SystemExit("Expected two formatter model values")
formatter.write_text(
    formatter_text.replace('model = "mnn-local",', "model = model,", 2),
    encoding="utf-8"
)

# Route GGUF model IDs to the text-only llama.cpp session adapter.
routes = java / "api/openai/network/routes/ChatRoutes.kt"
route_import = "import com.alibaba.mnnllm.api.openai.network.services.MNNChatService\n"
source = routes.read_text(encoding="utf-8")
for line in [
    "import com.alibaba.mnnllm.android.modelist.GgufApiSessionRegistry\n",
    "import com.alibaba.mnnllm.api.openai.network.services.GgufApiChatService\n",
    "import io.ktor.http.HttpStatusCode\n",
    "import io.ktor.server.response.respond\n",
]:
    if line.strip() not in source:
        if route_import not in source:
            raise SystemExit("ChatRoutes import insertion anchor missing")
        source = source.replace(route_import, route_import + line, 1)

route_old = "        MNNChatService.processChatCompletion(call, chatRequest, traceId)"
route_new = '''        val requestedModel = chatRequest.model?.trim()
        if (requestedModel?.startsWith("local-gguf/") == true) {
            val ggufSession = GgufApiSessionRegistry.getActiveSession(requestedModel)
            if (ggufSession == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to mapOf(
                        "message" to "所请求的 GGUF 模型当前未处于可用会话中，请先在 MNN Chat 中打开该模型。"
                    ))
                )
            } else {
                GgufApiChatService().processChatCompletion(
                    call, chatRequest, traceId, requestedModel, ggufSession
                )
            }
        } else {
            MNNChatService.processChatCompletion(call, chatRequest, traceId)
        }'''
replace_once(routes, route_old, route_new, "GGUF OpenAI chat route")

# The model-list endpoint advertises only a GGUF model that has an active registered session.
models = java / "api/openai/network/services/MNNModelsService.kt"
model_import = "import com.alibaba.mnnllm.api.openai.manager.CurrentModelManager\n"
source = models.read_text(encoding="utf-8")
registry_import = "import com.alibaba.mnnllm.android.modelist.GgufApiSessionRegistry\n"
if registry_import.strip() not in source:
    if model_import not in source:
        raise SystemExit("MNNModelsService import insertion anchor missing")
    source = source.replace(model_import, model_import + registry_import, 1)
models.write_text(source, encoding="utf-8")
replace_once(
    models,
    "            val currentModelId = CurrentModelManager.getCurrentModelId()",
    "            val currentModelId = GgufApiSessionRegistry.currentModelId()\n"
    "                ?: CurrentModelManager.getCurrentModelId()",
    "GGUF active model advertisement"
)

print("GGUF OpenAI API route added for /v1/chat/completions.")
print("The /v1/models endpoint now reports the active GGUF model id.")
print("Text streaming, non-streaming and max_tokens/max_completion_tokens are wired.")
print("Multimodal requests are rejected explicitly instead of being silently dropped.")
