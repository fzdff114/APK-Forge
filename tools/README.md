# APK Forge local-first AI tools

APK Forge exposes real filesystem and build operations to an AI coding agent through an MCP stdio server. The agent writes actual Android source files into a workspace, scans the project, runs a local Gradle build, reads compiler errors, repairs the project and verifies the APK. It does not require GitHub Actions.

Requirements: Python 3.10+; no pip packages are required.

## Connect the tools to an AI coding agent

Start the MCP server from a local checkout:

~~~sh
python3 tools/apkforge_mcp.py --workspace ./apkforge-workspace
~~~

For a Codex-style MCP configuration, register the command with the absolute path to the checkout and workspace. For example:

~~~sh
codex mcp add apk-forge -- python3 /absolute/path/APK-Forge/tools/apkforge_mcp.py --workspace /absolute/path/apkforge-workspace
~~~

A Claude Desktop-style configuration looks like:

~~~json
{
  "mcpServers": {
    "apk-forge": {
      "command": "python3",
      "args": [
        "/absolute/path/APK-Forge/tools/apkforge_mcp.py",
        "--workspace",
        "/absolute/path/apkforge-workspace"
      ]
    }
  }
}
~~~

The AI agent must run in an environment that can start this MCP process and access the same workspace. This is a tool server, not an AI model and not an AI chat application.

## Tools exposed to the agent

| Tool | What it actually does |
|---|---|
| workspace_info | Detects Java, Gradle and Android SDK availability |
| create_project | Creates a Kotlin + Compose Android project scaffold |
| list_files | Lists workspace project files |
| read_file | Reads source/configuration text; protects local paths and secrets |
| write_file | Atomically writes real project source/configuration files |
| edit_file | Applies exact, ambiguity-checked source edits |
| delete_file | Deletes an explicitly confirmed project file |
| search_code | Searches source/configuration text with line numbers |
| analyze_project | Runs the Android project scanner |
| build_apk | Runs local Gradle assembleDebug and verifies the output APK |
| read_build_log | Returns real Gradle/compiler failure lines for AI repair |
| verify_apk | Checks APK ZIP integrity, Manifest, dex entries and SHA-256 |

## Expected AI generation workflow

1. The user describes the app in a prompt.
2. The AI agent calls create_project (or inspects an imported project).
3. The agent implements all requested features using write_file and edit_file. A scaffold is not considered a finished app.
4. The agent calls analyze_project and fixes actual blockers.
5. After reviewing the project's Gradle scripts, the agent calls build_apk with allow_project_code=true.
6. On a failure, the agent calls read_build_log, fixes the root cause and rebuilds.
7. The agent calls verify_apk and returns the APK path and digest.

The project, Gradle invocation, build log and APK stay in the workspace where the MCP server runs. The tool server does not upload source to GitHub and does not dispatch GitHub Actions. GitHub export/hosting is a separate optional operation.

## Important distinction about the Android app

The MCP server's Gradle build is local to the computer/runtime that launches it. It is not a substitute for an Android on-device Gradle runtime. The APK Forge Android app still needs its on-device build engine integrated; the legacy GitHub remote-build screen is not the desired final workflow.

## Command-line fallback

The same analysis and build functions are available directly:

~~~sh
python3 tools/apkforge.py scaffold --name "Notes App" --package com.example.notes --output generated-project
python3 tools/apkforge.py scan --project generated-project --format markdown
python3 tools/apkforge.py build --project generated-project --confirm-gradle-code
python3 tools/apkforge.py verify --apk generated-project/app/build/outputs/apk/debug/app-debug.apk
~~~

Build safety: Gradle scripts and plugins can execute code. Review the project before building and explicitly approve execution. The static scanner does not replace a real Gradle build.
