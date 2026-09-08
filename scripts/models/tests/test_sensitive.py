import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from evaluate import sensitive_metrics


class SensitiveMetricsTest(unittest.TestCase):
    fixtures = [{"id": "a", "language": "ru", "sensitive": True},
                {"id": "b", "language": "ru", "sensitive": False},
                {"id": "c", "language": "en", "sensitive": True},
                {"id": "d", "language": "en", "sensitive": False}]
    predictions = [{"id": "a", "score": .9}, {"id": "b", "score": .7},
                   {"id": "c", "score": .2}, {"id": "d", "score": .1}]

    def test_false_negatives_and_false_positives_stay_separate_by_language(self):
        metrics = sensitive_metrics(self.fixtures, self.predictions, .5)
        self.assertEqual(0., metrics["ru"]["false_negative_rate"])
        self.assertEqual(1., metrics["ru"]["false_positive_rate"])
        self.assertEqual(1., metrics["en"]["false_negative_rate"])
        self.assertEqual(0., metrics["en"]["false_positive_rate"])

    def test_missing_calibration_or_nonfinite_prediction_is_rejected(self):
        with self.assertRaises(ValueError):
            sensitive_metrics(self.fixtures, self.predictions, None)
        with self.assertRaises(ValueError):
            sensitive_metrics(self.fixtures, [dict(p, score=float("nan")) for p in self.predictions], .5)
