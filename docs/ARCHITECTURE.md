# Architecture

## Milestone 1: local project inspection

The Android app imports project ZIP files through the Storage Access Framework and extracts them into app-private cache storage. The importer rejects path traversal and limits entry count and expanded size.

The analyzer locates the Gradle root, Android modules, manifests, source files, wrapper configuration, and common SDK/namespace issues. It reports evidence-based findings rather than assuming every missing optional file is an error.

The prompt generator converts findings into a structured repair request. The user remains in control of what is shared with an external AI.

## Milestone 2: remote build runner

A GitHub Actions workflow is planned as the build backend. A future in-app GitHub connection will ask the user to select a repository and approve source upload, store credentials in Android Keystore-backed encrypted storage, upload a bounded archive, dispatch the workflow, show run status, and retrieve the APK artifact.

The app must not upload source merely because a project was imported or scanned. Tokens must never be written to logs, clipboard, analytics, or project files.

## Repair policy

Only deterministic, reversible changes may be auto-applied. Code generation, dependency substitutions, API migrations, and business-logic changes require explicit review. Never create empty source files as a substitute for missing implementations.
