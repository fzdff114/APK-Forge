# APK Forge

APK Forge is an Android-first project inspector and APK build assistant designed for AI-generated Android projects.

## Implemented

- Import Android project ZIP files on an Android phone.
- Safely extract ZIP contents into app-private storage with path traversal, entry-count, and expanded-size checks.
- Inspect Gradle entry points, Android modules, Manifest, source files, wrapper files, and common SDK configuration issues.
- Generate structured AI repair instructions with stable build keywords.
- Save GitHub credentials encrypted with Android Keystore.
- Export a clean project ZIP and trigger GitHub Actions remote builds from a Private repository.
- Track the build, download the APK artifact, and save the APK through Android's document picker.

## Important privacy rule

Remote builds are blocked for Public repositories. Use a Private repository that contains the workflow file. The app creates a temporary branch per build and deletes it after a completed run. Compressed project uploads are limited to 12 MiB.

## Build setup

See [docs/GITHUB_BUILD_SETUP.md](docs/GITHUB_BUILD_SETUP.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and [docs/AI_PROMPT_RULES.md](docs/AI_PROMPT_RULES.md).

## Android project

- Android app: app/
- Workflow: .github/workflows/android-build.yml
- Android Gradle Plugin: 8.9.2
- Kotlin: 2.1.20
- JDK: 17

APK Forge does not claim a project built successfully until the GitHub Actions run reports success and an APK artifact is downloaded.
