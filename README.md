# APK Forge

APK Forge is an Android utility for inspecting Android projects and obtaining debug APK builds. AI-assisted project generation and repair are provided as repository-side tools for AI coding agents, not as an AI client embedded in the Android app.

## Android application

- Import an Android project ZIP using the Android file picker.
- Safely extract and inspect the project structure.
- Check Gradle settings, Android modules, Manifest files, source files, SDK configuration and wrapper completeness.
- Configure a Private GitHub repository for remote builds.
- Trigger a remote build, track the result, download the APK artifact and save it on the phone.
- The app does not contain an AI chat UI, AI API endpoint, model selector or AI API key field.

## Repository-side AI tools

AI coding agents use the Python tools in tools/apkforge.py from their own terminal or tool environment. Read AGENTS.md and tools/README.md first.

- scaffold: create a real minimal Android project skeleton.
- scan: inspect a project's modules, Gradle files, Manifest, sources and wrapper.
- prompt: create a context-aware AI repair task from actual scan findings.
- build: run assembleDebug, save logs and locate/verify the generated APK.
- verify: validate APK archive integrity, Manifest, dex entries and SHA-256.
- archive: create a filtered source ZIP for remote builds.

Requirements: Python 3.10+; no pip packages are required.

## Generate a new APK with an AI coding agent

1. Open this repository in an AI coding agent that can read files and run terminal commands.
2. Tell the agent to read AGENTS.md and tools/README.md.
3. Describe the Android app you want. The agent creates generated-project/ with scaffold, then implements the actual features.
4. The agent runs scan, fixes errors, reviews Gradle scripts, then builds and verifies the APK.
5. The agent reports the verified APK path and SHA-256.

Example:

~~~sh
python3 tools/apkforge.py scaffold --name "Offline Notes" --package com.example.offlinenotes --output generated-project
python3 tools/apkforge.py scan --project generated-project
python3 tools/apkforge.py build --project generated-project --confirm-gradle-code
python3 tools/apkforge.py verify --apk generated-project/app/build/outputs/apk/debug/app-debug.apk
~~~

See docs/AI_USAGE.md, docs/TOOLS_REFERENCE.md, ai-prompts/GENERATE_ANDROID_APP.md and ai-prompts/REPAIR_ANDROID_PROJECT.md.

## Build workflows

- APK Forge app CI: .github/workflows/apkforge-ci.yml
- AI-generated project build: .github/workflows/ai-project-build.yml
- ZIP-based remote build backend: .github/workflows/android-build.yml

The AI project build workflow is manually dispatched and builds a project directory committed in the selected repository. Use a Private repository for private app source code. Do not send source code to a Public repository.

## Build stack

- Android Gradle Plugin: 8.9.2
- Kotlin: 2.1.20
- JDK: 17
- compileSdk: 35
- minSdk: 26

APK Forge does not claim a build succeeded until Gradle reports success and APK verification passes.
