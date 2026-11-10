# APK Forge

APK Forge is an Android-first project inspector and APK build assistant designed for AI-generated Android projects.

## Goals

- Import Android project ZIP files on an Android phone.
- Inspect project structure and detect missing or inconsistent build files.
- Generate actionable, context-aware prompts that can be sent back to an AI coding assistant.
- Safely apply only deterministic repairs; never create empty placeholder source files.
- Build APKs through a user-controlled GitHub Actions runner, without requiring Google Play services on the phone.

## Current status

This repository is being initialized. The first milestone is the project-analysis and AI-prompt workflow. Remote build execution is being scaffolded separately and will require the user to configure a GitHub token with the minimum repository and Actions permissions.

## Safety and privacy

- Projects are analyzed locally before upload.
- Source code is uploaded to the configured repository only after the user explicitly starts a remote build.
- GitHub credentials must be stored using Android secure storage and must never be written to logs or project files.
- ZIP extraction must reject path traversal entries and enforce file/size limits.

## Development

- Android application: `app/`
- Remote build workflow: `.github/workflows/android-build.yml`
- Android Gradle Plugin / Kotlin versions are pinned in the Gradle build files.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/AI_PROMPT_RULES.md](docs/AI_PROMPT_RULES.md).