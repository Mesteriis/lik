#!/usr/bin/env python3
"""Score a recorded inference run against a hash-locked external fixture set."""
import argparse
import json
import math
from pathlib import Path
import unicodedata

from artifacts import digest, verify_file


def aligned(expected, actual):
    index = {row["id"]: row for row in actual}
    if len(index) != len(actual) or set(index) != {row["id"] for row in expected}:
        raise ValueError("Predictions must cover every fixture exactly once")
    return [(row, index[row["id"]]) for row in expected]


def retrieval_metrics(queries, predictions, photo_ids):
    result = {}
    for query, prediction in aligned(queries, predictions):
        ranking = prediction["ranking"]
        if not ranking or len(set(ranking)) != len(ranking) or not set(ranking) <= photo_ids:
            raise ValueError("Invalid/duplicate ranked photo identity")
        relevant = set(query["relevant"])
        if not relevant or not relevant <= photo_ids:
            raise ValueError("Missing relevance judgments")
        language = result.setdefault(query["language"], {"queries": 0, "recall@1": 0., "recall@5": 0., "mrr": 0.})
        language["queries"] += 1
        for k in (1, 5):
            language[f"recall@{k}"] += len(relevant.intersection(ranking[:k])) / len(relevant)
        language["mrr"] += next((1 / (i + 1) for i, photo in enumerate(ranking) if photo in relevant), 0.)
    for scores in result.values():
        for key in ("recall@1", "recall@5", "mrr"):
            scores[key] /= scores["queries"]
    return result


def edit_distance(left, right):
    previous = list(range(len(right) + 1))
    for i, item in enumerate(left, 1):
        current = [i]
        for j, other in enumerate(right, 1):
            current.append(min(previous[j] + 1, current[-1] + 1, previous[j - 1] + (item != other)))
        previous = current
    return previous[-1]


def ocr_metrics(fixtures, predictions):
    result = {}
    for fixture, prediction in aligned(fixtures, predictions):
        expected = unicodedata.normalize("NFC", fixture["text"])
        actual = unicodedata.normalize("NFC", prediction["text"])
        if not expected.strip():
            raise ValueError("OCR ground truth must contain text")
        scores = result.setdefault(fixture["language"], {"samples": 0, "characters": 0, "words": 0, "character_errors": 0, "word_errors": 0})
        scores["samples"] += 1
        scores["characters"] += len(expected)
        scores["words"] += len(expected.split())
        scores["character_errors"] += edit_distance(expected, actual)
        scores["word_errors"] += edit_distance(expected.split(), actual.split())
    for scores in result.values():
        scores["cer"] = scores["character_errors"] / scores["characters"]
        scores["wer"] = scores["word_errors"] / scores["words"]
    return result


def face_metrics(fixtures, predictions, threshold):
    if not math.isfinite(threshold) or not -1 <= threshold <= 1:
        raise ValueError("Invalid face threshold")
    same = different = false_accept = false_reject = 0
    for fixture, prediction in aligned(fixtures, predictions):
        cosine = prediction["cosine"]
        if not math.isfinite(cosine) or not -1 <= cosine <= 1:
            raise ValueError("Invalid face cosine")
        if fixture["same"]:
            same += 1
            false_reject += cosine < threshold
        else:
            different += 1
            false_accept += cosine >= threshold
    if not same or not different:
        raise ValueError("Face fixtures require positive and negative pairs")
    return {"threshold": threshold, "same_pairs": same, "different_pairs": different,
            "false_accept_rate": false_accept / different, "false_reject_rate": false_reject / same}


def sensitive_metrics(fixtures, predictions, threshold):
    if threshold is None or not math.isfinite(threshold) or not 0 <= threshold <= 1:
        raise ValueError("A finite calibrated sensitive threshold is required")
    result = {}
    for fixture, prediction in aligned(fixtures, predictions):
        score = prediction["score"]
        if not math.isfinite(score) or not 0 <= score <= 1:
            raise ValueError("Sensitive score must be a finite probability")
        if type(fixture["sensitive"]) is not bool:
            raise ValueError("Sensitive ground truth must be a reviewed boolean")
        counts = result.setdefault(fixture["language"], {"positive": 0, "negative": 0,
                                                       "false_positive": 0, "false_negative": 0})
        if fixture["sensitive"]:
            counts["positive"] += 1
            counts["false_negative"] += score < threshold
        else:
            counts["negative"] += 1
            counts["false_positive"] += score >= threshold
    if set(result) != {"ru", "en"}:
        raise ValueError("Sensitive evaluation requires both RU and EN context slices")
    for counts in result.values():
        if not counts["positive"] or not counts["negative"]:
            raise ValueError("Each sensitive slice needs positive and negative fixtures")
        counts["false_positive_rate"] = counts["false_positive"] / counts["negative"]
        counts["false_negative_rate"] = counts["false_negative"] / counts["positive"]
        counts["threshold"] = threshold
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[2] / "models/catalog-v1.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    dataset = json.loads(args.dataset.read_text())
    run = json.loads(args.predictions.read_text())
    if run["datasetSha256"] != digest(args.dataset) or run["catalogSha256"] != digest(args.catalog):
        raise ValueError("Run belongs to another dataset/catalog generation")
    if not all(run.get(key) for key in ("profile", "device", "runtime", "producer", "startedAt")):
        raise ValueError("Run lacks provenance")
    if run["profile"] not in {p["id"] for p in json.loads(args.catalog.read_text())["profiles"]}:
        raise ValueError("Unknown profile")
    for photo in dataset["photos"]:
        verify_file(args.dataset.parent, photo)
    fixed = json.loads((Path(__file__).resolve().parents[2] / "models/evaluation/queries-v1.json").read_text())["queries"]
    if {(q["id"], q["text"], q["language"]) for q in dataset["queries"]} != {(q["id"], q["text"], q["language"]) for q in fixed}:
        raise ValueError("Dataset must contain exactly the 60 fixed RU/EN queries")
    metrics = {"retrieval": retrieval_metrics(dataset["queries"], run["retrieval"], {p["id"] for p in dataset["photos"]}) if "retrieval" in run else {"status": "not-run"}}
    # Missing stages are explicitly not run; they never become zero-error scores.
    metrics["ocr"] = ocr_metrics(dataset["ocr"], run["ocr"]) if "ocr" in run else {"status": "not-run"}
    metrics["people"] = face_metrics(dataset["facePairs"], run["people"], dataset["faceThreshold"]) if "people" in run else {"status": "not-run"}
    metrics["sensitive"] = sensitive_metrics(dataset["sensitive"], run["sensitive"], dataset["sensitiveThreshold"]) if "sensitive" in run else {"status": "not-run"}
    report = {"schemaVersion": 1, "datasetSha256": digest(args.dataset), "catalogSha256": digest(args.catalog),
              "runSha256": digest(args.predictions), "provenance": {k: run[k] for k in ("profile", "device", "runtime", "producer", "startedAt")}, "metrics": metrics}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
