import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from artifacts import validate_catalog
from freeze_catalog import fingerprint


class CatalogIdentityTest(unittest.TestCase):
    def test_frozen_catalog_contracts_and_pipeline_dependencies_are_coherent(self):
        root = pathlib.Path(__file__).resolve().parents[3]
        catalog = json.loads((root / "models/catalog-v1.json").read_text())
        contracts = json.loads((root / "models/contracts-v1.json").read_text())["components"]
        files = validate_catalog(catalog)
        self.assertEqual(12, sum(f["path"].endswith(".onnx") for f in files))
        self.assertEqual(len(files), len({f["path"] for f in files}))
        for component in catalog["components"]:
            self.assertEqual(contracts[component["id"]], component["contract"])
            self.assertEqual(component["fingerprint"], fingerprint({k: v for k, v in component.items() if k != "fingerprint"}))
        for profile in catalog["profiles"]:
            self.assertIn("sensitive-v1", profile["components"])
            for name, pipeline in profile["pipelines"].items():
                self.assertEqual(1, len(pipeline["compatibleFingerprints"]), (profile["id"], name))
                self.assertRegex(pipeline["compatibleFingerprints"][0], r"^[a-f0-9]{64}$")
                self.assertNotEqual(pipeline["fingerprint"], pipeline["compatibleFingerprints"][0])
            self.assertEqual(profile["fingerprint"], fingerprint({k: v for k, v in profile.items() if k != "fingerprint"}))
        dimensions = {p["id"]: p["pipelines"]["search"]["dimension"] for p in catalog["profiles"]}
        self.assertEqual({"compact-v1": 512, "balanced-v1": 768, "extended-v1": 1024}, dimensions)
        self.assertIsNone(contracts["sensitive-v1"]["calibration"]["releaseThreshold"])
        onnx_paths = {f["path"] for f in files if f["path"].endswith(".onnx")}
        activation = {r["path"]: r for r in catalog["activationSmokeReferences"]}
        self.assertEqual(onnx_paths, set(activation))
        self.assertEqual("activation-ramp-v1", catalog["oracleRevision"])
        for path, reference in activation.items():
            samples = reference["samples"]
            self.assertGreaterEqual(len(samples), 2, path)
            self.assertEqual(len(samples), len({sample["index"] for sample in samples}), path)
            self.assertTrue(all(sample["index"] >= 0 for sample in samples), path)
            self.assertTrue(all(__import__('re').fullmatch(r"[0-9a-f]{8}", sample["floatBits"])
                                for sample in samples), path)
            self.assertLess(reference["minimumNormRatio"], 1)
            self.assertGreater(reference["maximumNormRatio"], 1)
            self.assertGreater(reference["sampleAbsoluteTolerance"], 0)
            self.assertGreater(reference["sampleRelativeTolerance"], 0)
            self.assertLess(reference["minimumRangeRatio"], 1)
            self.assertGreater(reference["maximumRangeRatio"], 1)
            if path.startswith("ocr-"):
                self.assertTrue(reference["scaleAware"], path)
        for file in files:
            if file["path"].endswith(".onnx"):
                self.assertTrue(all(i["smokePattern"] in
                                    {"deterministic-ramp-v1", "byte-ramp-v1", "zeros-v1", "ones-v1"}
                                    for i in file["onnx"]["inputs"]), file["path"])
                if file["path"].startswith("ocr-"):
                    self.assertTrue(any(i["smokePattern"] in {"deterministic-ramp-v1", "byte-ramp-v1"}
                                        for i in file["onnx"]["inputs"]), file["path"])
