import importlib.util
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from verify_hf_runtime import project_multilingual


@unittest.skipUnless(importlib.util.find_spec("numpy"), "requires export environment")
class RuntimePipelineTest(unittest.TestCase):
    def test_padding_is_excluded_before_publisher_projection_and_normalization(self):
        import numpy as np
        hidden = np.array([[[1., 0.], [99., 99.], [3., 2.]]], dtype=np.float32)
        actual = project_multilingual(hidden, np.array([[1, 0, 1]]), np.array([[1., 0.], [0., 2.]]))
        self.assertTrue(np.allclose(actual, [[2 ** -.5, 2 ** -.5]]))
