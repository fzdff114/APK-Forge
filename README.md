# APK Forge

APK Forge is a local-first Android project-to-APK tool:

**Natural-language app prompt → AI coding agent creates real project files → on-device Gradle build → verified APK in Downloads.**

GitHub is optional source hosting. It is not required to generate or compile an app.

## Android app

- Copy a structured Android engineering task from your requirements prompt to an AI coding agent.
- Import a complete Android project ZIP when the AI agent runs outside the phone.
- Inspect Android projects, Gradle modules, Manifest, SDK configuration and sources.
- Initialize the local JDK, Gradle and Android SDK build environment through Termux.
- Build with Gradle on the Android device, without GitHub Actions.
- Review real build logs, verify the APK archive/Manifest/DEX/signature and SHA-256, then save the APK.
- No AI chat client, model selector, AI API endpoint or AI API-key form is embedded in this app.

The local build bridge uses the official Termux app as its execution environment. Install Termux, grant APK Forge the Termux RUN_COMMAND permission, enable Termux external commands, and grant Termux shared-storage access. The app's setup flow installs Python, JDK 21, Gradle and Android SDK packages. First setup downloads a sizable toolchain.

## AI coding-agent tools (MCP)

tools/apkforge_mcp.py is a real MCP stdio server. An AI coding agent connected to it can create an Android project, create/read/write/edit/delete source files in a sandboxed workspace, search code, scan Android/Gradle configuration, build locally with Gradle, read real build logs, repair errors, verify the APK and export it to Android Download/APKForge/.

On the phone, run the MCP server from Termux so Gradle uses the device-local toolchain:

1. Initialize Termux from APK Forge, or install Python, Git, JDK 21, Gradle and Android SDK packages in Termux.
2. Clone this repository's local-first-prompt-builder branch inside Termux.
3. Register the command below as an MCP server in an AI coding agent running in that same environment.
4. Give the agent your app prompt. It must create and implement complete files, call analyze_project, build_apk, read_build_log if needed, verify_apk, then export_apk.

~~~sh
pkg update -y
pkg install -y python git openjdk-21 gradle android-sdk apksigner d8 zipalign unzip
export ANDROID_HOME="$PREFIX/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
git clone --branch local-first-prompt-builder https://github.com/fzdff114/APK-Forge.git
cd APK-Forge
python3 tools/apkforge_mcp.py --workspace "$HOME/apkforge-workspace"
~~~

Run termux-setup-storage first so export_apk can copy the verified APK to ~/storage/shared/Download/APKForge/. APK Forge can also build a project ZIP imported from another AI environment.

The MCP server is a tool interface, not an AI model. The connected coding agent supplies the model/intelligence and must run where it can access the MCP process and its workspace. A remote AI chat without a local tool bridge cannot directly edit files on the phone.

## MCP tools

| Tool | Function |
|---|---|
| workspace_info | Detect Java, Gradle and Android SDK |
| create_project | Create a Kotlin/Compose Android scaffold |
| list_files, read_file, write_file, edit_file, delete_file | Operate on workspace files |
| search_code | Find source/configuration references |
| analyze_project | Scan Gradle modules, Manifest and sources |
| build_apk | Run local assembleDebug and verify build output |
| read_build_log | Return actual Gradle/compiler failures for AI repair |
| verify_apk | Validate ZIP, Manifest, DEX and SHA-256 |
| export_apk | Copy a verified APK to the configured export folder |

Requirements: Python 3.10+ for repository tools; Android app build uses AGP 8.9.2, Kotlin 2.1.20 and compileSdk 35. On-device runtime uses Termux's Android-native SDK and JDK 21.

## GitHub role

GitHub is for optional source backup, collaboration and sharing. This repository's Actions workflow validates APK Forge itself during development; it is not the normal build path for user-generated apps. Legacy remote-build workflows remain optional and are not used by the local-first app flow.

## Build safety

Gradle build scripts and plugins execute project code. APK Forge requires explicit approval before running a local build. Do not build untrusted project sources. The static scanner is heuristic; only a real successful Gradle build plus APK verification counts as success.
