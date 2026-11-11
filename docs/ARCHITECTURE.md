# Architecture

## End-to-end flow

**Prompt → AI coding agent → MCP workspace file operations → Android project scan → local Gradle → APK verification/export.**

The model runs in the selected AI coding environment. APK Forge supplies project filesystem/build tools; it is not an AI model client and does not embed API keys or a model selector.

## Android app

The Kotlin/Compose Android app:
- creates an engineering prompt from user requirements for an external AI coding agent;
- imports a complete Android project ZIP when the agent runs elsewhere;
- inspects the project using ProjectAnalyzer;
- delegates local builds through Termux RunCommandService;
- stores job status/logs and APK output in Download/APKForge/;
- rechecks APK ZIP integrity, Manifest, DEX and SHA-256 before export.

Termux supplies Android-native binaries at its own package-specific prefix. APK Forge does not unpack another app's Termux bootstrap into its own private directory. First-time setup installs Python, JDK 21, Gradle and Android SDK/build tools in Termux.

## Local MCP tool server

tools/apkforge_mcp.py exposes MCP stdio tools for workspace discovery and safe project file CRUD, scaffold generation, code search, Gradle/Manifest/source scanning, local assembleDebug, build-log retrieval, APK verification and export.

Workspace operations reject path traversal and symlinks, cap text file size and block common credential, keystore and local SDK files. The build operation requires explicit approval because Gradle scripts are executable code.

When the MCP server runs inside Termux, tools/apkforge.py detects Termux's environment, temporarily points Gradle at the on-device Android SDK and native aapt2, uses low-memory/no-daemon settings and restores the project's original machine-local properties afterwards.

## Two project handoff modes

1. **Direct local agent:** AI agent and MCP server run in the same Termux environment. The agent creates files and builds in one workspace, then calls export_apk.
2. **ZIP handoff:** the AI agent runs elsewhere, exports a complete source ZIP, and the user imports it in APK Forge. The app compiles it locally through Termux.

GitHub is optional source backup/sharing. GitHub Actions is used for APK Forge development CI, not normal user-project compilation.

## Validation and repair policy

A static scan is not a compiler. A build only counts as successful when Gradle succeeds, an APK is found, APK ZIP/Manifest/DEX and signature checks pass, and SHA-256 is calculated. When Gradle fails, use the actual build log to fix the first root cause; don't hide errors with version downgrades or blanket suppressions.
