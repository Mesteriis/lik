import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import artifacts


class ArtifactBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.cache = self.root / "cache"
        self.output = self.root / "assets"
        self.payload = b"model bytes"
        self.catalog = {
            "schemaVersion": 1,
            "catalogVersion": "2026-09-08.1",
            "defaultProfile": "balanced-v1",
            "profiles": [
                {"id": name + "-v1", "components": ["shared"]}
                for name in ("compact", "balanced", "extended")
            ],
            "components": [{"id": "shared", "sources": [{
                "revision": "a" * 40,
                "files": [{"path": "model.bin", "size": len(self.payload),
                           "sha256": hashlib.sha256(self.payload).hexdigest()}],
            }], "artifacts": [{
                "path": "shared/model.onnx", "size": len(self.payload),
                "sha256": hashlib.sha256(self.payload).hexdigest(),
            }]}],
        }

    def install(self):
        path = self.cache / "prepared/shared/model.onnx"
        path.parent.mkdir(parents=True)
        path.write_bytes(self.payload)
        return path

    def test_cache_never_causes_model_payloads_to_enter_apk_assets(self):
        self.install()
        artifacts.stage(self.catalog, self.cache, self.output)
        self.assertFalse(json.loads((self.output / "models/availability.json").read_text())["available"])
        self.assertEqual(0, len(list(self.output.rglob("*.onnx"))))

    def test_same_size_corruption_is_rejected_before_publication(self):
        path = self.install()
        path.write_bytes(b"x" * len(self.payload))
        with self.assertRaisesRegex(artifacts.ArtifactError, "SHA-256"):
            artifacts.verify(self.catalog, self.cache)
        self.assertFalse(self.output.exists())

    def test_missing_cache_cannot_pass_developer_runtime_verification(self):
        with self.assertRaisesRegex(artifacts.ArtifactError, "Missing"):
            artifacts.verify(self.catalog, self.cache)

    def test_metadata_staging_removes_stale_weights_without_a_cache(self):
        self.install()
        self.output.mkdir(parents=True)
        (self.output / "stale.onnx").write_bytes(self.payload)
        import shutil
        shutil.rmtree(self.cache)
        artifacts.stage(self.catalog, self.cache, self.output)
        self.assertFalse(list(self.output.rglob("*.onnx")))
        self.assertFalse(json.loads((self.output / "models/availability.json").read_text())["available"])

    def test_partial_cache_is_rejected_by_developer_verification(self):
        (self.cache / "prepared").mkdir(parents=True)
        with self.assertRaisesRegex(artifacts.ArtifactError, "Missing"):
            artifacts.verify(self.catalog, self.cache)

    def test_path_traversal_and_symlinks_are_rejected(self):
        self.catalog["components"][0]["artifacts"][0]["path"] = "../outside.onnx"
        with self.assertRaisesRegex(artifacts.ArtifactError, "path"):
            artifacts.verify(self.catalog, self.cache)
        self.catalog["components"][0]["artifacts"][0]["path"] = "shared/model.onnx"
        path = self.install()
        path.unlink()
        outside = self.root / "outside.onnx"
        outside.write_bytes(self.payload)
        path.symlink_to(outside)
        with self.assertRaisesRegex(artifacts.ArtifactError, "Symlink"):
            artifacts.verify(self.catalog, self.cache)

    def test_floating_revision_and_incomplete_profile_catalog_are_rejected(self):
        self.catalog["components"][0]["sources"][0]["revision"] = "main"
        with self.assertRaisesRegex(artifacts.ArtifactError, "revision"):
            artifacts.verify(self.catalog, self.cache)
        self.catalog["components"][0]["sources"][0]["revision"] = "a" * 40
        self.catalog["profiles"].pop()
        with self.assertRaisesRegex(artifacts.ArtifactError, "profiles"):
            artifacts.verify(self.catalog, self.cache)

    def test_download_catalog_rejects_missing_or_unpinned_hugging_face_urls(self):
        self.catalog["delivery"] = "settings-download-from-huggingface"
        file = self.catalog["components"][0]["artifacts"][0]
        with self.assertRaisesRegex(artifacts.ArtifactError, "Hugging Face"):
            artifacts.validate_catalog(self.catalog)
        file.update(repo="publisher/model", revision="a" * 40, remoteFile="onnx/model.onnx")
        file["url"] = "https://huggingface.co/publisher/model/resolve/main/onnx/model.onnx"
        with self.assertRaisesRegex(artifacts.ArtifactError, "Hugging Face"):
            artifacts.validate_catalog(self.catalog)
        file["url"] = "https://huggingface.co/publisher/model/resolve/" + "a" * 40 + "/onnx/model.onnx"
        artifacts.validate_catalog(self.catalog)
        file["url"] += "?download=true"
        with self.assertRaisesRegex(artifacts.ArtifactError, "Hugging Face"):
            artifacts.validate_catalog(self.catalog)


if __name__ == "__main__":
    unittest.main()
