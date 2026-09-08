# External fixed fixture contract v1

No photo, OCR or face quality metric has been produced merely by adding this harness. Supply a fixed local fixture set outside Git; the app does not download an evaluation dataset. Preserve that set and its `dataset.json` SHA-256 for every compared profile/backend. Do not change queries, relevance judgments or face thresholds after seeing a result.

`dataset.json` has `schemaVersion: 1`, a descriptive `id`, provenance/license notes, and these arrays:

* `photos`: at least 100 natural photos, each with unique `id`, safe relative `path`, positive byte `size`, full `sha256`, provenance and license/consent. All external bytes are checked before scoring. Include EXIF portrait/rotated photos, mixed aspect ratios, poor lighting, text and non-text negatives. Do not use generated random images to claim retrieval quality.
* `queries`: copy all 60 records from `queries-v1.json` exactly and add a nonempty `relevant` array of photo IDs to each. English/Russian translations use the same manually reviewed relevance set. Labels are fixed independently of model output. Keep additional hard negatives, and report the exact photo count.
* `ocr`: at least 20 English and 20 Russian text crops, each with unique `id`, `photoId`, `language`, exact ground-truth `text`, and optional quadrilateral coordinates in the EXIF-oriented photo. Include mixed Cyrillic/Latin, digits, receipts, signs, small text and rotated scenes. OCR evaluation consumes predictions keyed by crop ID; detector miss/empty recognition must be an empty string, not a dropped sample.
* `facePairs`: fixed `id`, `leftPhotoId`, `rightPhotoId`, face annotations and boolean `same`; at least 20 consented adult identities with two different captures per identity and at least 40 positive/40 negative pairs. Document collection permission. This is a face-embedding verification test, not an assertion of demographic fairness or face-grouping quality.
* `faceThreshold`: explicit cosine threshold fixed before scoring. Calibrate on a separate split; include that split's digest. Never choose a threshold on this test set.

Minimal structural example (illustrative only; all ellipses must be replaced with real data):

```json
{
  "schemaVersion": 1,
  "id": "lik-eval-private-v1",
  "photos": [{"id": "photo001", "path": "photos/001.jpg", "size": 12345, "sha256": "...", "provenance": "...", "license": "..."}],
  "queries": [{"id": "en01", "language": "en", "text": "a cat sleeping on a sofa", "relevant": ["photo001"]}],
  "ocr": [{"id": "ocr-ru01", "photoId": "photo001", "language": "ru", "text": "Кассовый чек"}],
  "facePairs": [{"id": "pair01", "leftPhotoId": "photo002", "rightPhotoId": "photo003", "same": true}],
  "faceThreshold": 0.363
}
```

`run_retrieval.py` performs actual offline host-CPU inference and records rankings, exact catalog/dataset hashes, runtime/device identity, load time and end-to-end per-photo/per-query timings. It cannot fabricate OCR/people results; those stages are explicitly `not-run` until their real pipeline supplies predictions. For each language, the scorer reports mean Recall@1/5 (fraction of all relevant photos retrieved) and MRR. OCR reports micro-averaged Unicode NFC case-sensitive CER and whitespace-tokenized WER. Face scoring reports false-accept/reject rates at the declared threshold. Missing or duplicate predictions, unknown photo IDs, malformed scores and generation mismatches fail.

To score actual OCR/people runs, add `ocr: [{"id": "...", "text": "..."}]` and `people: [{"id": "...", "cosine": 0.5}]` to the inference producer's run JSON with the same provenance/digests. Record the source commit and runtime implementing crop/recognition/alignment; hand-entered guesses are not inference evidence.

For phone performance, capture cold process/session startup separately, at least 30 warm samples, p50/p95, peak PSS/native/Java memory, model-cache bytes, index bytes, charging/battery state and sustained thermal status on the actual Fold. Record every unsupported operator and actual provider partition/fallback, including all-CPU runs. [Backend acceptance policy](../backend-policy-v1.json) requires paired CPU/accelerator outputs for the entire pipeline before accepting an accelerator. No timing, RAM, battery or thermal value is inferred from file size.

## Shared sensitive-classifier slice

Add `sensitive: [{"id": "s-ru001", "photoId": "photo001", "language": "ru", "sensitive": true, "scenarioId": "..."}]`. Language identifies the photo's reviewed RU/EN usage/context slice (including text-bearing scenes), not a classifier text input. Include at least 30 RU and 30 EN test fixtures, each slice with at least 15 adult NSFW positives and 15 safe negatives, distinct from a calibration split of at least the same size. Every photo must have a real digest, documented license/consent and reviewed label; no actual sensitive photos are stored in Git. Use the fixed bilingual scenario inventory in `sensitive-scenarios-v1.json` to cover ordinary photos, beach/sports, skin-like textures, classical art, drawings, screenshots, low light, crops, and explicitly out-of-scope private documents. Out-of-scope/ambiguous items have a separate manual-review label and cannot silently count as correctly screened safe.

Run `run_sensitive.py --dataset /external/fixtures/dataset.json --output /external/results/sensitive.json`. It executes the exact shared ONNX classifier and publisher timm preprocessing and records probabilities without making a safe/sensitive decision. Fix `sensitiveThreshold` only from a separately hashed, reviewed calibration split before test scoring; retain the chosen operating-point rationale, calibration false negatives/positives and disjoint photo SHA lists in `sensitiveCalibration`. No default threshold is provided. Automatic safe decisions remain disabled without that reviewed calibration. A value selected on the test set invalidates test evidence.

`evaluate.py` scores the recorded run, reporting false-negative and false-positive rates separately for RU/EN at the declared threshold; incomplete/non-finite predictions fail. Report sample counts and uncertainty, include all missed detections, and do not copy publisher accuracy into Lik results. Review misses before accepting a release threshold. Store the accepted threshold with classifier fingerprint and dataset/calibration hashes, never as a mutable global preference. Physical phone performance and biometric/quarantine enforcement are separate acceptance steps.
