import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))
import apkforge_mcp


class WorkspaceToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "workspace"
        self.workspace = apkforge_mcp.Workspace(self.root)
        self.server = apkforge_mcp.ApkForgeMcpServer(self.workspace)

    def tearDown(self):
        self.temp.cleanup()

    def test_write_read_edit_and_delete_source(self):
        written = self.workspace.write_file("app/src/main/Test.kt", "class Before\n")
        self.assertTrue(written["written"])
        self.assertEqual(self.workspace.read_file("app/src/main/Test.kt"), "class Before\n")
        edited = self.workspace.edit_file("app/src/main/Test.kt", "Before", "After")
        self.assertEqual(edited["replacements"], 1)
        self.assertEqual(self.workspace.read_file("app/src/main/Test.kt"), "class After\n")
        deleted = self.server.call_tool("delete_file", {"path": "app/src/main/Test.kt", "confirm": True})
        self.assertTrue(deleted["deleted"])
        self.assertFalse((self.root / "app/src/main/Test.kt").exists())

    def test_rejects_parent_traversal_and_absolute_paths(self):
        with self.assertRaises(PermissionError):
            self.workspace.write_file("../outside.txt", "no")
        with self.assertRaises(ValueError):
            self.workspace.read_file(str(self.root / "inside.txt"))

    def test_protects_local_sdk_paths_and_signing_keys(self):
        (self.root / "local.properties").write_text("sdk.dir=/private/sdk", encoding="utf-8")
        with self.assertRaises(PermissionError):
            self.workspace.read_file("local.properties")
        with self.assertRaises(PermissionError):
            self.workspace.write_file("release/secret.jks", "not a key")

    def test_edit_refuses_ambiguous_or_missing_match(self):
        self.workspace.write_file("A.kt", "x x")
        with self.assertRaises(ValueError):
            self.workspace.edit_file("A.kt", "x", "y")
        with self.assertRaises(ValueError):
            self.workspace.edit_file("A.kt", "missing", "y")

    def test_create_project_and_analyze(self):
        result = self.server.call_tool("create_project", {
            "name": "Notes App",
            "package": "com.example.notes",
            "path": "apps/notes",
        })
        self.assertTrue(result["created"])
        report = self.server.call_tool("analyze_project", {"path": "apps/notes"})
        self.assertEqual(report["summary"]["errors"], 0, json.dumps(report, indent=2))

    def test_build_requires_explicit_project_code_approval(self):
        apkforge_mcp.apkforge.scaffold(
            "Test App", "com.example.test", self.root / "app"
        )
        with self.assertRaises(PermissionError):
            self.server.call_tool("build_apk", {
                "path": "app",
                "allow_project_code": False,
            })

    def test_mcp_initialize_and_tool_call(self):
        init = apkforge_mcp.dispatch(self.server, {
            "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}
        })
        self.assertEqual(init["result"]["serverInfo"]["name"], "apk-forge-local-tools")
        call = apkforge_mcp.dispatch(self.server, {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {"name": "workspace_info", "arguments": {}},
        })
        self.assertFalse(call["result"]["isError"])
        decoded = json.loads(call["result"]["content"][0]["text"])
        self.assertFalse(decoded["github_required"])

    def test_reads_content_length_framed_mcp_message(self):
        payload = json.dumps({
            "jsonrpc": "2.0", "id": 7, "method": "ping", "params": {}
        }).encode("utf-8")
        framed = b"Content-Length: " + str(len(payload)).encode("ascii") + b"\r\n\r\n" + payload
        message = apkforge_mcp.read_frame(io.BytesIO(framed))
        self.assertEqual(message["method"], "ping")


if __name__ == "__main__":
    unittest.main()
