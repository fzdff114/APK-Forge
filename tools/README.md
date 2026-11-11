# APK Forge local-first AI tools

APK Forge exposes real project filesystem and Android build operations to an AI coding agent through an MCP stdio server. The agent writes complete Android source/configuration files into a local workspace, scans the project, runs Gradle locally, reads real compiler errors, repairs the project, verifies the APK and exports it. It does not require GitHub Actions.

## On-device requirements

The Android app delegates native command execution to the official Termux app. Install Termux, grant APK Forge the dangerous com.termux.permission.RUN_COMMAND permission, enable allow-external-apps=true in Termux, and run termux-setup-storage with storage permission. APK Forge's Local Build Environment screen has a setup command and a toolchain installer.

The installer uses Termux packages for Python, Git, OpenJDK 21, Gradle, Android SDK, Android build utilities, zipalign, apksigner and unzip. It installs Android platform 35 and build-tools 36.1.0. First setup downloads a large toolchain and needs sufficient free storage.

## Start MCP in Termux

Clone the APK Forge repository inside Termux (the development branch while local-first work is being validated):

~~~sh
git clone --branch local-first-prompt-builder https://github.com/fzdff114/APK-Forge.git
cd APK-Forge
export ANDROID_HOME="$PREFIX/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
python3 tools/apkforge_mcp.py --workspace "$HOME/apkforge-workspace"
~~~

Register that command in an AI coding agent that runs in the same local environment and can start MCP stdio processes. The agent must share the configured workspace. The MCP server is a tool interface; it does not call a model or store provider credentials. A cloud-only AI session with no bridge to the phone cannot directly edit phone files.

## Tools exposed

| Tool | What it actually does |
|---|---|
| workspace_info | Detects local Java, Gradle and Android SDK |
| create_project | Creates a real Kotlin + Compose project scaffold |
| list_files | Lists workspace files, hiding build caches and secrets |
| read_file | Reads source/config text; blocks local paths and signing secrets |
| write_file | Atomically writes a real project file |
| edit_file | Applies exact, ambiguity-checked source edits |
| delete_file | Deletes an explicitly confirmed project file |
| search_code | Searches source/configuration files with line numbers |
| analyze_project | Runs APK Forge's Android project scanner |
| build_apk | Executes local Gradle assembleDebug and checks the APK |
| read_build_log | Returns real Gradle/compiler failure lines for AI repair |
| verify_apk | Checks ZIP integrity, Manifest, DEX and SHA-256 |
| export_apk | Copies a verified APK to ~/storage/shared/Download/APKForge/ in Termux; on other hosts uses workspace/exports/ |

## Required agent workflow

1. Convert the user's prompt to a testable feature checklist.
2. Call workspace_info, then create_project (or inspect the provided project).
3. Implement all real features by writing files. Do not stop at the scaffold; no fake buttons, TODO-only files or empty classes.
4. Call analyze_project and fix actual errors.
5. Review Gradle scripts. With explicit user approval, call build_apk with allow_project_code=true.
6. On failure, call read_build_log, repair the root cause and rebuild.
7. Call verify_apk, then export_apk; return the exported path and SHA-256.

## ZIP handoff fallback

If the AI agent is running somewhere that cannot access the phone's MCP process, it should generate a complete Android Studio project ZIP (including settings/build files, manifest, sources and resources). Import that ZIP in APK Forge. The Android app scans and builds it locally through Termux. This flow still does not upload source to GitHub.

## Direct command-line fallback

The local CLI can create, inspect, build and verify projects:

~~~sh
python3 tools/apkforge.py scaffold --name "Notes App" --package com.example.notes --output generated-project
python3 tools/apkforge.py scan --project generated-project --format markdown
python3 tools/apkforge.py build --project generated-project --confirm-gradle-code
python3 tools/apkforge.py verify --apk generated-project/app/build/outputs/apk/debug/app-debug.apk
~~~

Build safety: Gradle scripts and plugins can execute code. Review source and explicitly approve execution. A static scanner does not replace a real Gradle build.
