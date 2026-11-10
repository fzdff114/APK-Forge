# AI prompt rules

AI project generation and repair are repository-side workflows. AI coding agents use tools/apkforge.py; the APK Forge Android app does not contain an AI API client.

Stable tool keywords:

- PROJECT_SCAN: map modules, source sets, files, resources, and entry points. Run apkforge.py scan.
- APP_REQUIREMENTS: convert user language into screens, behaviors, data models and acceptance tests.
- PROJECT_SCAFFOLD: create a minimal complete Gradle/Manifest/Activity skeleton. Run apkforge.py scaffold.
- DEPENDENCY_CHECK: verify dependency aliases, module references, Maven coordinates and repositories.
- MANIFEST_REPAIR: inspect components, permissions, intent-filters and exported flags.
- GRADLE_REPAIR: inspect settings, plugins, Wrapper, repositories and tasks.
- SOURCE_COMPLETION: implement actual missing features without empty placeholders.
- RESOURCE_CHECK: inspect layouts, drawables, strings, assets and references.
- SDK_COMPATIBILITY: check JDK, Gradle, AGP, Kotlin, compileSdk, targetSdk and minSdk.
- BUILD_VALIDATION: run Gradle assembleDebug using apkforge.py build after reviewing build scripts.
- BUILD_ERROR_REPAIR: fix root causes using the real build log, then rebuild.
- APK_VERIFY: run apkforge.py verify on the exact generated APK.
- APK_EXPORT: report the verified APK path and SHA-256.

AI response contract:
1. State findings before editing.
2. Identify exact file paths.
3. Return complete files or precise patches.
4. Preserve existing behavior unless a change is required and explained.
5. Never claim a build passed without successful Gradle output and APK verification.
6. List unresolved blockers separately.
7. Keep generated apps under generated-project/ unless the user explicitly requests a different path.
