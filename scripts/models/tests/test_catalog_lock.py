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
            self.assertEqual(profile["fingerprint"], fingerprint({k: v for k, v in profile.items() if k != "fingerprint"}))
        dimensions = {p["id"]: p["pipelines"]["search"]["dimension"] for p in catalog["profiles"]}
        self.assertEqual({"compact-v1": 512, "balanced-v1": 768, "extended-v1": 1024}, dimensions)
        self.assertIsNone(contracts["sensitive-v1"]["calibration"]["releaseThreshold"])
