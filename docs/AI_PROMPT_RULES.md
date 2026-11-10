# AI prompt rules

Stable build keywords:

- PROJECT_SCAN: map modules, source sets, files, resources, and entry points.
- DEPENDENCY_CHECK: verify declared dependencies and referenced libraries.
- MANIFEST_REPAIR: inspect manifest components, permissions, and exported flags.
- GRADLE_REPAIR: inspect settings, plugins, wrapper, repositories, and tasks.
- SOURCE_COMPLETION: resolve missing source implementations without empty placeholders.
- RESOURCE_CHECK: verify layouts, drawables, strings, assets, and referenced IDs.
- SDK_COMPATIBILITY: check JDK, Gradle, AGP, Kotlin, compileSdk, targetSdk, and minSdk.
- BUILD_VALIDATION: run Gradle and interpret actual build logs.
- APK_EXPORT: locate and report the produced APK.

AI response contract:
1. State findings before editing.
2. Identify exact file paths.
3. Return complete files or precise patches.
4. Preserve existing behavior unless a change is required and explained.
5. Never claim a build passed without output proving it.
6. List unresolved blockers separately.
