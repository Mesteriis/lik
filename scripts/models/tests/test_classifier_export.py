import importlib.util
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from prepare import export_torch


@unittest.skipUnless(importlib.util.find_spec("torch"), "requires pinned export environment")
class ClassifierExportTest(unittest.TestCase):
    def test_classifier_exports_probabilities_without_embedding_normalization(self):
        import numpy as np
        import torch
        import onnxruntime as ort
        torch.manual_seed(0)
        model = torch.nn.Sequential(torch.nn.Linear(3, 2), torch.nn.Softmax(dim=-1)).eval()
        inputs = (torch.tensor([[.2, .8, -.4]]),)
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            work = root / "work"
            work.mkdir()
            evidence = export_torch(model, inputs, ["pixel_values"], root / "model.onnx", work,
                                    precision="fp32", output_name="probabilities")
            session = ort.InferenceSession(str(root / "model.onnx"), providers=["CPUExecutionProvider"])
            self.assertEqual("probabilities", session.get_outputs()[0].name)
            actual = session.run(None, {"pixel_values": inputs[0].numpy()})[0]
            self.assertTrue(np.allclose(actual.sum(-1), 1))
            self.assertTrue(np.allclose(actual, model(*inputs).detach().numpy(), atol=1e-6))
            self.assertEqual("FP32", evidence["precision"])
