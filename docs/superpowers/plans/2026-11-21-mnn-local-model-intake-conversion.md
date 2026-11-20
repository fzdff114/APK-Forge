# MNN Chat Local Model Intake and Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the arm64 MNN Chat APK to recognize common local model formats, import valid MNN ZIP/folder packages safely, explain conversion prerequisites, and add a real offline converter only for formats and architectures whose native backend can be built and verified.

**Architecture:** Keep Android SAF file/folder selection and app-private storage in Kotlin. Put format inspection, GGUF metadata parsing, archive validation, and MNN-template validation in testable Kotlin helpers. Any weight conversion must execute through a native arm64 backend using source-derived MNN conversion logic; if that backend cannot be linked and verified in this build, the UI must report the missing capability and must not create a fake model.

**Tech Stack:** Kotlin, Android Storage Access Framework, JUnit/Robolectric, Android NDK/CMake, Alibaba MNN 0.8.3 app source.

**Spec:** `docs/superpowers/specs/2026-11-21-mnn-local-format-conversion-design.md`

## Global Constraints

- Conversion stays on-device and never uploads model weights.
- Existing MNN folder import and ADB import paths remain available.
- A valid MNN model folder is identified by root `config.json` and required runtime model assets.
- Conversion output is written to a staging folder and only published after validation.
- The original source files are never deleted or overwritten.
- Unsupported formats, architectures, quantization types, or missing templates are reported explicitly.
- The initial scope is text-only LLM; no multimodal conversion claims.
- Keep application ID `com.alibaba.mnnllm.android.localimport`, version name `0.8.3-localimport`, version code `831`, arm64-v8a only.

## Review Focus

- A ZIP containing `../` path traversal entries or absolute paths must never write outside the staging folder.
- A ZIP bomb or excessive entry count must fail with a clear error before consuming unbounded storage.
- A directory with a stray `config.json` but no MNN runtime files must not be accepted as a runnable model.
- A split Safetensors folder with an index but missing shards must not be marked ready for conversion.
- A GGUF file with a truncated or malformed header must be rejected without crashing or reading the entire file into memory.

---

### Task 1: Model format inspection and metadata

**Files:**
- Create: `.apkforge/mnn-chat-local-import/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelFormatInspector.kt`
- Modify: `.apkforge/mnn-chat-local-import/apply_patch.py`
- Test: `.apkforge/mnn-chat-local-import/tests/LocalModelFormatInspectorTest.kt`

**Interfaces:**
- Produces `LocalModelFormatInspector.inspectFile(name: String, header: ByteArray): Detection`
- Produces `LocalModelFormatInspector.inspectDirectory(fileNames: List<String>): Detection`
- Produces `LocalModelFormatInspector.inspectTemplate(fileNames: Set<String>): TemplateCheck`
- Produces stable enum values for MNN, GGUF, SAFETENSORS, ZIP, ONNX, TFLITE, PYTORCH, UNKNOWN.

- [ ] Write tests for GGUF magic, common extensions, valid/invalid MNN directory signatures, Safetensors folder and incomplete-shard detection.
- [ ] Run tests and confirm each missing behavior fails for its expected assertion.
- [ ] Implement the pure Kotlin inspector without reading whole model files.
- [ ] Re-run unit tests and confirm expected classifications.
- [ ] Add the inspector source to the build patch package.

### Task 2: Safe ZIP intake and expanded SAF import flow

**Files:**
- Create: `.apkforge/mnn-chat-local-import/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelArchiveImporter.kt`
- Modify: `.apkforge/mnn-chat-local-import/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelFolderImporter.kt`
- Modify: `.apkforge/mnn-chat-local-import/apply_patch.py`
- Modify: `apps/Android/MnnLlmChat/app/src/main/java/com/alibaba/mnnllm/android/main/MainActivity.kt` (via patch)
- Modify: `apps/Android/MnnLlmChat/app/src/main/res/layout/activity_main.xml` (via patch)
- Modify: `apps/Android/MnnLlmChat/app/src/main/res/values*/strings.xml` (via patch)
- Test: `.apkforge/mnn-chat-local-import/tests/LocalModelArchiveImporterTest.kt`

**Interfaces:**
- `LocalModelArchiveImporter.importMnnZip(context: Context, input: InputStream, displayName: String): File`
- Existing `LocalModelFolderImporter.importFromTree(context, treeUri)` remains compatible and uses validated MNN runtime signatures.
- MainActivity adds a single-file picker for GGUF/ZIP/other recognized files and retains SAF folder selection.

- [ ] Write tests for safe extraction, path traversal rejection, entry/count/size limits, corrupt ZIP rejection, and MNN-only promotion.
- [ ] Run tests and confirm the unsafe archive and malformed archive tests fail before implementation.
- [ ] Implement bounded streaming ZIP extraction to app-private staging storage; reject absolute, parent-traversal, duplicate and symlink-like entries.
- [ ] Implement model-file pick flow and user-visible format diagnostics in Chinese.
- [ ] Re-run targeted tests and the existing app unit-test suite.
- [ ] Add source and resource changes to the patch script.

### Task 3: On-device conversion capability gate

**Files:**
- Create: `.apkforge/mnn-chat-local-import/src/main/java/com/alibaba/mnnllm/android/modelist/LocalModelConversionManager.kt`
- Create: `.apkforge/mnn-chat-local-import/src/test/java/com/alibaba/mnnllm/android/modelist/LocalModelConversionManagerTest.kt`
- Modify: `.apkforge/mnn-chat-local-import/apply_patch.py`
- Modify: `.github/workflows/mnn-chat-file-import-build.yml`

**Interfaces:**
- `LocalModelConversionManager.inspectConversion(source: Detection, template: TemplateCheck): ConversionReadiness`
- `LocalModelConversionManager.convert(source: File, template: File, output: File, progress: (Int, String) -> Unit): ConversionResult`
- ConversionResult must distinguish SUCCESS, UNSUPPORTED_ARCHITECTURE, UNSUPPORTED_QUANTIZATION, MISSING_TEMPLATE, MISSING_NATIVE_BACKEND, INVALID_SOURCE and IO_ERROR.

- [ ] Write tests for missing template, missing backend, unsupported architecture, and output-validation failure.
- [ ] Verify tests fail before implementing the manager.
- [ ] Implement capability detection and a strict result contract; never mark a source file as an MNN output.
- [ ] Inspect the official MNN converter build graph and test whether the current NDK build can link its JSON-to-MNN converter API into the app shared library without replacing the existing inference runtime.
- [ ] If a native backend and a real source-to-MNN mapping implementation can be compiled and exercised with a fixture, expose only that verified architecture/quantization subset.
- [ ] If no real backend can be built and validated in this build, ship an explicit unsupported reason rather than a fake converter; record the exact missing native component as a release limitation.
- [ ] Run the manager tests and full app tests.

### Task 4: Build and artifact verification

**Files:**
- Modify: `.github/workflows/mnn-chat-file-import-build.yml`
- Verify: branch workflow run and uploaded artifact.

- [ ] Trigger the arm64 Standard Debug build from the feature branch.
- [ ] Confirm Gradle `assembleStandardDebug` succeeds.
- [ ] Confirm the APK exists, is non-empty, contains arm64 native libraries, and has application ID `com.alibaba.mnnllm.android.localimport`.
- [ ] Record artifact ID, SHA-256, APK size and remaining conversion limitations.
- [ ] Do not merge the feature branch into `main`.

## Verification Commands

- Unit tests: `./gradlew testStandardDebugUnitTest`
- APK build: `./gradlew assembleStandardDebug`
- Package check: `aapt dump badging app-standard-debug.apk`
- APK archive check: `unzip -t app-standard-debug.apk`

## Implementation Ledger

- Base branch: `mnn-chat-local-file-import` (isolated from `main`).
- Ruling: preserve the existing APK's original MNN runtime and app ID fork — avoids colliding with the official installation.
- Ruling: conversion is success only when the app actually transforms tensor weights and validates a model directory — prevents false-positive model imports.
