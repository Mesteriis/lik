import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import evaluate


class EvaluationTest(unittest.TestCase):
    def test_ranking_scores_relevance_and_rejects_missing_or_duplicate_results(self):
        queries = [{"id": "en01", "language": "en", "relevant": ["cat"]},
                   {"id": "ru01", "language": "ru", "relevant": ["dog"]}]
        actual = [{"id": "en01", "ranking": ["cat", "dog"]},
                  {"id": "ru01", "ranking": ["cat", "dog"]}]
        scores = evaluate.retrieval_metrics(queries, actual, {"cat", "dog"})
        self.assertEqual(1.0, scores["en"]["recall@1"])
        self.assertEqual(0.0, scores["ru"]["recall@1"])
        self.assertEqual(0.5, scores["ru"]["mrr"])
        with self.assertRaises(ValueError):
            evaluate.retrieval_metrics(queries, actual[:1], {"cat", "dog"})
        actual[1]["ranking"] = ["dog", "dog"]
        with self.assertRaises(ValueError):
            evaluate.retrieval_metrics(queries, actual, {"cat", "dog"})

    def test_cyrillic_ocr_uses_unicode_characters_and_nfc(self):
        scores = evaluate.ocr_metrics([{"id": "r", "language": "ru", "text": "Мой кот"}],
                                      [{"id": "r", "text": "Мой кит"}])
        self.assertAlmostEqual(1 / 7, scores["ru"]["cer"])
        self.assertEqual(0.5, scores["ru"]["wer"])

    def test_face_metrics_count_false_accepts_and_false_rejects_at_explicit_threshold(self):
        pairs = [{"id": "same", "same": True}, {"id": "different", "same": False}]
        scores = evaluate.face_metrics(pairs, [{"id": "same", "cosine": .3},
                                               {"id": "different", "cosine": .5}], .4)
        self.assertEqual(1.0, scores["false_accept_rate"])
        self.assertEqual(1.0, scores["false_reject_rate"])
        with self.assertRaises(ValueError):
            evaluate.face_metrics(pairs, [{"id": "same", "cosine": float("nan")},
                                         {"id": "different", "cosine": .5}], .4)


if __name__ == "__main__":
    unittest.main()
