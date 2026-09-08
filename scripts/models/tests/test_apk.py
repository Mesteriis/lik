import hashlib
import json
import pathlib
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from inspect_apk import inspect_apk


class ApkVerificationTest(unittest.TestCase):
    def test_weight_or_tokenizer_payload_cannot_pass_distribution(self):
        catalog = {"components": []}
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "fixture.apk"
            for payload in [None, "assets/models/shared/model.onnx", "assets/models/tokenizer.json", "assets/hidden/weights.bin"]:
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("assets/models/catalog-v1.json", json.dumps(catalog))
                    archive.writestr("assets/models/contracts-v1.json", "{}")
                    archive.writestr("assets/models/backend-policy-v1.json", "{}")
                    archive.writestr("assets/models/availability.json", json.dumps({"available": False, "bundledPayloads": False}))
                    if payload:
                        archive.writestr(payload, b"fixed model bytes")
                if payload is None:
                    self.assertEqual(0, inspect_apk(path, catalog)["onnxCount"])
                else:
                    with self.assertRaises(ValueError):
                        inspect_apk(path, catalog)
