# APK Forge tool keywords and execution contract

AI agents should use these keyword labels in plans and reports. They are stable task identifiers, not network API keys or in-app features.

| Keyword | Action | Required tool or artifact |
|---|---|---|
| PROJECT_SCAN | Map project tree, modules and entry points | apkforge.py scan |
| APP_REQUIREMENTS | Convert request to testable acceptance criteria | AI planning step |
| PROJECT_SCAFFOLD | Create settings, Gradle, Manifest and main Activity | apkforge.py scaffold |
| DEPENDENCY_CHECK | Check dependency aliases and project module references | apkforge.py scan + source review |
| MANIFEST_REPAIR | Fix invalid XML, components, permissions and exported flags | apkforge.py scan + XML review |
| GRADLE_REPAIR | Fix settings, plugins, Wrapper, repositories and module config | apkforge.py scan + Gradle output |
| SOURCE_COMPLETION | Implement actual requested features | AI source editing |
| RESOURCE_CHECK | Check resource paths and references | AI source review + Android compiler |
| SDK_COMPATIBILITY | Check JDK, Gradle, AGP, Kotlin and SDK versions | scan + Gradle build |
| BUILD_VALIDATION | Execute assembleDebug and inspect logs | apkforge.py build |
| BUILD_ERROR_REPAIR | Fix the first root-cause compiler/runtime build issue | AI source editing + repeat build |
| APK_VERIFY | Validate archive, Manifest, dex and digest | apkforge.py verify |
| APK_EXPORT | Report the verified output path | Build result JSON |

Execution order: PROJECT_SCAN -> APP_REQUIREMENTS -> PROJECT_SCAFFOLD (new projects only) -> SOURCE_COMPLETION -> DEPENDENCY_CHECK -> MANIFEST_REPAIR -> GRADLE_REPAIR -> RESOURCE_CHECK -> SDK_COMPATIBILITY -> BUILD_VALIDATION -> BUILD_ERROR_REPAIR (repeat when needed) -> APK_VERIFY -> APK_EXPORT.
