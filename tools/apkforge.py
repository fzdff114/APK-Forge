#!/usr/bin/env python3
"""APK Forge repository-side Android project tools. Standard library only."""
from __future__ import annotations
import argparse, hashlib, json, os, re, shlex, shutil, subprocess, sys, time, zipfile
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

IGNORED = {".git", ".gradle", ".idea", ".kotlin", "build", ".apkforge", "captures", "node_modules"}
TEXT_EXT = {".kt", ".java", ".kts", ".gradle", ".xml", ".json", ".toml", ".properties", ".pro", ".txt", ".md", ".yml", ".yaml"}
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


@dataclass
class Issue:
    code: str
    severity: str
    title: str
    detail: str
    file: str | None = None


@dataclass
class Report:
    project_root: str
    file_count: int
    android_modules: list[str]
    issues: list[Issue]

    @property
    def errors(self): return sum(x.severity == "ERROR" for x in self.issues)
    @property
    def warnings(self): return sum(x.severity == "WARNING" for x in self.issues)
    @property
    def passes(self): return sum(x.severity == "PASS" for x in self.issues)

    def to_dict(self):
        return {
            "project_root": self.project_root,
            "file_count": self.file_count,
            "android_modules": self.android_modules,
            "summary": {"errors": self.errors, "warnings": self.warnings, "passes": self.passes},
            "issues": [asdict(x) for x in self.issues],
        }


def walk_files(root: Path):
    for current, dirs, names in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d not in IGNORED)
        base = Path(current)
        for name in names:
            path = base / name
            if not path.is_symlink():
                yield path


def find_root(path: Path) -> Path:
    path = path.expanduser().resolve()
    if not path.is_dir():
        raise ValueError(f"Project directory does not exist: {path}")
    found = []
    for current, dirs, names in os.walk(path):
        depth = len(Path(current).relative_to(path).parts)
        dirs[:] = [d for d in dirs if d not in IGNORED] if depth < 5 else []
        if "settings.gradle" in names or "settings.gradle.kts" in names:
            found.append((depth, Path(current)))
    return sorted(found, key=lambda x: x[0])[0][1].resolve() if found else path


def read(path: Path, limit=500_000):
    try:
        with path.open("r", encoding="utf-8", errors="replace") as stream:
            return stream.read(limit)
    except OSError:
        return ""


def rel(path: Path, root: Path):
    return path.relative_to(root).as_posix()


def get_modules(root: Path, settings: str):
    names = []
    for group in re.findall(r"""include\s*\(([^)]*)\)""", settings, re.S):
        names.extend(re.findall(r"""["']:([^"']+)["']""", group))
    modules = [root.joinpath(*name.split(":")) for name in names]
    if not modules:
        modules = [p.parent for p in walk_files(root) if p.name in ("build.gradle", "build.gradle.kts")]
    return sorted({p.resolve() for p in modules if p.is_dir()})


def catalog_has_alias(alias: str, catalog: str):
    plain = alias.removeprefix("libs.")
    candidates = {
        plain, plain.replace(".", "-"), plain.replace(".", "_"),
        plain.replace("-", "."), plain.replace("_", "."),
    }
    return any(item in catalog for item in candidates)


def scan_project(path: Path) -> Report:
    root = find_root(path)
    files = list(walk_files(root))
    issues: list[Issue] = []
    settings = next((root / n for n in ("settings.gradle.kts", "settings.gradle") if (root / n).is_file()), None)
    settings_text = read(settings) if settings else ""
    if settings:
        issues.append(Issue("PROJECT_SCAN", "PASS", "Gradle project entry found", "Gradle settings file exists.", rel(settings, root)))
    else:
        issues.append(Issue("PROJECT_SCAN", "ERROR", "Missing Gradle project entry",
                            "No settings.gradle or settings.gradle.kts was found."))

    build_files = [p for p in files if p.name in ("build.gradle", "build.gradle.kts")]
    modules = get_modules(root, settings_text)
    android_modules = []
    catalog_path = root / "gradle" / "libs.versions.toml"
    catalog = read(catalog_path) if catalog_path.is_file() else ""

    for module in modules:
        build = next((module / n for n in ("build.gradle.kts", "build.gradle") if (module / n).is_file()), None)
        if not build:
            continue
        body = read(build)
        app_plugin = (
            "com.android.application" in body
            or re.search(r"alias\s*\(\s*libs\.plugins\.[\w.]*android[\w.]*application", body, re.I) is not None
        )
        library_plugin = (
            "com.android.library" in body
            or re.search(r"alias\s*\(\s*libs\.plugins\.[\w.]*android[\w.]*library", body, re.I) is not None
        )
        if not app_plugin and not library_plugin:
            continue
        module_name = rel(module, root) or "."
        android_modules.append(module_name)
        if app_plugin:
            issues.append(Issue("GRADLE_REPAIR", "PASS", "Android application module detected",
                                "Android application plugin declaration found.", rel(build, root)))
        if app_plugin and "namespace" not in body:
            issues.append(Issue("GRADLE_REPAIR", "WARNING", "Namespace not detected",
                                "No literal namespace was found; check convention plugins or variables.", rel(build, root)))
        if app_plugin and not re.search(r"compileSdk(?:Version)?\s*(?:=\s*)?\d+", body):
            issues.append(Issue("SDK_COMPATIBILITY", "WARNING", "compileSdk not statically detected",
                                "compileSdk may be supplied by a variable or convention plugin.", rel(build, root)))

        aliases = set(re.findall(r"\blibs(?:\.[A-Za-z_][A-Za-z0-9_-]*)+", body))
        if aliases and not catalog_path.is_file():
            issues.append(Issue("DEPENDENCY_CHECK", "ERROR", "Version catalog missing",
                                "The module uses libs.* aliases but gradle/libs.versions.toml is missing.", rel(build, root)))
        elif aliases:
            missing = sorted(a for a in aliases if not catalog_has_alias(a, catalog))
            if missing:
                issues.append(Issue("DEPENDENCY_CHECK", "WARNING", "Review version-catalog aliases",
                                    "Could not match aliases textually in libs.versions.toml: " + ", ".join(missing),
                                    rel(build, root)))
        for ref in re.findall(r"""project\s*\(\s*["']:(.*?)["']\s*\)""", body):
            if not root.joinpath(*ref.split(":")).is_dir():
                issues.append(Issue("DEPENDENCY_CHECK", "ERROR", "Referenced project module missing",
                                    f'Build script references project(":{ref}") but the directory is absent.', rel(build, root)))

    if not build_files:
        issues.append(Issue("GRADLE_REPAIR", "ERROR", "Gradle build scripts missing",
                            "No build.gradle or build.gradle.kts exists."))
    elif not android_modules:
        issues.append(Issue("GRADLE_REPAIR", "ERROR", "No Android module detected",
                            "No module declares com.android.application or com.android.library."))

    manifests = [p for p in files if p.name == "AndroidManifest.xml" and "/src/main/" in "/" + rel(p, root)]
    if not manifests:
        issues.append(Issue("MANIFEST_REPAIR", "ERROR", "Main Manifest missing",
                            "No module src/main/AndroidManifest.xml was found."))
    for manifest in manifests:
        try:
            xml_root = ET.parse(manifest).getroot()
            issues.append(Issue("MANIFEST_REPAIR", "PASS", "Manifest XML parsed",
                                "AndroidManifest.xml is well-formed.", rel(manifest, root)))
            for component in xml_root.iter():
                if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
                    continue
                if any(child.tag == "intent-filter" for child in component):
                    if component.attrib.get(ANDROID_NS + "exported") is None:
                        comp_name = component.attrib.get(ANDROID_NS + "name", "(unnamed)")
                        issues.append(Issue("MANIFEST_REPAIR", "ERROR", "android:exported missing",
                                            f"{comp_name} has an intent-filter but no explicit exported value.",
                                            rel(manifest, root)))
        except ET.ParseError as exc:
            issues.append(Issue("MANIFEST_REPAIR", "ERROR", "Manifest XML invalid", str(exc), rel(manifest, root)))

    source = [p for p in files if p.suffix.lower() in (".kt", ".java") and "/src/main/" in "/" + rel(p, root)]
    if source:
        issues.append(Issue("SOURCE_COMPLETION", "PASS", "Main source files found",
                            f"Found {len(source)} Kotlin/Java source files."))
        empty = [rel(p, root) for p in source if p.stat().st_size == 0]
        if empty:
            issues.append(Issue("SOURCE_COMPLETION", "WARNING", "Empty source files found",
                                "Empty files are not implemented features: " + ", ".join(empty[:20])))
    else:
        issues.append(Issue("SOURCE_COMPLETION", "ERROR", "Main source files missing",
                            "No Kotlin/Java files found under a main source set."))

    wrapper_properties = root / "gradle/wrapper/gradle-wrapper.properties"
    wrapper_jar = root / "gradle/wrapper/gradle-wrapper.jar"
    wrapper_script = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    missing = [label for label, path in (
        ("gradle-wrapper.properties", wrapper_properties),
        ("gradle-wrapper.jar", wrapper_jar),
        (wrapper_script.name, wrapper_script),
    ) if not path.is_file()]
    if missing:
        issues.append(Issue("GRADLE_REPAIR", "WARNING", "Gradle Wrapper incomplete",
                            "Missing: " + ", ".join(missing) + ". CI may use an installed Gradle distribution."))
    else:
        match = re.search(r"distributionUrl=.*?/gradle-([0-9.]+)-(?:bin|all)\.zip", read(wrapper_properties))
        version = match.group(1) if match else "unknown"
        issues.append(Issue("GRADLE_REPAIR", "PASS", "Gradle Wrapper files found",
                            f"Configured Gradle version: {version}.", rel(wrapper_properties, root)))

    if (root / "local.properties").is_file():
        issues.append(Issue("PROJECT_SCAN", "WARNING", "local.properties present",
                            "It may contain another computer's SDK path; do not commit machine-specific paths.",
                            "local.properties"))
    if not any(x.severity == "ERROR" for x in issues):
        issues.append(Issue("BUILD_VALIDATION", "PASS", "No static blocking errors found",
                            "A real Gradle build is still required; static checks cannot prove compilation."))
    return Report(str(root), len(files), android_modules, issues)


def render_report(report: Report):
    lines = [
        "# APK Forge project scan", "",
        f"- Root: {report.project_root}",
        f"- Files: {report.file_count}",
        f"- Android modules: {', '.join(report.android_modules) or '(not detected)'}",
        f"- Errors: {report.errors}; warnings: {report.warnings}; passes: {report.passes}",
        "", "## Findings", "",
    ]
    for item in report.issues:
        loc = f" ({item.file})" if item.file else ""
        lines += [f"- [{item.severity}] {item.code}: {item.title}{loc}", f"  {item.detail}"]
    return "\n".join(lines) + "\n"


def collect_context(root: Path, max_chars=100_000, max_files=70):
    priority_names = {"settings.gradle.kts", "settings.gradle", "build.gradle.kts",
                      "build.gradle", "gradle.properties", "AndroidManifest.xml"}
    candidates = [
        p for p in walk_files(root)
        if p.suffix.lower() in TEXT_EXT and p.name != "local.properties" and p.stat().st_size <= 200_000
    ]
    candidates.sort(key=lambda p: (
        0 if p.name in priority_names else
        1 if p.name == "libs.versions.toml" else
        2 if p.suffix.lower() in (".kt", ".java") else 3,
        rel(p, root)
    ))
    blocks, used = [], 0
    for path in candidates[:max_files]:
        remaining = max_chars - used
        if remaining < 1000:
            break
        body = read(path, min(30_000, remaining))
        block = f"\n--- FILE: {rel(path, root)} ---\n{body}\n"
        blocks.append(block)
        used += len(block)
    return "".join(blocks)


def make_prompt(report: Report):
    root = Path(report.project_root)
    return f"""# APK Forge AI coding task

You are an Android engineer operating in this repository. The user wants a real Android application and an installable APK.

## Mandatory workflow
1. Read AGENTS.md and tools/README.md before editing.
2. Inspect the request and existing project. Do not assume the app is a blank template.
3. Run: python3 tools/apkforge.py scan --project "{root}" --format markdown
4. Implement real requested features. Do not create empty placeholder classes, fake methods, TODO-only files, or dummy resources to silence the scanner.
5. Preserve existing package ID, UI, working behavior and assets unless the user explicitly requests a change.
6. Run the scanner again and fix evidence-based blockers.
7. Review the Gradle scripts before executing them. Gradle scripts are executable project code.
8. After user approval, run: python3 tools/apkforge.py build --project "{root}" --confirm-gradle-code
9. Verify the produced APK with: python3 tools/apkforge.py verify --apk PATH_TO_APK
10. Report the APK path, SHA-256, build outcome, and unresolved issues. Never claim success without both a successful build and APK verification.

## Current static scan
{render_report(report)}

## Existing project context
{collect_context(root)}

## Rules
- Return complete changed files or precise patches.
- Use real, compatible Maven dependencies and Android APIs.
- Never place credentials, signing keys, local.properties or machine-specific paths in source control.
- Do not execute arbitrary AI-generated shell scripts.
- If the project is incomplete, explain which files/features are genuinely missing; do not invent that a feature exists.
"""


def scaffold(name: str, package_name: str, output: Path, overwrite=False):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9 _-]{0,39}", name):
        raise ValueError("App name must be 1-40 simple Latin letters, digits, spaces, _ or -.")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*){1,7}", package_name):
        raise ValueError("Invalid dotted Android application ID.")
    output = output.expanduser().resolve()
    if output.exists() and any(output.iterdir()) and not overwrite:
        raise FileExistsError(f"Output directory is not empty: {output}. Use --overwrite only intentionally.")
    output.mkdir(parents=True, exist_ok=True)
    package_path = "/".join(package_name.split("."))
    files = {
        "settings.gradle.kts": """pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "APP_NAME"
include(":app")
""".replace("APP_NAME", name),
        "build.gradle.kts": """plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
""",
        "gradle.properties": """org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.caching=true
android.useAndroidX=true
kotlin.compiler.execution.strategy=in-process
kotlin.code.style=official
""",
        "app/build.gradle.kts": f"""plugins {{
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}}
android {{
    namespace = "{package_name}"
    compileSdk = 35
    defaultConfig {{
        applicationId = "{package_name}"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }}
    buildFeatures {{ compose = true }}
    compileOptions {{
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}
    kotlinOptions {{ jvmTarget = "17" }}
}}
dependencies {{
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}}
""",
        "app/src/main/AndroidManifest.xml": """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:allowBackup="false" android:label="APP_NAME"
        android:supportsRtl="true" android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".replace("APP_NAME", name),
        f"app/src/main/java/{package_path}/MainActivity.kt": f"""package {package_name}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {{
    override fun onCreate(savedInstanceState: Bundle?) {{
        super.onCreate(savedInstanceState)
        setContent {{
            MaterialTheme {{
                Surface(modifier = Modifier.fillMaxSize()) {{
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {{
                        Text("{name}")
                        Text("Generated by APK Forge scaffold")
                    }}
                }}
            }}
        }}
    }}
}}
""",
        "README.md": f"""# {name}

Android project scaffold generated by APK Forge repository tools.

- Package: {package_name}
- compileSdk: 35
- minSdk: 26
- Android Gradle Plugin: 8.9.2
- Kotlin: 2.1.20
- JDK: 17

This is a real, minimal Compose entry point. Implement the requested app features before treating the project as complete.
""",
    }
    for relname, content in files.items():
        target = output / relname
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and not overwrite:
            raise FileExistsError(f"Refusing to overwrite {target}")
        target.write_text(content, encoding="utf-8")
    return output


def verify_apk(apk: Path):
    apk = apk.expanduser().resolve()
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        raise FileNotFoundError(f"APK file not found: {apk}")
    with zipfile.ZipFile(apk) as archive:
        bad = archive.testzip()
        if bad:
            raise RuntimeError(f"APK ZIP integrity error: {bad}")
        names = set(archive.namelist())
        if "AndroidManifest.xml" not in names:
            raise RuntimeError("APK has no AndroidManifest.xml")
        dex = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", Path(n).name))
        if not dex:
            raise RuntimeError("APK has no classes*.dex file")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return {"apk": str(apk), "size_bytes": apk.stat().st_size, "sha256": digest,
            "zip_integrity": "ok", "manifest_present": True, "dex_files": dex, "verified": True}


def apply_termux_build_config(root: Path):
    """Temporarily point Gradle at Termux's Android-native SDK/aapt2 binaries."""
    prefix = os.environ.get("PREFIX", "")
    if "com.termux" not in prefix and "/data/data/com.termux/" not in prefix:
        return None
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_value:
        raise RuntimeError("Termux detected but ANDROID_HOME/ANDROID_SDK_ROOT is not set.")
    sdk = Path(sdk_value).expanduser().resolve()
    build_tools = sdk / "build-tools"
    candidates = [p for p in build_tools.iterdir() if p.is_dir()] if build_tools.is_dir() else []
    candidates.sort(key=lambda p: tuple(
        int(part) if part.isdigit() else part for part in re.split(r"[.-]", p.name)
    ))
    aapt2 = next(
        (p / "aapt2" for p in reversed(candidates)
         if (p / "aapt2").is_file() and os.access(p / "aapt2", os.X_OK)),
        None,
    )
    if aapt2 is None:
        located = shutil.which("aapt2")
        aapt2 = Path(located).resolve() if located else None
    if aapt2 is None or not aapt2.is_file():
        raise RuntimeError(
            "Termux Android SDK has no executable native aapt2. Install the Termux android-sdk package."
        )

    backups: dict[Path, str | None] = {}

    def set_properties(path: Path, values: dict[str, str]):
        backups[path] = path.read_text(encoding="utf-8") if path.is_file() else None
        lines = (backups[path] or "").splitlines()
        keys = set(values)
        lines = [
            line for line in lines
            if not any(line.lstrip().startswith(key + "=") for key in keys)
        ]
        lines.extend(f"{key}={value}" for key, value in values.items())
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    try:
        set_properties(root / "local.properties", {
            "sdk.dir": str(sdk),
        })
        set_properties(root / "gradle.properties", {
            "android.aapt2FromMavenOverride": str(aapt2),
            "android.overridePathCheck": "true",
            "org.gradle.daemon": "false",
            "org.gradle.parallel": "false",
            "org.gradle.jvmargs": "-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8",
            "kotlin.compiler.execution.strategy": "in-process",
        })
    except Exception:
        restore_termux_build_config(backups)
        raise
    return backups


def restore_termux_build_config(backups: dict[Path, str | None]):
    for path, previous in backups.items():
        if previous is None:
            path.unlink(missing_ok=True)
        else:
            path.write_text(previous, encoding="utf-8")


def build(project: Path, timeout: int, confirm: bool, allow_errors: bool):
    if not confirm:
        raise PermissionError("Gradle scripts execute project code. Review the project and pass --confirm-gradle-code.")
    root = find_root(project)
    report = scan_project(root)
    if report.errors and not allow_errors:
        raise RuntimeError(f"Static scan found {report.errors} error(s). Review scan output or use --allow-scan-errors after review.")
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if wrapper.is_file():
        command = [str(wrapper), "--no-daemon", "--stacktrace", "assembleDebug"] if os.name == "nt" or os.access(wrapper, os.X_OK) else ["bash", str(wrapper), "--no-daemon", "--stacktrace", "assembleDebug"]
    else:
        gradle = shutil.which("gradle")
        if not gradle:
            raise FileNotFoundError("No Gradle Wrapper or installed Gradle. Add a complete Wrapper or install Gradle.")
        command = [gradle, "--no-daemon", "--stacktrace", "assembleDebug"]
    log_dir = root / ".apkforge" / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / f"build-{time.strftime('%Y%m%d-%H%M%S')}.log"
    termux_backups = None
    try:
        termux_backups = apply_termux_build_config(root)
        if termux_backups is not None:
            print("Detected Termux: using the on-device Android SDK and native aapt2.")
        print("Running:", shlex.join(command))
        print("Project:", root)
        print("Log:", log_path)
        try:
            result = subprocess.run(
                command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, errors="replace", timeout=timeout, check=False
            )
        except subprocess.TimeoutExpired as exc:
            output = exc.stdout or ""
            if isinstance(output, bytes):
                output = output.decode("utf-8", errors="replace")
            log_path.write_text(output, encoding="utf-8")
            raise TimeoutError(f"Gradle timed out after {timeout}s. Log: {log_path}") from exc
        log_path.write_text(result.stdout or "", encoding="utf-8")
        if result.returncode:
            print((result.stdout or "")[-10000:], file=sys.stderr)
            raise subprocess.CalledProcessError(result.returncode, command)
        apks = sorted(
            root.glob("**/build/outputs/apk/debug/*.apk"),
            key=lambda item: item.stat().st_mtime, reverse=True
        )
        if not apks:
            raise RuntimeError(f"Gradle returned success but no debug APK was found. Log: {log_path}")
        return {
            "project_root": str(root),
            "exit_code": result.returncode,
            "log": str(log_path),
            "apk": verify_apk(apks[0]),
        }
    finally:
        if termux_backups is not None:
            restore_termux_build_config(termux_backups)

def archive_project(project: Path, output: Path, max_mib: int):
    root = find_root(project)
    output = output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    excluded = IGNORED | {"local.properties", "gradlew", "gradlew.bat", "gradle-wrapper.jar",
                          "key.properties", ".env"}
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in walk_files(root):
            if path.name in excluded or path.suffix.lower() in {".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".log", ".iml"}:
                continue
            archive.write(path, rel(path, root))
    size = output.stat().st_size
    if not size:
        output.unlink(missing_ok=True)
        raise RuntimeError("Created an empty project archive.")
    if size > max_mib * 1024 * 1024:
        output.unlink(missing_ok=True)
        raise RuntimeError(f"Archive is {size / 1024 / 1024:.1f} MiB; limit is {max_mib} MiB.")
    return {"archive": str(output), "size_bytes": size,
            "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}


def write_result(value: str, output: str | None):
    if output:
        target = Path(output).expanduser().resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value, encoding="utf-8")
        print("Wrote:", target)
    else:
        print(value, end="")


def main():
    parser = argparse.ArgumentParser(description="Repository-side AI Android project tools; no AI API is embedded.")
    commands = parser.add_subparsers(dest="command", required=True)

    p = commands.add_parser("scaffold", help="Create a minimal Android project for an AI agent to extend.")
    p.add_argument("--name", required=True)
    p.add_argument("--package", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--overwrite", action="store_true")
    p.set_defaults(func=lambda a: (print("Created:", scaffold(a.name, a.package, Path(a.output), a.overwrite)) or 0))

    p = commands.add_parser("scan", help="Inspect project structure and likely build blockers.")
    p.add_argument("--project", default=".")
    p.add_argument("--format", choices=("markdown", "json"), default="markdown")
    p.add_argument("--output")
    def do_scan(a):
        report = scan_project(Path(a.project))
        value = json.dumps(report.to_dict(), ensure_ascii=False, indent=2) + "\n" if a.format == "json" else render_report(report)
        write_result(value, a.output)
        return 1 if report.errors else 0
    p.set_defaults(func=do_scan)

    p = commands.add_parser("prompt", help="Create a context-aware repair task for an AI coding agent.")
    p.add_argument("--project", default=".")
    p.add_argument("--output")
    def do_prompt(a):
        value = make_prompt(scan_project(Path(a.project)))
        write_result(value, a.output)
        return 0
    p.set_defaults(func=do_prompt)

    p = commands.add_parser("build", help="Run assembleDebug, save logs, locate and verify the APK.")
    p.add_argument("--project", default=".")
    p.add_argument("--timeout", type=int, default=3600)
    p.add_argument("--confirm-gradle-code", action="store_true")
    p.add_argument("--allow-scan-errors", action="store_true")
    p.add_argument("--output")
    def do_build(a):
        value = json.dumps(build(Path(a.project), a.timeout, a.confirm_gradle_code, a.allow_scan_errors),
                           ensure_ascii=False, indent=2) + "\n"
        write_result(value, a.output)
        return 0
    p.set_defaults(func=do_build)

    p = commands.add_parser("verify", help="Verify APK ZIP integrity, manifest, dex entries and SHA-256.")
    p.add_argument("--apk", required=True)
    p.add_argument("--output")
    def do_verify(a):
        value = json.dumps(verify_apk(Path(a.apk)), ensure_ascii=False, indent=2) + "\n"
        write_result(value, a.output)
        return 0
    p.set_defaults(func=do_verify)

    p = commands.add_parser("archive", help="Create a filtered source archive for remote builds.")
    p.add_argument("--project", default=".")
    p.add_argument("--output", required=True)
    p.add_argument("--max-mib", type=int, default=12)
    def do_archive(a):
        value = json.dumps(archive_project(Path(a.project), Path(a.output), a.max_mib),
                           ensure_ascii=False, indent=2) + "\n"
        print(value, end="")
        return 0
    p.set_defaults(func=do_archive)

    args = parser.parse_args()
    try:
        return int(args.func(args))
    except subprocess.CalledProcessError as error:
        print(f"BUILD FAILED (exit {error.returncode}). Inspect the log path printed above.", file=sys.stderr)
        return error.returncode or 1
    except Exception as error:
        print(f"APK Forge: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
