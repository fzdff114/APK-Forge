from pathlib import Path
import re
import sys

app = Path(sys.argv[1])
java = app / "app/src/main/java/com/alibaba/mnnllm/android"

def block(source, start, end, replacement, label):
    a = source.find(start)
    b = source.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: source anchor missing")
    # When the replacement is the exact end anchor, the source suffix already
    # contains that anchor. Do not append it twice (duplicate companion objects
    # caused unbalanced Kotlin braces in the previous build).
    if replacement.strip() == end.strip():
        return source[:a] + source[b:]
    return source[:a] + replacement + source[b:]

def sub_once(source, pattern, replacement, label, flags=re.DOTALL):
    changed, count = re.subn(pattern, replacement, source, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 block, found {count}")
    return changed

def remove_lines(source, needle):
    return "\n".join(line for line in source.split("\n") if needle not in line) + "\n"

# Firebase plugin and SDK removal (not just a user-consent toggle).
root_gradle = app / "build.gradle"
root = root_gradle.read_text(encoding="utf-8")
root = remove_lines(root, "com.google.gms.google-services")
root = remove_lines(root, "com.google.firebase.crashlytics")
root_gradle.write_text(root, encoding="utf-8")

app_gradle = app / "app/build.gradle"
gradle = app_gradle.read_text(encoding="utf-8")
gradle, enable_count = re.subn(
    r"^def enableFirebase = .*$",
    "def enableFirebase = false",
    gradle,
    count=1,
    flags=re.MULTILINE
)
if enable_count != 1:
    raise SystemExit("Firebase build switch definition not found")

# Remove only the Firebase conditional plugin block. Preserve Markwon, compiler,
# Android SDK, and other unrelated Gradle variables that follow it.
gradle, firebase_block_count = re.subn(
    r"(?ms)^if \(enableFirebase && hasGoogleServicesConfig\) \{\n.*?^\}\n",
    "",
    gradle,
    count=1
)
if firebase_block_count != 1:
    raise SystemExit("Firebase conditional plugin block not found")
for needle in [
    "apply plugin: 'com.google.gms.google-services'",
    "apply plugin: 'com.google.firebase.crashlytics'",
    "firebase-bom",
    "com.google.firebase:firebase-analytics",
    "com.google.firebase:firebase-crashlytics",
]:
    gradle = remove_lines(gradle, needle)
gradle = gradle.replace(
    'buildConfigField "boolean", "ENABLE_FIREBASE", enableFirebase ? "true" : "false"',
    'buildConfigField "boolean", "ENABLE_FIREBASE", "false"'
)
if "com.google.firebase" in gradle or "com.google.gms.google-services" in gradle:
    raise SystemExit("Firebase SDK reference remains in app/build.gradle")
app_gradle.write_text(gradle, encoding="utf-8")

# Keep API symbols for old call sites but make every crash context operation inert.
context_path = java / "utils/CrashReportContext.kt"
context = context_path.read_text(encoding="utf-8")
context = context.replace("import com.google.firebase.crashlytics.FirebaseCrashlytics\n", "")
context = context.replace("import kotlin.concurrent.Volatile\n", "")
context = re.sub(r"\s*@Volatile\s*\n\s*private var lastKnownActivityName: String = \"none\"\s*\n", "\n", context, count=1)
context = block(
    context,
    "    fun setCurrentActivity(activityName: String?) {",
    "    internal fun resolveCurrentActivityForCrashReport",
    """    // All reporting APIs are intentionally inert in this build.
    fun setCurrentActivity(activityName: String?) {}

    fun setAppInForeground(inForeground: Boolean) {}

    fun setCurrentModel(modelId: String?, sessionId: String?) {}

    fun setBenchmarkState(state: String?) {}

    fun setBenchmarkContext(
        modelId: String?,
        backend: String?,
        nPrompt: Int,
        nGenerate: Int,
        nRepeat: Int,
        kvCache: String?
    ) {}

    @JvmStatic
    fun reportLlmSetConfig(stage: String?, config: String?) {}

""",
    "CrashReportContext reporting methods"
)
context = re.sub(
    r"\n    private inline fun updateSafely\(block: FirebaseCrashlytics\.\(\) -> Unit\) \{.*?\n    \}\n(?=\})",
    "\n", context, count=1, flags=re.DOTALL
)
if "FirebaseCrashlytics" in context or "updateSafely" in context:
    raise SystemExit("CrashReportContext still references Firebase")
context_path.write_text(context, encoding="utf-8")

# Replace analytics calls with a no-op compatibility shell.
(java / "utils/AnalyticsTracker.kt").write_text("""package com.alibaba.mnnllm.android.utils

import android.content.Context

/** Analytics are disabled in this build; no events are queued or transmitted. */
object AnalyticsTracker {
    fun logAppOpen(context: Context) {}
    fun logPageView(context: Context, pageName: String) {}
}
""", encoding="utf-8")

# Remove the custom Java exception handler and native crash-log writer from the app.
application_path = java / "MnnLlmApplication.kt"
application = application_path.read_text(encoding="utf-8")
for line in [
    "import com.alibaba.mnnllm.android.utils.CrashUtil\n",
    "import com.alibaba.mnnllm.android.utils.CurrentActivityTracker\n",
    "import com.alibaba.mnnllm.android.privacy.PrivacyPolicyManager\n",
    "import com.google.firebase.crashlytics.FirebaseCrashlytics\n",
    "import timber.log.Timber\n",
]:
    application = application.replace(line, "")
application = application.replace("        CrashUtil.init(this)\n", "")
application = application.replace("        CurrentActivityTracker.initialize(this)\n", "")
application = application.replace("        applyCrashReportingConsent()\n", "")
if "    fun applyCrashReportingConsent() {" in application:
    application = block(
        application, "    fun applyCrashReportingConsent() {",
        "    companion object {", "",
        "MnnLlmApplication consent updater"
    )
application_path.write_text(application, encoding="utf-8")
crash_util = java / "utils/CrashUtil.kt"
if crash_util.exists():
    crash_util.unlink()
cmake = app / "app/src/main/cpp/CMakeLists.txt"
cmake.write_text(remove_lines(cmake.read_text(encoding="utf-8"), "crash_util.cpp"), encoding="utf-8")

paths_xml = app / "app/src/main/res/xml/file_paths.xml"
if paths_xml.exists():
    content = re.sub(
        r'\s*<files-path\s+name="crash"\s+path="crash/"\s*/>',
        "", paths_xml.read_text(encoding="utf-8")
    )
    paths_xml.write_text(content, encoding="utf-8")

manifest = app / "app/src/main/AndroidManifest.xml"
manifest_text = manifest.read_text(encoding="utf-8")
manifest_text = re.sub(
    r'\s*<meta-data\s+android:name="firebase_crashlytics_collection_enabled"\s+android:value="false"\s*/>',
    "", manifest_text, flags=re.DOTALL
)
if "firebase_crashlytics" in manifest_text.lower():
    raise SystemExit("Crashlytics manifest entry remains")
manifest.write_text(manifest_text, encoding="utf-8")

# Remove debug buttons and handlers that intentionally send test crash events.
debug_path = java / "debug/DebugActivity.kt"
debug = debug_path.read_text(encoding="utf-8")
debug = debug.replace("import com.google.firebase.crashlytics.FirebaseCrashlytics\n", "")
for line in [
    "    private lateinit var homeCrashlyticsNonFatalButton: Button\n",
    "    private lateinit var homeCrashlyticsFatalButton: Button\n",
    "    private var testCrashlyticsNonFatalButton: Button? = null\n",
    "    private var testCrashlyticsFatalButton: Button? = null\n",
    "        homeCrashlyticsNonFatalButton = findViewById(R.id.homeCrashlyticsNonFatalButton)\n",
    "        homeCrashlyticsFatalButton = findViewById(R.id.homeCrashlyticsFatalButton)\n",
    "        testCrashlyticsNonFatalButton = parentView.findViewById(R.id.testCrashlyticsNonFatalButton)\n",
    "        testCrashlyticsFatalButton = parentView.findViewById(R.id.testCrashlyticsFatalButton)\n",
]:
    debug = debug.replace(line, "")
debug = sub_once(
    debug,
    r"\n        testCrashlyticsNonFatalButton\?\.setOnClickListener \{.*?\n        \}\n\n        testCrashlyticsFatalButton\?\.setOnClickListener \{.*?\n        \}\n",
    "", "DebugActivity settings crash-test listeners"
)
debug = sub_once(
    debug,
    r"\n        homeCrashlyticsNonFatalButton\.setOnClickListener \{.*?\n        \}\n        homeCrashlyticsFatalButton\.setOnClickListener \{.*?\n        \}\n",
    "", "DebugActivity home crash-test listeners"
)
debug = block(
    debug, "    private fun testCrashlyticsNonFatal() {",
    "    private fun startTtsTest() {", "",
    "DebugActivity crash-test functions"
)
if "FirebaseCrashlytics" in debug or "Crashlytics" in debug:
    raise SystemExit("Crashlytics code remains in DebugActivity")
debug_path.write_text(debug, encoding="utf-8")

for relative, ids in [
    ("app/src/main/res/layout/activity_debug.xml", ["homeCrashlyticsNonFatalButton", "homeCrashlyticsFatalButton"]),
    ("app/src/main/res/layout/debug_test_settings.xml", ["testCrashlyticsNonFatalButton", "testCrashlyticsFatalButton"]),
]:
    path = app / relative
    xml = path.read_text(encoding="utf-8")
    for view_id in ids:
        xml = sub_once(
            xml,
            rf'\s*<Button\b(?=[^>]*android:id="@\+id/{view_id}")[^>]*/>',
            "", f"{relative} {view_id}"
        )
    path.write_text(xml, encoding="utf-8")

# Remove crash-log sharing and the manual GitHub issue-submission entry points.
main_path = java / "main/MainActivity.kt"
main = main_path.read_text(encoding="utf-8")
for line in [
    "import com.alibaba.mnnllm.android.utils.AnalyticsTracker\n",
    "import com.alibaba.mnnllm.android.utils.CrashUtil\n",
    "import com.alibaba.mnnllm.android.privacy.CrashConsentGate\n",
    "import com.alibaba.mnnllm.android.privacy.PrivacyPolicyManager\n",
    "import com.alibaba.mnnllm.android.privacy.PrivacyPolicyDialogFragment\n",
]:
    main = main.replace(line, "")
main = re.sub(
    r"^\s*val reportCrashMenu = menu\.findItem\(R\.id\.action_report_crash\)\n\s*reportCrashMenu\.isVisible = CrashUtil\.hasCrash\(\)\n",
    "", main, flags=re.MULTILINE
)
main = sub_once(
    main,
    r"\n        val issueMenu = menu\.findItem\(R\.id\.action_github_issue\).*?\n        \}\n(?=\s*val settingsMenu)",
    "", "MainActivity issue menu handler"
)
main = sub_once(
    main,
    r"\n        val reportCrashMenu = menu\.findItem\(R\.id\.action_report_crash\).*?\n        \}\n(?=\s*val filterDownloadedItem)",
    "", "MainActivity crash-share handler"
)
main = re.sub(r"^\s*AnalyticsTracker\.log(?:PageView|AppOpen)\(.*\)\s*\n", "", main, flags=re.MULTILINE)
main = main.replace("        checkPrivacyPolicyAgreement()\n", "")
main = sub_once(
    main, r"\n    fun onReportIssue\(view: View\?\) \{.*?\n    \}\n",
    "", "MainActivity issue callback"
)
main = block(
    main, "    private fun checkPrivacyPolicyAgreement() {",
    "    companion object {", "",
    "MainActivity crash-consent dialog"
)
main_path.write_text(main, encoding="utf-8")

menu = app / "app/src/main/res/menu/menu_main.xml"
menu_xml = menu.read_text(encoding="utf-8")
for item_id in ["action_github_issue", "action_report_crash"]:
    menu_xml = sub_once(
        menu_xml, rf'\s*<item\b(?=[^>]*android:id="@\+id/{item_id}")[^>]*/>',
        "", f"menu item {item_id}"
    )
menu.write_text(menu_xml, encoding="utf-8")

activity_main = app / "app/src/main/res/layout/activity_main.xml"
layout = activity_main.read_text(encoding="utf-8")
layout = sub_once(
    layout,
    r'\s*<com\.nambimobile\.widgets\.efab\.FabOption\b(?:(?!/>).)*android:onClick="onReportIssue"(?:(?!/>).)*/>',
    "", "issue-report floating button"
)
activity_main.write_text(layout, encoding="utf-8")

github_path = java / "utils/GithubUtils.kt"
github = github_path.read_text(encoding="utf-8")
github, count = re.subn(
    r"    fun reportIssue\(context: Context\) \{.*?\n    \}",
    "    // Developer issue submission is disabled in this build.\n    fun reportIssue(context: Context) {}",
    github, count=1, flags=re.DOTALL
)
if count != 1:
    raise SystemExit("GithubUtils reportIssue method not found")
github_path.write_text(github, encoding="utf-8")

chat_path = java / "chat/chatlist/ChatViewHolders.kt"
chat = chat_path.read_text(encoding="utf-8")
chat = sub_once(
    chat,
    r"\n            reportIssueButton\.setOnClickListener \{.*?\n            \}\n(?=\s*toggleBenchmarkButton)",
    "", "ChatViewHolders issue-report action"
)
chat = chat.replace(
    "            // Setup action buttons\n",
    "            // Crash/issue export is disabled; keep its view hidden.\n            reportIssueButton.visibility = View.GONE\n            // Setup remaining local action buttons\n",
    1
)
chat_path.write_text(chat, encoding="utf-8")

popup_path = java / "widgets/PopupWindowHelper.kt"
popup = popup_path.read_text(encoding="utf-8")
old = """            val reportIssueItem = popupView.findViewById<TextView>(R.id.assistant_text_report)

            wireAction(popupView.findViewById(R.id.assistant_text_copy_row), copyItem, onClickListener)"""
new = """            val reportIssueItem = popupView.findViewById<TextView>(R.id.assistant_text_report)
            popupView.findViewById<View>(R.id.assistant_text_report_row).visibility = View.GONE
            popupView.findViewById<View>(R.id.assistant_text_report_icon).visibility = View.GONE
            reportIssueItem.visibility = View.GONE

            wireAction(popupView.findViewById(R.id.assistant_text_copy_row), copyItem, onClickListener)"""
if popup.count(old) != 1:
    raise SystemExit("PopupWindowHelper report-menu anchor missing")
popup_path.write_text(popup.replace(old, new, 1), encoding="utf-8")

# Remove call sites in the model-session native implementation as well.
session_cpp = app / "app/src/main/cpp/llm_session.cpp"
session_source = session_cpp.read_text(encoding="utf-8")
session_source, declaration_count = re.subn(
    r"^void ReportLlmSetConfigToFirebase\(const std::string& stage, const std::string& config_json\);[ \t]*$\n?",
    "",
    session_source,
    count=1,
    flags=re.MULTILINE
)
session_source, call_count = re.subn(
    r"^[ \t]*ReportLlmSetConfigToFirebase\([^;]*\);[ \t]*\n",
    "",
    session_source,
    flags=re.MULTILINE
)
if declaration_count != 1 or call_count != 3 or "ReportLlmSetConfigToFirebase" in session_source:
    raise SystemExit(f"Expected to remove 3 native crash-report call sites; removed {call_count}")
session_cpp.write_text(session_source, encoding="utf-8")

# Remove JNI forwarding of model IDs and full configuration JSON to Crashlytics.
jni_path = app / "app/src/main/cpp/llm_mnn_jni.cpp"
jni = jni_path.read_text(encoding="utf-8")
jni = sub_once(
    jni,
    r"\nnamespace \{\nJavaVM\* g_jvm = nullptr;.*?\n\}\n\nvoid ReportLlmSetConfigToFirebase\(.*?\n\}\n",
    "", "native Crashlytics JNI bridge"
)
jni = jni.replace("    g_jvm = vm;\n", "")
jni = jni.replace("    g_jvm = nullptr;\n", "")
if "ReportLlmSetConfigToFirebase" in jni or "g_jvm" in jni:
    raise SystemExit("Native Crashlytics JNI forwarding symbols remain")
jni_path.write_text(jni, encoding="utf-8")

# Remove the now-meaningless crash-diagnostics consent toggle from Settings.
settings_fragment = java / "mainsettings/MainSettingsFragment.kt"
settings_source = settings_fragment.read_text(encoding="utf-8")
settings_source = settings_source.replace(
    "import com.alibaba.mnnllm.android.privacy.PrivacyPolicyManager\n", ""
)
settings_source = settings_source.replace(
    "import com.alibaba.mnnllm.android.MnnLlmApplication\n", ""
)
for line in [
    "    private var suppressCrashDiagnosticsCallback = false\n",
    "    private var crashDiagnosticsToggleInProgress = false\n",
    "    private var crashDiagnosticsDialogShowing = false\n",
    "        setupCrashDiagnostics()\n",
]:
    settings_source = settings_source.replace(line, "")
settings_source = block(
    settings_source,
    "    private fun setupCrashDiagnostics() {",
    "    private fun setupUpdateAndVersion() {",
    "",
    "MainSettingsFragment crash-consent controls"
)
settings_fragment.write_text(settings_source, encoding="utf-8")

settings_layout = app / "app/src/main/res/layout/fragment_main_settings.xml"
settings_xml = settings_layout.read_text(encoding="utf-8")
settings_xml = sub_once(
    settings_xml,
    r'\s*<com\.alibaba\.mnnllm\.android\.modelsettings\.SettingsItemView\b(?=[^>]*android:id="@\+id/item_crash_diagnostics")[^>]*/>',
    "", "Settings crash-diagnostics switch"
)
settings_layout.write_text(settings_xml, encoding="utf-8")

# 9. Remove benchmark leaderboard upload and JSONL file upload paths.

# Benchmarking remains local; users may still choose to share a rendered result image themselves.
presenter_path = java / "benchmark/BenchmarkPresenter.kt"
presenter = presenter_path.read_text(encoding="utf-8")
presenter = presenter.replace("    private val leaderboardService = LeaderboardService()\n", "")
presenter = presenter.replace(
    """    // Feature toggle: true for leaderboard upload, false for share
    // You can change this to false to test the share functionality
    private val useLeaderboardUpload = false
""",
    ""
)
presenter = block(
    presenter,
    "    override fun onUploadToLeaderboardClicked() {",
    "    override fun getCurrentState(): BenchmarkState {",
    "",
    "BenchmarkPresenter leaderboard upload handler"
)
presenter = presenter.replace("// Save results for leaderboard upload", "// Keep benchmark results locally for the result card")
presenter_path.write_text(presenter, encoding="utf-8")

contract_path = java / "benchmark/BenchmarkContract.kt"
contract = contract_path.read_text(encoding="utf-8")
for method in [
    "        fun uploadToLeaderboard()\n",
    "        fun showUploadProgress(message: String)\n",
    "        fun hideUploadProgress()\n",
    "        fun showRankInfo(rank: Int, totalUsers: Int)\n",
    "        fun onUploadToLeaderboardClicked()\n",
]:
    if method not in contract:
        raise SystemExit(f"BenchmarkContract leaderboard method missing: {method.strip()}")
    contract = contract.replace(method, "")
contract = contract.replace("        // Share and Leaderboard functionality\n", "        // Local result sharing only\n")
contract_path.write_text(contract, encoding="utf-8")

fragment_path = java / "benchmark/BenchmarkFragment.kt"
fragment = fragment_path.read_text(encoding="utf-8")
fragment = block(
    fragment,
    "    override fun uploadToLeaderboard() {",
    "    private fun createBitmapFromView(view: View): Bitmap {",
    "",
    "BenchmarkFragment leaderboard upload UI handlers"
)
fragment = fragment.replace(
    '// For other text (like "Share", "Upload to Leaderboard"), hide icon and arrow',
    '// For other button labels, hide icon and progress indicator'
)
fragment_path.write_text(fragment, encoding="utf-8")

# These two classes contain no other call sites in the app and are now removed from the APK.
for relative in [
    "app/src/main/java/com/alibaba/mnnllm/android/benchmark/LeaderboardService.kt",
    "app/src/main/java/com/alibaba/mnnllm/android/benchmark/BenchmarkTest.kt",
]:
    file_path = app / relative
    if file_path.exists():
        file_path.unlink()

for relative in [
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-zh-rCN/strings.xml",
    "app/src/main/res/values-zh-rTW/strings.xml",
]:
    resource = app / relative
    if resource.exists():
        text = resource.read_text(encoding="utf-8")
        text = re.sub(
            r'^\s*<string\s+name="(?:upload_to_leaderboard|leaderboard_ranking|leaderboard_rank_info|leaderboard_rank_not_found)">.*?</string>\s*\n?',
            "",
            text,
            flags=re.MULTILINE
        )
        resource.write_text(text, encoding="utf-8")

# Remove obsolete debug-only reporting labels.
for relative in [
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-zh-rCN/strings.xml",
    "app/src/main/res/values-zh-rTW/strings.xml",
]:
    path = app / relative
    if path.exists():
        text = re.sub(
            r'\s*<string\s+name="debug_test_crashlytics_[^"]+">.*?</string>',
            "", path.read_text(encoding="utf-8"), flags=re.DOTALL
        )
        path.write_text(text, encoding="utf-8")

# Fail closed if any automatic or manual crash-report SDK/hook survives.
for root in [
    app / "app/src/main/java", app / "app/src/main/cpp",
    app / "app/src/main/AndroidManifest.xml", app / "app/build.gradle"
]:
    files = [root] if root.is_file() else list(root.rglob("*.kt")) + list(root.rglob("*.cpp"))
    for path in files:
        source = path.read_text(encoding="utf-8", errors="ignore")
        for term in [
            "FirebaseCrashlytics", "FirebaseAnalytics", "CrashUtil.shareLatestCrash",
            "CrashUtil.init(", "ReportLlmSetConfigToFirebase"
        ]:
            if term in source:
                raise SystemExit(f"Forbidden crash/analytics hook remains: {path} ({term})")
if "com.google.firebase" in (app / "app/build.gradle").read_text(encoding="utf-8"):
    raise SystemExit("Firebase dependencies remain in app/build.gradle")

# Fail the build if any app-side telemetry, leaderboard or benchmark-file uploader survives.
for root in [app / "app/src/main/java", app / "app/src/main/cpp"]:
    for path in list(root.rglob("*.kt")) + list(root.rglob("*.java")) + list(root.rglob("*.cpp")):
        source = path.read_text(encoding="utf-8", errors="ignore")
        for forbidden in [
            "mnn-mnnchatleaderboard.ms.show",
            "LeaderboardService",
            "onUploadToLeaderboardClicked",
            "uploadToLeaderboard",
            "uploadJsonlFiles",
            "uploadJsonlx",
            "uploadInfoJsonString",
        ]:
            if forbidden in source:
                raise SystemExit(f"Forbidden upload feature remains: {path} ({forbidden})")

print("Firebase Crashlytics/Analytics SDKs, dependencies and plugin bindings removed.")
print("Crash context forwarding, local crash-log capture/export and debug crash tests removed.")
print("Developer issue-report entry points hidden/disabled; ordinary model downloads remain.")
