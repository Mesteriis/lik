"""Reviewed trust anchors never enroll inspected APK bytes automatically."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compiled_apk import COMPILED_POLICY, ROOT, TOOL_FILES, verify_parser_files


class CompilerPolicyTest(unittest.TestCase):
    def test_candidate_helper_cannot_overwrite_trusted_policy(self):
        before = COMPILED_POLICY.read_bytes()
        result = subprocess.run([sys.executable, ROOT / "scripts/models/compiled_apk.py", "--output", COMPILED_POLICY],
                                capture_output=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"Cannot overwrite the trust anchor", result.stderr)
        self.assertEqual(before, COMPILED_POLICY.read_bytes())

    def test_reviewed_linux_sdk_bytes_are_allowed_but_corruption_is_rejected(self):
        configured = os.environ.get("LIK_MODEL_CACHE")
        cache = Path(configured) if configured else Path.home() / "Library/Caches/Lik/model-artifacts"
        archive = cache / "research/hf-delivery/build-tools_r36_linux.zip"
        if not archive.is_file() and not configured:
            self.skipTest("Download the pinned official Linux SDK ZIP for cross-host receipt regression")
        policy = json.loads(COMPILED_POLICY.read_text())["toolchain"]
        source = next(p for p in policy["compatibleParserDistributions"] if p["platform"] == "linux")
        data = archive.read_bytes()
        self.assertEqual(source["size"], len(data))
        self.assertEqual(source["sha256"], hashlib.sha256(data).hexdigest())
        with tempfile.TemporaryDirectory() as temporary, zipfile.ZipFile(archive) as zipped:
            tools = Path(temporary)
            for name in TOOL_FILES:
                (tools / name).parent.mkdir(parents=True, exist_ok=True)
                (tools / name).write_bytes(zipped.read("android-16/" + name))
            try:
                verify_parser_files(policy, tools)
            except ValueError as error:
                self.fail("Reviewed official Linux SDK must pass: " + str(error))
            (tools / "dexdump").write_bytes(b"unapproved parser")
            with self.assertRaises(ValueError):
                verify_parser_files(policy, tools)
