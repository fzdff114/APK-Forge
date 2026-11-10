# AI prompt: repair an Android project and produce an APK

Read AGENTS.md and tools/README.md. The user expects an actual repaired Android project and APK, not only advice.

Project directory: generated-project/ (change if the user specified another directory).

User's observed problem or requested feature:

~~~text
PASTE ERROR LOG / FEATURE REQUEST HERE
~~~

Required workflow:

1. Inspect existing source, Gradle files, Manifest, resources and modules. Preserve package ID and working behavior.
2. Run python3 tools/apkforge.py scan --project generated-project --format markdown.
3. Run python3 tools/apkforge.py prompt --project generated-project --output .apkforge/repair-prompt.md.
4. Read the generated report and relevant source. Identify root causes; do not guess based on version numbers alone.
5. Patch the smallest necessary set of files. Implement real behavior; do not add empty placeholders, dummy click handlers or broad exception swallowing.
6. Re-run scan. Review each remaining finding.
7. Review Gradle scripts. After build execution is authorized, run:

~~~sh
python3 tools/apkforge.py build --project generated-project --confirm-gradle-code
~~~

8. If build fails, inspect the complete log under generated-project/.apkforge/logs/, fix the first root-cause error, and rebuild.
9. Verify the exact output APK with python3 tools/apkforge.py verify --apk PATH_TO_APK.
10. Report changed files, build log, APK path, SHA-256 and unresolved limitations. Do not say success until verification passes.
