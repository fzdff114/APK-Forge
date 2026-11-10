# How AI agents use APK Forge tools

## Important distinction

AI functionality lives in the GitHub repository as scripts and agent instructions. The APK Forge Android app itself does not contain an AI chat screen, AI API endpoint, model selector or AI API key field. The AI coding agent connected to this repository is the caller of the repository tools.

## Set up an AI coding agent

Open this repository in an AI coding environment that can read files and run terminal commands. The agent should read AGENTS.md automatically. If it does not, explicitly tell it:

~~~text
Read AGENTS.md and tools/README.md. Use the repository's apkforge.py tools to create, inspect, repair, build and verify Android projects. Keep generated app code under generated-project/ and do not add AI API configuration to the APK Forge Android app.
~~~

## Generate a new Android app

1. Tell the AI what application to create, including screens, behaviors, data storage, permissions, device/Android version constraints and network requirements.
2. The AI calls scaffold to create a complete Gradle project skeleton.
3. The AI writes real app implementation files into generated-project/.
4. The AI calls scan and fixes structural errors.
5. After reviewing build scripts and obtaining build authorization, the AI calls build.
6. The AI calls verify and reports the actual APK path and SHA-256.

Example:

~~~sh
python3 tools/apkforge.py scaffold --name "Offline Notes" --package com.example.offlinenotes --output generated-project
~~~

## Repair an existing AI-generated project

1. Put the project in generated-project/ or give the agent its exact path.
2. Ask the agent to inspect the project and run scan.
3. Ask it to read the report and generate a repair plan; prompt assembles findings and selected project context.
4. The agent fixes missing or inconsistent files without overwriting unrelated features.
5. It rebuilds and verifies the output APK.

## Tool commands

| Tool | What AI uses it for |
|---|---|
| scaffold | Create a real Android project skeleton |
| scan | Find likely structural/build blockers |
| prompt | Give AI project-specific repair context |
| build | Compile with Gradle and locate the APK |
| verify | Check APK archive, Manifest, dex and SHA-256 |
| archive | Package source safely for a remote build |

## Build using GitHub Actions

The workflow .github/workflows/ai-project-build.yml can build generated-project/ after the agent commits the project. Open GitHub Actions, select “Build AI generated Android project”, choose the project directory, and run it. The APK is uploaded as an artifact.

Alternatively, the agent can run apkforge.py build in a prepared build environment. The workflow uses JDK 17 and Android SDK 35.

## What AI must not do

- Do not claim a ZIP, source folder, or generated code is an APK.
- Do not claim success when Gradle failed or when no APK artifact exists.
- Do not create empty files just to silence scan warnings.
- Do not silently change application ID or remove existing features.
- Do not place AI API keys, signing files, or local SDK paths in the repository.
- Do not upload private project source to a Public GitHub repository.
- Do not put AI API settings or AI code generation UI inside the APK Forge Android app.
