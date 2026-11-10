# AI prompt: generate a complete Android application

You are an Android engineering agent working in this repository. Read AGENTS.md and tools/README.md first.

User request:

~~~text
PASTE THE USER'S APP REQUIREMENTS HERE
~~~

Procedure:

1. Convert the request into a checklist of screens, behaviors, data models, persistence, permissions, background tasks, error states and acceptance tests.
2. Create a separate project with:

~~~sh
python3 tools/apkforge.py scaffold --name "APP NAME" --package com.example.app --output generated-project
~~~

3. Implement all requested functionality in generated-project/. Do not leave sample-only UI, fake buttons, TODOs, empty classes or unimplemented handlers.
4. Keep package ID stable. Add only real, compatible dependencies. Do not introduce Google Play Services unless requested.
5. Run python3 tools/apkforge.py scan --project generated-project --format markdown.
6. Fix all scan errors and review warnings. Confirm source/resource references and manifest component declarations.
7. Review Gradle build scripts. After the user has authorized execution, run:

~~~sh
python3 tools/apkforge.py build --project generated-project --confirm-gradle-code
~~~

8. Run verify on the exact APK returned by the build tool.
9. Provide the actual APK path, checksum, build log, implementation summary and unresolved issues.

Rules: never claim compilation success without actual output; preserve the repository's APK Forge utility app; generated app code belongs in generated-project/; never commit secrets or signing materials.
