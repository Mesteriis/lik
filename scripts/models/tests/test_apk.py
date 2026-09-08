import hashlib
import json
import os
import pathlib
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from inspect_apk import inspect_apk

ROOT = pathlib.Path(__file__).resolve().parents[3]
CATALOG = json.loads((ROOT / "models/catalog-v1.json").read_text())


class ApkFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "fixture.apk"
        self.entries = {
            "assets/models/" + name: (ROOT / "models" / name).read_bytes()
            for name in ("catalog-v1.json", "contracts-v1.json", "backend-policy-v1.json")
        }
        self.entries["assets/models/availability.json"] = (json.dumps({
            "schemaVersion": 1, "available": False,
            "catalogVersion": CATALOG["catalogVersion"], "selectedProfile": "balanced-v1",
            "mode": "settings-download-from-huggingface", "bundledPayloads": False,
        }) + "\n").encode()
        for notice in CATALOG["licenseNotices"]:
            self.entries["assets/models/" + notice["path"]] = (ROOT / "models" / notice["path"]).read_bytes()

    def write_apk(self, extra=None):
        with zipfile.ZipFile(self.path, "w") as archive:
            for name, data in (self.entries | (extra or {})).items():
                archive.writestr(name, data)
        return self.path


class ApkVerificationTest(ApkFixture):
    def test_weight_or_tokenizer_payload_cannot_pass_distribution(self):
        self.assertEqual(0, inspect_apk(self.write_apk(), CATALOG)["onnxCount"])
        for name in ("assets/models/shared/model.onnx", "assets/models/tokenizer.json", "assets/hidden/weights.bin"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                inspect_apk(self.write_apk({name: b"fixed model bytes"}), CATALOG)

    def test_unrecognized_resources_and_root_blobs_fail_closed(self):
        for name in ("res/raw/arbitrary.bin", "res/raw-v36/tokenizer.json", "arbitrary.bin",
                     "hidden/model.data", "META-INF/weights", "kotlin/fake.kotlin_builtins"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                inspect_apk(self.write_apk({name: b"renamed bytes"}), CATALOG)

    def test_model_bytes_cannot_replace_compiled_formats_or_approved_binaries(self):
        for name in ("res/drawable/icon.png", "res/layout/main.xml", "res/a.xml", "resources.arsc",
                     "classes9.dex", "AndroidManifest.xml", "lib/arm64-v8a/libonnxruntime.so",
                     "assets/dexopt/baseline.prof", "DebugProbesKt.bin"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                inspect_apk(self.write_apk({name: b"renamed model bytes"}), CATALOG)

    def test_processing_contract_cannot_smuggle_an_additional_payload(self):
        data = json.loads(self.entries["assets/models/contracts-v1.json"])
        data["tokenizer"] = {"vocab": {"secret-model-token": 1}}
        with self.assertRaises(ValueError):
            inspect_apk(self.write_apk({"assets/models/contracts-v1.json": json.dumps(data)}), CATALOG)

    def test_ordinary_git_checkout_metadata_allows_only_agp_fields(self):
        # AGP emits this instead of NO_VALID_GIT_FOUND outside a linked worktree.
        name = "META-INF/version-control-info.textproto"
        data = (b'repositories {\n  system: GIT\n  local_root_path: "$PROJECT_DIR"\n'
                b'  revision: "d79ae0cf82d1aa53b1ba5de191aa1e82c6cfccc2"\n}\n')
        try:
            report = inspect_apk(self.write_apk({name: data}), CATALOG)
        except ValueError as error:
            self.fail("Legitimate AGP checkout metadata must pass: " + str(error))
        self.assertTrue(report["metadataOnly"])
        with self.assertRaises(ValueError):
            inspect_apk(self.write_apk({name: data + b'tokenizer: "hidden"\n'}), CATALOG)

    def test_real_launcher_png_is_allowed_but_trailing_blob_is_not(self):
        png = (ROOT / "app/src/main/res/drawable-nodpi/lik_emblem.png").read_bytes()
        name = "res/drawable-nodpi-v4/lik_emblem.png"
        self.assertTrue(inspect_apk(self.write_apk({name: png}), CATALOG)["metadataOnly"])
        with self.assertRaises(ValueError):
            inspect_apk(self.write_apk({name: png + b"hidden payload"}), CATALOG)

    def test_duplicate_zip_paths_are_rejected(self):
        self.write_apk()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.path, "a") as archive:
                archive.writestr("assets/models/catalog-v1.json", self.entries["assets/models/catalog-v1.json"])
        with self.assertRaises(ValueError):
            inspect_apk(self.path, CATALOG)

    def test_unindexed_archive_bytes_cannot_hide_payloads(self):
        for location in ("prefix", "suffix", "comment", "extra"):
            self.write_apk()
            if location == "prefix":
                self.path.write_bytes(b"hidden model bytes" + self.path.read_bytes())
            elif location == "suffix":
                self.path.write_bytes(self.path.read_bytes() + b"hidden model bytes")
            elif location == "comment":
                with zipfile.ZipFile(self.path, "a") as archive:
                    archive.comment = b"hidden model bytes"
            else:
                with zipfile.ZipFile(self.path, "w") as archive:
                    for name, data in self.entries.items():
                        info = zipfile.ZipInfo(name)
                        info.extra = b"\xfe\xca\x06\x00hidden"
                        archive.writestr(info, data)
            with self.subTest(location=location), self.assertRaises(ValueError):
                inspect_apk(self.path, CATALOG)


class TestApkVerificationTest(ApkFixture):
    def setUp(self):
        super().setUp()
        self.entries = {
            "assets/" + path.relative_to(ROOT / "app/schemas").as_posix(): path.read_bytes()
            for path in (ROOT / "app/schemas").rglob("*.json")
        }

    def test_android_test_allows_only_exact_checked_in_room_schemas(self):
        self.assertTrue(inspect_apk(self.write_apk(), CATALOG, "android-test")["metadataOnly"])
        name = next(iter(self.entries))
        with self.assertRaises(ValueError):
            inspect_apk(self.write_apk({name: b'{"model":"unexpected"}'}), CATALOG, "android-test")

    def test_android_test_has_the_same_resource_and_blob_boundary(self):
        for name in ("assets/model.bin", "res/raw/model.bin", "res/drawable/model.png", "model.dat"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                inspect_apk(self.write_apk({name: b"unapproved model bytes"}), CATALOG, "android-test")


class DownloadedPayloadRegressionTest(ApkFixture):
    """Opt-in real bytes stay outside Git; explicit cache must never silently skip."""
    def runtime_file(self, relative):
        configured = os.environ.get("LIK_MODEL_CACHE")
        cache = pathlib.Path(configured) if configured else pathlib.Path.home() / "Library/Caches/Lik/model-artifacts"
        path = cache / "hf-runtime" / relative
        if not path.is_file() and not configured:
            self.skipTest("Real downloaded payload regression requires LIK_MODEL_CACHE")
        data = path.read_bytes()
        expected = next(f for c in CATALOG["components"] for f in c["artifacts"] if f["path"] == relative)
        self.assertEqual(expected["size"], len(data))
        self.assertEqual(expected["sha256"], hashlib.sha256(data).hexdigest())
        return data

    def test_real_downloaded_tokenizer_in_res_raw_is_rejected(self):
        tokenizer = self.runtime_file("multilingual-text-v1/tokenizer.json")
        with self.assertRaises(ValueError):
            inspect_apk(self.write_apk({"res/raw/tokenizer.json": tokenizer}), CATALOG)

    def test_real_yunet_renamed_as_res_raw_binary_is_rejected(self):
        model = self.runtime_file("yunet-v1/model.onnx")
        with self.assertRaises(ValueError):
            inspect_apk(self.write_apk({"res/raw/face_model.bin": model}), CATALOG)

    def test_real_yunet_renamed_across_every_apk_boundary_is_rejected(self):
        model = self.runtime_file("yunet-v1/model.onnx")
        for name in ("res/drawable/icon.png", "res/layout/view.xml", "res/a.xml", "resources.arsc",
                     "classes9.dex", "lib/arm64-v8a/libonnxruntime.so", "DebugProbesKt.bin",
                     "META-INF/LICENSE-1DS", "model.data"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                inspect_apk(self.write_apk({name: model}), CATALOG)
