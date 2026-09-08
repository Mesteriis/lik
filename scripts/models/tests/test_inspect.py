import importlib.util
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from prepare import inspect_model


@unittest.skipUnless(importlib.util.find_spec("onnxruntime"), "requires the pinned export environment")
class OnnxValidationTest(unittest.TestCase):
    def graph(self, directory, divide_zero=False, dynamic=False):
        import onnx
        from onnx import helper, TensorProto
        shape = [1, "width"] if dynamic else [1, 2]
        inputs = [helper.make_tensor_value_info("x", TensorProto.FLOAT, shape)]
        output = helper.make_tensor_value_info("y", TensorProto.FLOAT, shape)
        if divide_zero:
            nodes = [helper.make_node("Div", ["x", "zero"], ["y"])]
            initializers = [helper.make_tensor("zero", TensorProto.FLOAT, [], [0.])]
        else:
            nodes, initializers = [helper.make_node("Identity", ["x"], ["y"])], []
        model = helper.make_model(helper.make_graph(nodes, "fixture", inputs, [output], initializers),
                                  opset_imports=[helper.make_opsetid("", 17)], ir_version=10)
        path = pathlib.Path(directory) / "fixture.onnx"
        onnx.save(model, path)
        return path

    def test_actual_runtime_records_shapes_and_rejects_nonfinite_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            result = inspect_model(self.graph(directory))
            self.assertEqual([1, 2], result["outputs"][0]["smokeShape"])
            reference = result["smokeReference"]
            self.assertEqual("allclose", reference["comparison"])
            self.assertEqual(8, reference["size"])
            self.assertEqual(bytes(8), (pathlib.Path(directory) / reference["file"]).read_bytes())
            with self.assertRaisesRegex(ValueError, "non-finite"):
                inspect_model(self.graph(directory, divide_zero=True))

    def test_dynamic_inputs_require_explicit_smoke_shapes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.graph(directory, dynamic=True)
            with self.assertRaisesRegex(ValueError, "smoke shape"):
                inspect_model(path)
            self.assertEqual([1, 4], inspect_model(path, {"x": [1, 4]})["outputs"][0]["smokeShape"])


if __name__ == "__main__":
    unittest.main()
