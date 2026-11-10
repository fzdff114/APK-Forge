# Architecture

## Android application

The APK Forge Android app uses Kotlin and Jetpack Compose. It imports ZIP files through the Storage Access Framework and stores working projects in app-private storage. ZIP extraction rejects path traversal and enforces entry-count and expanded-size limits.

The app provides project inspection and GitHub Actions build/download functionality. It does not include an AI chat client, API endpoint form, model selector, API key form, or in-app AI code generator.

## Repository-side AI tools

The AI workflow lives in repository files and command-line tools. AI coding agents read AGENTS.md, docs/AI_USAGE.md, ai-prompts/ and tools/README.md, then execute tools/apkforge.py from their own terminal environment.

The command-line tool can create a minimal Android scaffold, inspect Gradle/Manifest/source structure, prepare a context-aware repair prompt, execute assembleDebug after explicit confirmation, verify APK contents and SHA-256, and package source while excluding local secrets and build outputs.

The script uses only Python's standard library. It does not call an AI model and does not store API credentials. The selected AI coding agent supplies the intelligence and edits source files.

## AI agent workflow

1. Read the user's app specification and define acceptance criteria.
2. Scaffold a new project or inspect the existing one.
3. Implement genuine features while preserving existing behavior.
4. Run the static scan, review findings and fix root causes.
5. Review build scripts and obtain build authorization.
6. Run Gradle, inspect logs and repair compiler errors.
7. Verify the exact APK archive and report its SHA-256.

## Remote build runner

GitHub Actions is the remote build backend. The ai-project-build.yml workflow builds a project directory in the checked-out repository. The ZIP-based android-build.yml workflow is available for private-repository temporary-branch uploads. Private project source must not be uploaded to a Public repository.

Gradle scripts and plugins can execute code. The repository tool requires the explicit --confirm-gradle-code flag before running a build. Review untrusted projects before building.

## Repair policy

Only deterministic, reversible changes should be automated. AI-generated source changes must be reviewed by the agent/user. Never create empty source files as a substitute for missing implementations. A passing static scan is not proof that Gradle compiles.
