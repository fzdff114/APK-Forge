# APK Forge

APK Forge is an Android-first AI project generator, repair assistant, and APK build helper.

## Current features

- Configure an OpenAI-compatible Chat Completions API endpoint, model ID, and API key. The model is user-selected rather than hardcoded.
- Encrypt AI and GitHub API keys using AES-GCM with Android Keystore-backed keys.
- Generate a complete Android Studio project from a natural-language app specification.
- Import an existing Android project ZIP and ask AI to repair or extend it using selected project context.
- Preview AI-proposed file paths and notes before applying the result.
- Reject absolute paths, path traversal, secret/signing files, local SDK paths, and Gradle wrapper binaries.
- Keep backups of text files before AI overwrites them.
- Scan Gradle, Android modules, Manifest, source files, and wrapper configuration after applying AI output.
- Build through GitHub Actions and download the APK artifact from a Private build repository.

## AI usage

See [docs/AI_USAGE.md](docs/AI_USAGE.md) for API setup, new-project generation, existing-project repair, the keyword list, and security boundaries.

## Remote build privacy

Remote builds are blocked for Public repositories. Use a Private repository that contains `.github/workflows/android-build.yml`. Each build uses a temporary branch and deletes it after the completed run. Compressed project uploads are limited to 12 MiB.

## Android application

- Android app: `app/`
- APK Forge CI: `.github/workflows/apkforge-ci.yml`
- Project build backend: `.github/workflows/android-build.yml`
- Android Gradle Plugin: 8.9.2
- Kotlin: 2.1.20
- JDK: 17

APK Forge does not claim success until the build runner reports a successful Gradle build and an APK artifact is actually downloaded.
