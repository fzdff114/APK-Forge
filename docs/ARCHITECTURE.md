# Architecture

## Mobile application

The Android app uses Kotlin and Jetpack Compose. It imports ZIP files through the Storage Access Framework and stores working projects in app-private storage. ZIP extraction rejects path traversal and enforces entry-count and expanded-size limits.

## AI generation and repair

The app connects to a user-configured OpenAI-compatible Chat Completions endpoint. Endpoint, model ID, and API key are configurable; no model is forced. API keys are encrypted at rest with AES-GCM using Android Keystore.

For new projects, AI receives the user's specification and returns a JSON manifest containing complete relative paths and complete text file contents. For repairs, the app collects selected Gradle, Manifest, source, resource, and version-catalog text files (bounded by a context limit) and sends them only after the user explicitly starts the request.

AI output is validated and shown as a preview before application. The app rejects path traversal, absolute paths, protected directories, signing credentials, local SDK configuration, executable Gradle wrapper binaries, unsupported file types, and oversized output. Existing files are backed up under `.apkforge/backups/` before replacement.

APK Forge never runs arbitrary AI-provided shell commands. Generated Gradle scripts are still executable project code, so users should review projects before remote building.

## Remote build runner

GitHub Actions is the first build backend. The selected repository must be Private. APK Forge verifies repository visibility, creates a temporary branch from the default branch, uploads a bounded ZIP, dispatches the build workflow, polls the run, downloads the APK artifact, and deletes the temporary branch after a completed run. If the run cannot be located or times out, the temporary branch is retained for troubleshooting.

The app must not upload source code merely because a project was imported or scanned. GitHub credentials and AI API keys must never be written to logs or project files.

## Repair policy

Only deterministic, reversible changes should be automated. AI-generated source changes require preview and explicit user approval. Never create empty source files as a substitute for missing implementations.
