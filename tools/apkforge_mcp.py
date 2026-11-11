#!/usr/bin/env python3
"""Local-first MCP tool server for APK Forge.

The server exposes real project filesystem, analysis, build and APK verification
operations to an AI coding agent. It does not call an AI service and never needs
GitHub Actions to build an APK.
"""
from __future__ import annotations

import argparse
import contextlib
import fnmatch
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

import apkforge

PROTOCOL_VERSION = "2025-06-18"
MAX_FILE_BYTES = 1_000_000
MAX_SEARCH_RESULTS = 100
SENSITIVE_NAMES = {
    "local.properties", "key.properties", ".env", ".env.local",
    "credentials.json", "google-services.json",
}
SENSITIVE_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}


def is_sensitive(path: Path) -> bool:
    return path.name.lower() in SENSITIVE_NAMES or path.suffix.lower() in SENSITIVE_SUFFIXES


class Workspace:
    """A strict filesystem sandbox for one generated Android project workspace."""

    def __init__(self, root: Path):
        self.root = root.expanduser().resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def resolve(self, relative: str, *, allow_root: bool = False) -> Path:
        if not isinstance(relative, str) or not relative.strip():
            raise ValueError("A non-empty workspace-relative path is required.")
        candidate = Path(relative)
        if candidate.is_absolute():
            raise ValueError("Absolute paths are not accepted; use a workspace-relative path.")
        target = (self.root / candidate).resolve()
        if target != self.root and self.root not in target.parents:
            raise PermissionError("Path escapes the configured APK Forge workspace.")
        if target == self.root and not allow_root:
            raise PermissionError("The workspace root itself is not a file target.")
        # Reject symlinks in every existing component, including links that resolve
        # back inside the workspace. This keeps agent writes predictable.
        current = self.root
        for part in candidate.parts:
            if part in ("", ".", ".."):
                if part == "..":
                    raise PermissionError("Parent-directory traversal is not allowed.")
                continue
            current = current / part
            if current.is_symlink():
                raise PermissionError("Symbolic links are not allowed in the agent workspace.")
        return target

    def list_files(self, relative: str = ".", limit: int = 500) -> list[dict[str, Any]]:
        root = self.resolve(relative, allow_root=True)
        if not root.is_dir():
            raise NotADirectoryError(relative)
        results = []
        for path in sorted(root.rglob("*")):
            if any(part in apkforge.IGNORED for part in path.relative_to(self.root).parts):
                continue
            if path.is_symlink() or is_sensitive(path):
                continue
            if len(results) >= limit:
                break
            results.append({
                "path": path.relative_to(self.root).as_posix(),
                "type": "directory" if path.is_dir() else "file",
                "size_bytes": path.stat().st_size if path.is_file() else 0,
            })
        return results

    def read_file(self, relative: str) -> str:
        path = self.resolve(relative)
        if is_sensitive(path):
            raise PermissionError("Reading credentials, local SDK paths or signing keys is blocked.")
        if not path.is_file():
            raise FileNotFoundError(relative)
        size = path.stat().st_size
        if size > MAX_FILE_BYTES:
            raise ValueError(f"File is {size} bytes; read_file limit is {MAX_FILE_BYTES} bytes.")
        try:
            return path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise ValueError("File is not UTF-8 text. Binary files are not exposed to the AI agent.") from exc

    def write_file(self, relative: str, content: str) -> dict[str, Any]:
        path = self.resolve(relative)
        if is_sensitive(path):
            raise PermissionError("Writing credentials or signing-key files through the agent is blocked.")
        if not isinstance(content, str):
            raise TypeError("content must be a string.")
        data = content.encode("utf-8")
        if len(data) > MAX_FILE_BYTES:
            raise ValueError(f"File is {len(data)} bytes; write_file limit is {MAX_FILE_BYTES} bytes.")
        path.parent.mkdir(parents=True, exist_ok=True)
        # Atomic replacement prevents partially written source files if the agent
        # process is interrupted.
        fd, temporary = tempfile.mkstemp(prefix=".apkforge-", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        return {"path": path.relative_to(self.root).as_posix(), "size_bytes": len(data), "written": True}

    def edit_file(self, relative: str, old_text: str, new_text: str, replace_all: bool = False) -> dict[str, Any]:
        path = self.resolve(relative)
        if is_sensitive(path):
            raise PermissionError("Editing credentials or signing-key files is blocked.")
        if not path.is_file():
            raise FileNotFoundError(relative)
        original = self.read_file(relative)
        count = original.count(old_text)
        if not old_text:
            raise ValueError("old_text must not be empty.")
        if count == 0:
            raise ValueError("old_text was not found; no changes were made.")
        if count > 1 and not replace_all:
            raise ValueError(f"old_text occurs {count} times; provide a more specific match or set replace_all=true.")
        updated = original.replace(old_text, new_text, -1 if replace_all else 1)
        result = self.write_file(relative, updated)
        result["replacements"] = count if replace_all else 1
        return result

    def search(self, query: str, glob: str = "**/*", limit: int = MAX_SEARCH_RESULTS) -> list[dict[str, Any]]:
        if not query:
            raise ValueError("query must not be empty.")
        matches = []
        for path in sorted(self.root.rglob("*")):
            if not path.is_file() or path.is_symlink() or is_sensitive(path):
                continue
            relative = path.relative_to(self.root).as_posix()
            if any(part in apkforge.IGNORED for part in Path(relative).parts):
                continue
            if not fnmatch.fnmatch(relative, glob):
                continue
            if path.stat().st_size > MAX_FILE_BYTES:
                continue
            try:
                lines = path.read_text(encoding="utf-8").splitlines()
            except (OSError, UnicodeDecodeError):
                continue
            for number, line in enumerate(lines, 1):
                if query.casefold() in line.casefold():
                    matches.append({"path": relative, "line": number, "text": line[:1000]})
                    if len(matches) >= limit:
                        return matches
        return matches


TOOLS = [
    {
        "name": "workspace_info",
        "description": "Show the local workspace path and available Java, Gradle and Android SDK tools. No GitHub account or cloud build is used.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "create_project",
        "description": "Create a real Kotlin + Jetpack Compose Android project scaffold in the workspace. The AI agent must then implement the requested app features by writing and editing project files; the scaffold alone is not a completed app.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Human-readable Android app name."},
                "package": {"type": "string", "description": "Dotted Android application ID, e.g. com.example.notes."},
                "path": {"type": "string", "description": "Workspace-relative destination, e.g. projects/notes."},
                "overwrite": {"type": "boolean", "default": False},
            },
            "required": ["name", "package", "path"],
            "additionalProperties": False,
        },
    },
    {
        "name": "list_files",
        "description": "List files and directories in the workspace or a project folder. Sensitive files and build caches are hidden.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "default": "."},
                "limit": {"type": "integer", "minimum": 1, "maximum": 500, "default": 500},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "read_file",
        "description": "Read one UTF-8 project source/configuration file. Credentials, local.properties, signing keys and binary files are blocked.",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
            "additionalProperties": False,
        },
    },
    {
        "name": "write_file",
        "description": "Create or atomically replace one UTF-8 source/configuration file. Paths are sandboxed inside the workspace.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "content": {"type": "string"},
            },
            "required": ["path", "content"],
            "additionalProperties": False,
        },
    },
    {
        "name": "edit_file",
        "description": "Replace an exact text fragment in one project file. Refuses ambiguous multi-match edits unless replace_all is true.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "old_text": {"type": "string"},
                "new_text": {"type": "string"},
                "replace_all": {"type": "boolean", "default": False},
            },
            "required": ["path", "old_text", "new_text"],
            "additionalProperties": False,
        },
    },
    {
        "name": "delete_file",
        "description": "Delete one workspace source/configuration file. Requires confirm=true. Credential, local SDK and signing-key files are protected.",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "confirm": {"type": "boolean"}},
            "required": ["path", "confirm"],
            "additionalProperties": False,
        },
    },
    {
        "name": "search_code",
        "description": "Search source/configuration text within the workspace. Returns file paths, line numbers and matching lines.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "glob": {"type": "string", "default": "**/*"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 100, "default": 100},
            },
            "required": ["query"],
            "additionalProperties": False,
        },
    },
    {
        "name": "analyze_project",
        "description": "Run APK Forge's real static Android project scanner. Reports Gradle modules, plugin aliases, Manifest/exported issues, source files, Wrapper completeness and build blockers.",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string", "description": "Workspace-relative project root."}},
            "required": ["path"],
            "additionalProperties": False,
        },
    },
    {
        "name": "build_apk",
        "description": "Build the Android project locally with its Gradle Wrapper or installed Gradle, then verify the resulting APK. This does not upload source to GitHub or use GitHub Actions. Building executes project Gradle code, so the caller must explicitly set allow_project_code=true after reviewing the build scripts.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "allow_project_code": {"type": "boolean", "description": "Explicit confirmation that project Gradle scripts have been reviewed and may execute."},
                "allow_scan_errors": {"type": "boolean", "default": False},
                "timeout_seconds": {"type": "integer", "minimum": 60, "maximum": 7200, "default": 3600},
            },
            "required": ["path", "allow_project_code"],
            "additionalProperties": False,
        },
    },
    {
        "name": "read_build_log",
        "description": "Read the tail of an APK Forge build log so the AI agent can locate and repair the actual Gradle/compiler error.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Workspace-relative log file path."},
                "max_lines": {"type": "integer", "minimum": 1, "maximum": 500, "default": 200},
            },
            "required": ["path"],
            "additionalProperties": False,
        },
    },
    {
        "name": "verify_apk",
        "description": "Verify APK ZIP integrity, AndroidManifest.xml, classes*.dex and SHA-256 for a locally built APK.",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string", "description": "Workspace-relative APK path."}},
            "required": ["path"],
            "additionalProperties": False,
        },
    },
    {
        "name": "export_apk",
        "description": "Copy a verified APK to the configured export folder. In Termux this defaults to ~/storage/shared/Download/APKForge so the APK is visible in Android Downloads; on other hosts it defaults to workspace/exports. Does not overwrite an existing file.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Workspace-relative APK path."},
                "name": {"type": "string", "description": "Optional output filename ending in .apk."},
            },
            "required": ["path"],
            "additionalProperties": False,
        },
    },
]


class ApkForgeMcpServer:
    def __init__(self, workspace: Workspace):
        self.workspace = workspace

    def call_tool(self, name: str, args: dict[str, Any]) -> Any:
        if name == "workspace_info":
            return {
                "workspace": str(self.workspace.root),
                "java": shutil.which("java"),
                "gradle": shutil.which("gradle"),
                "android_home": os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"),
                "adb": shutil.which("adb"),
                "github_required": False,
                "build_mode": "local Gradle",
            }
        if name == "create_project":
            destination = self.workspace.resolve(args["path"])
            if destination.exists() and any(destination.iterdir()) and not args.get("overwrite", False):
                raise FileExistsError(f"Destination is not empty: {args['path']}")
            apkforge.scaffold(args["name"], args["package"], destination, overwrite=args.get("overwrite", False))
            return {"created": True, "path": destination.relative_to(self.workspace.root).as_posix(),
                    "files": [x["path"] for x in self.workspace.list_files(args["path"])]}
        if name == "list_files":
            return self.workspace.list_files(args.get("path", "."), args.get("limit", 500))
        if name == "read_file":
            return {"path": args["path"], "content": self.workspace.read_file(args["path"])}
        if name == "write_file":
            return self.workspace.write_file(args["path"], args["content"])
        if name == "edit_file":
            return self.workspace.edit_file(args["path"], args["old_text"], args["new_text"], args.get("replace_all", False))
        if name == "delete_file":
            if args.get("confirm") is not True:
                raise PermissionError("Deletion refused; set confirm=true after reviewing the target file.")
            path = self.workspace.resolve(args["path"])
            if is_sensitive(path):
                raise PermissionError("Deleting credential or signing-key files through the agent is blocked.")
            if not path.is_file():
                raise FileNotFoundError(args["path"])
            path.unlink()
            return {"path": args["path"], "deleted": True}
        if name == "search_code":
            return self.workspace.search(args["query"], args.get("glob", "**/*"), args.get("limit", 100))
        if name == "analyze_project":
            root = self.workspace.resolve(args["path"])
            report = apkforge.scan_project(root)
            return report.to_dict()
        if name == "build_apk":
            if args.get("allow_project_code") is not True:
                raise PermissionError("Build refused: review the project's Gradle scripts and set allow_project_code=true.")
            root = self.workspace.resolve(args["path"])
            captured = io.StringIO()
            try:
                # apkforge.build prints status text; capture it so stdout remains a clean MCP stream.
                with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
                    result = apkforge.build(root, args.get("timeout_seconds", 3600), True, args.get("allow_scan_errors", False))
            except subprocess.CalledProcessError as exc:
                logs = sorted((root / ".apkforge" / "logs").glob("build-*.log"), key=lambda p: p.stat().st_mtime, reverse=True)
                tail = ""
                if logs:
                    tail = "\\n".join(logs[0].read_text(encoding="utf-8", errors="replace").splitlines()[-180:])
                raise RuntimeError(f"Local Gradle build failed (exit {exc.returncode}).\\n{captured.getvalue()}\\n{tail}") from exc
            result["apk"]["path"] = Path(result["apk"]["apk"]).relative_to(self.workspace.root).as_posix()
            result["apk"].pop("apk", None)
            result["log"] = Path(result["log"]).relative_to(self.workspace.root).as_posix()
            result["console_output"] = captured.getvalue()
            return result
        if name == "read_build_log":
            path = self.workspace.resolve(args["path"])
            if not path.is_file():
                raise FileNotFoundError(args["path"])
            if ".apkforge" not in path.parts or "logs" not in path.parts:
                raise PermissionError("Only files inside a project's .apkforge/logs directory can be read as build logs.")
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
            count = args.get("max_lines", 200)
            return {"path": args["path"], "total_lines": len(lines), "lines": lines[-count:]}
        if name == "verify_apk":
            path = self.workspace.resolve(args["path"])
            return apkforge.verify_apk(path)
        if name == "export_apk":
            source = self.workspace.resolve(args["path"])
            verified = apkforge.verify_apk(source)
            filename = args.get("name") or source.name
            if not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,120}\.apk", filename, re.IGNORECASE):
                raise ValueError("APK export name must contain only letters, digits, '.', '_' or '-', and end in .apk.")
            configured = os.environ.get("APKFORGE_EXPORT_DIR")
            if configured:
                export_dir = Path(configured).expanduser().resolve()
            elif "com.termux" in os.environ.get("PREFIX", ""):
                export_dir = Path.home() / "storage" / "shared" / "Download" / "APKForge"
                if not export_dir.parent.parent.exists():
                    raise RuntimeError("Termux shared storage is not mounted. Run termux-setup-storage and allow file access first.")
            else:
                export_dir = self.workspace.root / "exports"
            export_dir.mkdir(parents=True, exist_ok=True)
            destination = export_dir / filename
            if destination.exists():
                raise FileExistsError(f"Refusing to overwrite existing APK: {destination}")
            shutil.copy2(source, destination)
            exported = apkforge.verify_apk(destination)
            return {
                "exported_path": str(destination),
                "size_bytes": exported["size_bytes"],
                "sha256": exported["sha256"],
                "verified": exported["verified"],
                "source_sha256_matches": exported["sha256"] == verified["sha256"],
            }
        raise KeyError(f"Unknown APK Forge tool: {name}")


def rpc_result(message_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": message_id, "result": result}


def rpc_error(message_id: Any, code: int, message: str, data: Any = None) -> dict[str, Any]:
    error = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    return {"jsonrpc": "2.0", "id": message_id, "error": error}


def dispatch(server: ApkForgeMcpServer, message: dict[str, Any]) -> dict[str, Any] | None:
    method = message.get("method")
    params = message.get("params") or {}
    message_id = message.get("id")
    if method == "notifications/initialized" or method == "notifications/cancelled":
        return None
    if method == "initialize":
        return rpc_result(message_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "apk-forge-local-tools", "version": "0.2.0"},
            "instructions": "Use these tools to create real Android project files, analyze them, build APKs locally with Gradle, inspect build errors, and verify APK artifacts. Do not use GitHub Actions for local APK generation. Implement real features; do not stop at an empty scaffold.",
        })
    if method == "ping":
        return rpc_result(message_id, {})
    if method == "tools/list":
        return rpc_result(message_id, {"tools": TOOLS})
    if method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        try:
            result = server.call_tool(name, args)
            rendered = json.dumps(result, ensure_ascii=False, indent=2, default=str)
            return rpc_result(message_id, {"content": [{"type": "text", "text": rendered}], "isError": False})
        except Exception as exc:
            return rpc_result(message_id, {
                "content": [{"type": "text", "text": f"{type(exc).__name__}: {exc}"}],
                "isError": True,
            })
    if message_id is None:
        return None
    return rpc_error(message_id, -32601, f"Method not found: {method}")


def read_frame(stream) -> dict[str, Any] | None:
    headers: dict[str, str] = {}
    while True:
        line = stream.readline()
        if not line:
            return None if not headers else (_ for _ in ()).throw(ValueError("Unexpected EOF in MCP headers."))
        if line in (b"\r\n", b"\n"):
            break
        decoded = line.decode("ascii", errors="strict").strip()
        if ":" not in decoded:
            raise ValueError("Malformed MCP header.")
        key, value = decoded.split(":", 1)
        headers[key.lower().strip()] = value.strip()
    length_text = headers.get("content-length")
    if not length_text or not length_text.isdigit():
        raise ValueError("Missing or invalid Content-Length header.")
    length = int(length_text)
    if length < 2 or length > 8_000_000:
        raise ValueError("MCP message size is outside the accepted range.")
    payload = stream.read(length)
    if len(payload) != length:
        raise ValueError("Truncated MCP message.")
    message = json.loads(payload.decode("utf-8"))
    if not isinstance(message, dict):
        raise ValueError("MCP JSON-RPC message must be an object.")
    return message


def write_frame(stream, message: dict[str, Any]) -> None:
    payload = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    stream.write(f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii"))
    stream.write(payload)
    stream.flush()


def main() -> int:
    parser = argparse.ArgumentParser(description="APK Forge local-first MCP filesystem and Android build tools.")
    parser.add_argument("--workspace", default=os.environ.get("APKFORGE_WORKSPACE", "./apkforge-workspace"),
                        help="Workspace root for AI-generated Android projects.")
    args = parser.parse_args()
    server = ApkForgeMcpServer(Workspace(Path(args.workspace)))
    source, sink = sys.stdin.buffer, sys.stdout.buffer
    while True:
        try:
            message = read_frame(source)
            if message is None:
                return 0
            response = dispatch(server, message)
            if response is not None:
                write_frame(sink, response)
        except Exception as exc:
            # MCP stdio reserves stdout for protocol frames. Diagnostics go to stderr.
            print(f"APK Forge MCP: {type(exc).__name__}: {exc}", file=sys.stderr, flush=True)
            return 1


if __name__ == "__main__":
    raise SystemExit(main())
