# APK Forge - instructions for AI coding agents

This repository contains local-first MCP tools and an Android app that compiles Android projects on-device through Termux. The target workflow is prompt → AI agent creates complete project files through MCP → local Gradle build → verified APK export. Do not add an AI chat client, model selector or AI API-key form to APK Forge unless explicitly requested. GitHub is optional source hosting, not a normal build dependency.

## First steps

1. Read this file and tools/README.md.
2. Identify whether the user wants to modify APK Forge itself or create a separate Android app.
3. If creating a separate app, use a separate project directory (default: generated-project/) so the APK Forge utility app remains intact.
4. Turn the user's feature request into a concrete acceptance checklist before coding.

## Local-first MCP workflow

1. On the Android device, install Termux and grant APK Forge the Termux RUN_COMMAND permission.
2. Enable Termux external commands (allow-external-apps=true) and grant shared storage access with termux-setup-storage.
3. Initialize the local toolchain in APK Forge.
4. Run tools/apkforge_mcp.py from Termux and connect an AI coding agent running in that same environment. The agent creates files in the configured workspace, calls analyze_project/build_apk/read_build_log/verify_apk/export_apk, and saves the APK under Download/APKForge.
5. If the AI agent cannot access the phone's local MCP process, it must export a complete Android project ZIP for import into APK Forge. The app still builds it locally via Termux.

The MCP server is a tool interface, not an AI model. A remote chat session without a local tool bridge cannot directly access the phone filesystem.

## Repository tools

All commands use Python 3.10+ and the standard library only:

~~~sh
python3 tools/apkforge.py --help
python3 tools/apkforge.py scaffold --name "Example App" --package com.example.app --output generated-project
python3 tools/apkforge.py scan --project generated-project --format markdown
python3 tools/apkforge.py prompt --project generated-project --output .apkforge/repair-prompt.md
python3 tools/apkforge.py build --project generated-project --confirm-gradle-code
python3 tools/apkforge.py verify --apk generated-project/app/build/outputs/apk/debug/app-debug.apk
~~~

Commands:

- scaffold: creates a real minimal Kotlin + Compose Android project skeleton. It does not claim to implement the user's requested features; the agent must implement them.
- scan: checks Gradle entry points/modules, Android plugins, version-catalog aliases, Manifest XML/exported flags, source files, wrapper files, and machine-specific local.properties.
- prompt: emits a context-aware repair task containing scan findings and a bounded set of source/configuration files.
- build: executes assembleDebug using the project Gradle Wrapper or installed Gradle, stores logs under .apkforge/logs, locates the APK, and runs APK verification. It requires --confirm-gradle-code because Gradle build scripts are executable project code.
- verify: checks APK ZIP integrity, AndroidManifest.xml, classes*.dex, file size, and SHA-256.
- archive: creates a filtered source ZIP, excluding caches, build outputs, local SDK paths and signing secrets.

## Required AI workflow

### New Android application

1. Create a separate directory with scaffold, unless the user already supplied an Android project.
2. Implement the requested real features, UI, persistence, permissions, background work, and error handling. Do not leave placeholder methods, TODOs, fake buttons, or empty classes.
3. Confirm application ID, app label, minSdk, targetSdk, compileSdk, JDK, AGP, Kotlin, Compose and Gradle versions are mutually compatible.
4. Run the scan tool and fix all evidence-based errors. Treat warnings as items to review rather than blindly applying changes.
5. Review the actual Gradle files before a build. Ask for approval before executing a project whose build scripts have not been reviewed. When the user has already authorized the build, run the build tool with --confirm-gradle-code.
6. Run verify on the exact APK produced by the build. Never report success based only on the presence of an output directory.
7. Report APK path, SHA-256, app ID/version if known, build log path, and remaining limitations.

### Repair an existing project

1. Inspect the entire project before editing. Preserve working code, package ID, existing UI, assets, and behavior.
2. Run scan and prompt. Read the generated repair report and relevant source files.
3. Fix root causes rather than changing arbitrary version numbers or creating empty files.
4. Make a small patch, rerun scan, then build and verify.
5. If Gradle fails, read the full build log, identify the first root-cause error, fix it, and rebuild. Do not hide errors with broad suppressions.

## AI tool keywords

- PROJECT_SCAN: inspect the project tree and entry points.
- APP_REQUIREMENTS: convert the user's natural-language request into testable behaviors.
- PROJECT_SCAFFOLD: create the minimal Android project skeleton.
- DEPENDENCY_CHECK: validate dependency coordinates, aliases, repositories and module references.
- MANIFEST_REPAIR: validate components, permissions, exported flags and manifest references.
- GRADLE_REPAIR: repair settings, plugins, Gradle Wrapper and module build scripts.
- SOURCE_COMPLETION: implement genuine missing features and resolve source references.
- RESOURCE_CHECK: validate layouts, drawables, strings, assets and referenced resource identifiers.
- SDK_COMPATIBILITY: check JDK, Gradle, AGP, Kotlin and Android SDK compatibility.
- BUILD_VALIDATION: run the real Gradle build and inspect its log.
- BUILD_ERROR_REPAIR: map compiler errors to exact file/line and repair the cause.
- APK_VERIFY: check the actual APK archive, manifest, dex files and digest.
- APK_EXPORT: provide the verified APK path and checksum.

## Guardrails

- Never place AI API credentials, GitHub tokens, keystores, private keys, local.properties, or machine-specific paths in commits.
- Do not upload or commit a user's app source to this Public repository. Prefer a local workspace; GitHub hosting is optional and must not be required to compile an APK.
- Never run arbitrary AI-generated shell scripts. Use the documented tools and known Gradle tasks.
- A Gradle build can execute arbitrary project build logic. Review untrusted projects and obtain approval before building.
- The scan tool is heuristic, not a compiler. A zero-error scan does not prove that an APK builds.
- In Termux, apkforge.py temporarily configures the native Android SDK/aapt2 and restores project machine-local properties after the build.
- After successful verification, call export_apk to copy the APK into Android Downloads.
- An AI response is not a build result. Only successful Gradle output plus successful APK verification counts.
- Keep generated app code under generated-project/ unless the user explicitly asks to replace APK Forge itself.
