# Immutable downloadable model presets

Compact uses CLIP ViT-B/32 with aligned multilingual text; Balanced (selected by default) uses SigLIP 2 Base 224; Extended uses SigLIP 2 Large 256 (FP32 image tower, FP16 text tower after Android rejected the FP16 image auxiliary output). All share Cyrillic PP-OCRv5 recognition, YuNet/SFace and one Marqo ViT-Tiny sensitive classifier. Compact/Balanced share the mobile OCR detector; Extended uses the server detector.

**Models are downloaded from Hugging Face through Settings in Task 10. No weights or tokenizers belong in an APK, distribution, or Git.** Task 9 provides verified manifests and development evidence; Settings download, gallery inference, indexing and automatic profile activation are not implemented yet. A selected preset is not ready until all required files are downloaded, verified and self-tested and its enabled indexes are ready. The previous active profile keeps serving throughout preparation.

`catalog-v1.json` freezes 29 unique runtime files (12 ONNX graphs), exact immutable HF URLs/revisions/sizes/SHA-256, source provenance, licenses, inspected graph contracts and measured conversion parity. `hf-delivery-v1.json` also pins conversion model cards/configuration/license receipts. `sources-v1.json` pins original publisher weights used for independent validation. `contracts-v1.json` records preprocessing, tokenization, output selection, pooling/projection and normalization. Shared dependencies occur once. Artifact/pipeline fingerprints include the exact contracts; changing bytes or semantics requires a new version after publication.

## Build behavior

All ordinary debug/release/test/distribution builds contain no model payloads. App assets carry the catalog, contracts, backend policy and license notices, with `available=false`, `bundledPayloads=false` and Balanced selected; instrumentation assets contain only exact checked-in Room migration schemas. Builds never read the external model cache or download model payloads. `stageModelMetadata` replaces stale generated assets. The Android Components API registers a payload gate for every app and device-test variant, including every APK output in its artifact directory. Assembly and pre-installation depend on the gate; direct package tasks finalize with it. `assembleDistribution` / `verifyDistributionApks` build and inspect all these variants. No cache is required.

The gate inspects every ZIP entry, not only `assets/` or recognizable weight extensions. Raw resources and unknown blobs are forbidden. Model metadata/schema bytes must match repository trust anchors. PNGs, native libraries and opaque dependency resources must match the explicitly reviewed [content policy](../scripts/models/apk-content-policy-v1.json); Every compiled XML/resource table, DEX and baseline profile must match one complete, source-bound set of reviewed AAPT2/D8/R8 receipts, then pass authoritative AAPT2/ART parsing. Catalog artifact fingerprints are rejected at any renamed path. ZIP prefixes, trailers, comments, nonzero extra fields, hidden deflate bytes and nonzero deleted-entry padding are rejected; only fully consumed unique v2 signing records with cryptographic verification and empty alignment padding are allowed outside entries. Both physical and uncompressed APK sizes are capped at 250 MiB. Legitimate launcher PNGs and AndroidX resources remain allowed without modifying their bytes.

Version-control metadata admits only AGP’s fixed Git/error fields; commit changes alone do not require a new policy. Android source/schema/Gradle changes require deliberate compiler-receipt review; new dependency/resource bytes also require content-policy review with provenance. The [APK policy and refresh workflow](../docs/APK_PAYLOAD_POLICY.md) documents compiler-only candidate preparation, pinned macOS/Linux SDK parsers and unsupported signing formats. The build must never generate its own allowlist from packaged files or the model cache. Regression tests read actual HF tokenizer and YuNet bytes from `LIK_MODEL_CACHE` without copying them into Git; explicitly setting that variable makes missing regression artifacts fail instead of skip.

```sh
./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest assembleDistribution
python3 scripts/models/inspect_apk.py --directory app/build/outputs/apk/debug --directory app/build/outputs/apk/release
python3 scripts/models/inspect_apk.py --kind android-test --directory app/build/outputs/apk/androidTest/debug
```

`-PlikModelPython=/absolute/python3` selects the Python standard-library verifier, which also requires the reviewed Android SDK build-tools 36.0.0 parsers. ORT Android 1.29.0 is a runtime library, not model data; it is present in the app for the Android CPU acceptance probe but is not yet called by gallery features.

## Reproduce downloaded artifacts outside Git

Python 3.11.16 and the exact dependencies are locked. Public HF downloads need no login. The CLI is `hf`. The scripts accept only the frozen catalog; they cannot import arbitrary URLs or silently update hashes.

```sh
export LIK_MODEL_CACHE="$HOME/Library/Caches/Lik/model-artifacts"
uv venv --python 3.11.16 "$LIK_MODEL_CACHE/venv"
uv pip install --python "$LIK_MODEL_CACHE/venv/bin/python" -r scripts/models/requirements-export.lock
uv venv --python 3.11.16 "$LIK_MODEL_CACHE/paddle-venv"
uv pip install --python "$LIK_MODEL_CACHE/paddle-venv/bin/python" -r scripts/models/requirements-paddle.lock
HF_HUB_DISABLE_XET=1 "$LIK_MODEL_CACHE/venv/bin/python" scripts/models/download_hf.py --cache "$LIK_MODEL_CACHE" --hf "$LIK_MODEL_CACHE/venv/bin/hf"
python3 scripts/models/artifacts.py verify --cache "$LIK_MODEL_CACHE"
"$LIK_MODEL_CACHE/venv/bin/python" scripts/models/verify_tokenizers.py --cache "$LIK_MODEL_CACHE"
```

`download_hf.py --range-workers 16` is an optional bounded HTTPS fallback to the identical pinned URLs, checking every Content-Range and the final size/SHA-256. This developer helper is not the future Android durable Range/journal implementation. It creates `hf-runtime-candidates/`, verifies all files and atomically publishes `hf-runtime/`; incomplete or mismatched candidates cannot become verified. Existing complete runtime caches are reverified. Synthetic host outputs are development-only fixtures generated by validation, not extra product downloads.

For independent publisher comparison, first download originals, then validate the HF candidates:

```sh
HF_HUB_DISABLE_XET=1 "$LIK_MODEL_CACHE/venv/bin/python" scripts/models/prepare.py download --cache "$LIK_MODEL_CACHE"
"$LIK_MODEL_CACHE/venv/bin/python" scripts/models/verify_hf_runtime.py --cache "$LIK_MODEL_CACHE"
"$LIK_MODEL_CACHE/venv/bin/python" scripts/models/freeze_hf_catalog.py --cache "$LIK_MODEL_CACHE" --output /external/review-candidate.json
```

`freeze_hf_catalog.py` produces a separate review candidate; builds never run it. HF conversion repositories do not disclose every export command/tool version. Their actual graph producer/opset metadata and model-card/configuration receipts are recorded, and their outputs are independently compared against hash-verified publisher weights. The original Lik reference exports remain reproducible with `prepare.py prepare` and `prepare.py publish`, using **prepared-reference-v1.json**, never the download catalog. They live in external `prepared/`; verify with `artifacts.py verify --catalog models/prepared-reference-v1.json`. Reference exports are not download artifacts or APK inputs. Exact wrapper/export commands and tool locks are in `prepare.py` and `requirements-*.lock`.

## Evaluation and Android CPU evidence

[30 English + 30 Russian queries](evaluation/queries-v1.json), [golden tokenizer vectors](evaluation/tokenizer-golden-v1.json) and the [external dataset contract](evaluation/DATASET_CONTRACT.md) define real evaluation inputs. Licensed photo/OCR/face/sensitive fixtures and sensitive calibration are not supplied; no task-quality or Fold performance score is claimed. In particular, SigLIP 2 uses the actual cased GemmaTokenizerFast behavior, selects `pooler_output` by name and applies external L2 normalization. Compact text applies attention-masked mean, the pinned 768→512 dense weight, and L2 normalization. Marqo returns logits; apply stable softmax, class zero NSFW. Its release threshold remains unset.

```sh
"$LIK_MODEL_CACHE/venv/bin/python" scripts/models/run_retrieval.py --dataset /external/fixtures/dataset.json --profile balanced-v1 --output /external/results/balanced-run.json
"$LIK_MODEL_CACHE/venv/bin/python" scripts/models/run_sensitive.py --dataset /external/fixtures/dataset.json --output /external/results/sensitive-run.json
python3 scripts/models/evaluate.py --dataset /external/fixtures/dataset.json --predictions /external/results/balanced-run.json --output /external/results/balanced-scores.json
"$LIK_MODEL_CACHE/venv/bin/python" -m unittest discover -s scripts/models/tests -v
```

The Android probe takes explicitly provisioned development files in the debug emulator's private `files/model-probe`, checks every model and reference hash, runs all 12 graphs on ORT CPU, compares complete outputs with host references, and samples PSS every 100 ms. APKs still contain zero models. Run `verify_hf_runtime.py` to create the hash-checked synthetic references in `hf-runtime-candidates/`, and install debug/test APKs onto an explicitly selected emulator before provisioning. Product downloads in `hf-runtime/` do not require those references; no physical device is selected automatically.

```sh
python3 scripts/models/provision_android_probe.py --serial emulator-5580 --cache "$LIK_MODEL_CACHE"
ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.mesteriis.lik.ModelArtifactSmokeTest -Pandroid.testInstrumentationRunnerArguments.externalModels=true -Pandroid.testInstrumentationRunnerArguments.requireModels=true
```

Without explicit provisioning the external-model test skips; metadata/no-payload assertions still run. `requireModels=true` fails if provisioning was not requested. The probe is not Android tokenizer/preprocessor/postprocessor or end-to-end gallery acceptance. Measured load+inference time includes PSS sampling overhead; it is not warm latency. [Samsung acceleration](../docs/SAMSUNG_BACKENDS.md) requires separate physical Fold evidence; CPU is mandatory. [AiGate](../docs/AIGATE_INTEGRATION.md) is optional Task 10 work. [Task 13](../docs/SENSITIVE_MEDIA.md) implements quarantine and biometric hiding; the current gallery does not enforce those future policies.

MIT/Apache-2.0 notices are retained independently from application licenses. See [provenance](../docs/MODEL_PROVENANCE.md) for source/converter identities, verified bytes and measured versus unmeasured evidence. No reference application/runtime code or downloaded weights are copied into Git.
