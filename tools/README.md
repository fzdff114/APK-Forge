# APK Forge repository tools

These tools are for AI coding agents and developers working in this repository. They are command-line tools; they are not features embedded in the APK Forge Android application and do not call an AI model.

Requirements: Python 3.10+; no pip packages are required.

## Quick start

~~~sh
python3 tools/apkforge.py --help
python3 tools/apkforge.py scaffold --name "Notes App" --package com.example.notes --output generated-project
~~~

After creating the skeleton, the AI agent implements the user's actual requested functionality in generated-project/. Then run:

~~~sh
python3 tools/apkforge.py scan --project generated-project --format markdown
python3 tools/apkforge.py prompt --project generated-project --output .apkforge/repair-prompt.md
python3 tools/apkforge.py build --project generated-project --confirm-gradle-code
python3 tools/apkforge.py verify --apk generated-project/app/build/outputs/apk/debug/app-debug.apk
~~~

## Command reference

| Command | Purpose |
|---|---|
| scaffold | Create a real minimal Kotlin/Compose Android project |
| scan | Check structure, Gradle, Manifest, source and wrapper |
| prompt | Generate a project-specific AI repair task |
| build | Run assembleDebug, save logs, locate and verify the resulting APK |
| verify | Inspect APK ZIP integrity, Manifest, dex entries and SHA-256 |
| archive | Create a filtered source archive for remote builds |

## Build safety

The build command requires --confirm-gradle-code. This is intentional: Gradle build scripts and plugins can execute code. Review project files before running the build tool. The scanner is a static heuristic and does not replace the actual Gradle compiler.

## Remote build

The workflow .github/workflows/ai-project-build.yml can build a project directory committed in the selected repository. The existing android-build.yml workflow builds a source ZIP uploaded to a temporary branch. Both require GitHub Actions and a Private repository for private source code.
