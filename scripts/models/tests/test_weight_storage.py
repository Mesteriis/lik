import importlib.util
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from prepare import store_float16_weights


@unittest.skipUnless(importlib.util.find_spec("onnxruntime"), "requires pinned export environment")
class WeightStorageTest(unittest.TestCase):
    def test_stored_half_weights_keep_float_inputs_outputs_and_computation(self):
        import numpy as np
        import onnx
        import onnxruntime as ort
        from onnx import helper, numpy_helper, TensorProto
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "source.onnx"
            target = pathlib.Path(directory) / "stored.onnx"
            weights = np.linspace(-.2, .2, 1024, dtype=np.float32).reshape(32, 32)
            graph = helper.make_graph([helper.make_node("MatMul", ["x", "w"], ["y"])], "fixture",
                                      [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 32])],
                                      [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 32])],
                                      [numpy_helper.from_array(weights, "w")])
            onnx.save(helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], ir_version=10), source)
            store_float16_weights(source, target)
            actual_graph = onnx.load(target)
            onnx.checker.check_model(actual_graph)
            self.assertEqual(TensorProto.FLOAT16, actual_graph.graph.initializer[0].data_type)
            self.assertEqual("Cast", actual_graph.graph.node[0].op_type)
            self.assertEqual(TensorProto.FLOAT, actual_graph.graph.node[0].attribute[0].i)
            session = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"])
            self.assertEqual("tensor(float)", session.get_inputs()[0].type)
            value = np.ones((1, 32), dtype=np.float32)
            result = session.run(None, {"x": value})[0]
            # Storage rounds weights once. The graph must then compute in FP32
            # using those rounded values, including cancellation in this input.
            expected = value @ weights.astype(np.float16).astype(np.float32)
            self.assertTrue(np.allclose(result, expected, atol=1e-6))
            self.assertEqual(np.float32, result.dtype)


if __name__ == "__main__":
    unittest.main()
