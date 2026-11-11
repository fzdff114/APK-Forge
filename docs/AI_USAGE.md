# AI agent usage: prompt to project files to APK

## Intended workflow

The user describes an Android app in natural language. An AI coding agent must create and implement the complete Android project (not merely return snippets), call APK Forge tools, run a real local Gradle build, repair actual failures, verify the APK and export it.

The APK Forge Android app has no built-in AI chat, model picker or AI API-key screen. The user connects an AI coding agent to the local MCP server instead.

## Connect the MCP server on Android

1. Install the official Termux app.
2. In APK Forge, grant the com.termux.permission.RUN_COMMAND permission.
3. In Termux, enable external commands and storage access. Use the setup command shown by APK Forge; it sets allow-external-apps=true, reloads Termux settings and runs termux-setup-storage.
4. Run APK Forge's local toolchain setup. It installs Python, Git, JDK 21, Gradle, Android SDK, native Android build tools and signing verification tools.
5. Clone this repository's local-first-prompt-builder branch in Termux and register tools/apkforge_mcp.py as a local MCP server in an AI coding agent running in the same environment.

Example:

~~~sh
git clone --branch local-first-prompt-builder https://github.com/fzdff114/APK-Forge.git
cd APK-Forge
python3 tools/apkforge_mcp.py --workspace "$HOME/apkforge-workspace"
~~~

The agent must be able to start that process and share the same filesystem. The MCP server is the tool interface, not the model. A remote AI session without a local tool bridge cannot write to phone files.

## Required AI generation sequence

1. Convert the request into acceptance criteria: screens, interactions, data, persistence, permissions, network behavior, error states, Android versions and tests.
2. Call workspace_info.
3. Call create_project or inspect the user's existing project.
4. Implement real functionality by calling write_file and edit_file. Use read_file, list_files and search_code to inspect implementation. Do not stop at an empty scaffold.
5. Call analyze_project, fix structural/Manifest/Gradle errors and review warnings.
6. Review the Gradle scripts. After explicit approval, call build_apk with allow_project_code=true.
7. On failure, call read_build_log, repair the root cause and rebuild.
8. Call verify_apk.
9. Call export_apk to copy the verified APK to Android Download/APKForge/ (when running under Termux), then report the saved path and SHA-256.

## If the AI agent runs elsewhere

When the AI agent cannot access the phone's local MCP process, have it generate a complete Android Studio project and export the full source project as a ZIP. Import that ZIP in APK Forge. The Android app scans it and runs the build locally through Termux. This is a fallback handoff, not a GitHub upload.

## Security

- Gradle scripts and plugins can execute code; only build projects you trust and have reviewed.
- The MCP server confines file operations to its configured workspace and blocks credentials, keystores and machine-local SDK configuration.
- Do not upload project source to GitHub to compile it.
- Never claim an APK exists until Gradle succeeds and APK verification passes.
