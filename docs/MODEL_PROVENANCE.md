# Pinned model provenance and preparation

Task 9 freezes publisher sources and actual downloaded HF bytes in [the catalog](../models/catalog-v1.json). Weights and export work remain outside Git. This records artifact preparation and synthetic CPU checks; retrieval/OCR/face/sensitive accuracy, threshold calibration and physical Fold acceptance remain unmeasured. Settings downloads/gallery runtime/indexing and biometric hiding are separate Tasks 10–13.

## Publisher source identities

| Publisher repository | Immutable commit | Declared model license |
| --- | --- | --- |
| [google/siglip2-large-patch16-256](https://huggingface.co/google/siglip2-large-patch16-256/tree/787800c8990e6f058423089178e718139608408c) | `787800c8990e6f058423089178e718139608408c` | apache-2.0 |
| [google/siglip2-base-patch16-224](https://huggingface.co/google/siglip2-base-patch16-224/tree/75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2) | `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2` | apache-2.0 |
| [PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec](https://huggingface.co/PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec/tree/712d2d65556ccc1ea7b5d2bb232b018838b6a3ab) | `712d2d65556ccc1ea7b5d2bb232b018838b6a3ab` | apache-2.0 |
| [PaddlePaddle/PP-OCRv5_mobile_det](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det/tree/0d63e78e2b680928f6b1747d76a08db6e645efb7) | `0d63e78e2b680928f6b1747d76a08db6e645efb7` | apache-2.0 |
| [PaddlePaddle/PP-OCRv5_server_det](https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det/tree/ca867c897ecbca8873081573a802ad70d499cb94) | `ca867c897ecbca8873081573a802ad70d499cb94` | apache-2.0 |
| [openai/clip-vit-base-patch32](https://huggingface.co/openai/clip-vit-base-patch32/tree/3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268) | `3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268` | MIT (OpenAI CLIP LICENSE) |
| [sentence-transformers/clip-ViT-B-32-multilingual-v1](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/tree/58edf8cada9e398793dca955574a48cbb7f18be2) | `58edf8cada9e398793dca955574a48cbb7f18be2` | apache-2.0 |
| [opencv/opencv_zoo](https://github.com/opencv/opencv_zoo/tree/47534e27c9851bb1128ccc0102f1145e27f23f98) | `47534e27c9851bb1128ccc0102f1145e27f23f98` | MIT: face_detection_yunet; Apache-2.0: face_recognition_sface (directory LICENSE files) |
| [Marqo/nsfw-image-detection-384](https://huggingface.co/Marqo/nsfw-image-detection-384/tree/0c26ec22111b83f106d72a55f611ec35962bcb65) | `0c26ec22111b83f106d72a55f611ec35962bcb65` | apache-2.0 |

Every selected file has its exact immutable URL, positive source byte size and full SHA-256 in [sources-v1.json](../models/sources-v1.json). Large HF objects use publisher LFS hashes and are checked after download; small metadata/license files were downloaded and hashed. Repository/application licenses are not substituted for model-license declarations. MIT and Apache-2.0 notices are packaged as metadata; weights and tokenizers are downloaded separately. OpenAI’s MIT notice is pinned to [CLIP LICENSE at d05afc4](https://github.com/openai/CLIP/blob/d05afc436d78f1c48dc0dbf8e5980a9d471f35f6/LICENSE); OpenCV directory licenses are pinned in both source and HF receipt locks.

## Downloaded runtime ONNX files

Each link resolves the exact immutable file; these bytes were downloaded and SHA-256 verified. Conversion repositories are named explicitly. Original publisher identities above remain the independent numerical reference.

| Runtime artifact / HF converter | Bytes | SHA-256 |
| --- | ---: | --- |
| [clip-image-v1/image.onnx](https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/d15189d7028b43f1d3e65039190477f6af591c2a/onnx/vision_model_fp16.onnx) · Xenova/clip-vit-base-patch32 | 176080659 | `35c4e0fb0aeee527dcde1693520b214a34424a786babd530f35366bad5844efd` |
| [multilingual-text-v1/model.onnx](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/58edf8cada9e398793dca955574a48cbb7f18be2/onnx/model_qint8_arm64.onnx) · sentence-transformers/clip-ViT-B-32-multilingual-v1 | 135336307 | `3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7` |
| [siglip2-base-v1/image.onnx](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/vision_model_fp16.onnx) · onnx-community/siglip2-base-patch16-224-ONNX | 186039516 | `a1959f7bd3993a607e48839f6d01e25b876fe76afda301b028b78eef68aabd95` |
| [siglip2-base-v1/text.onnx](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/text_model_fp16.onnx) · onnx-community/siglip2-base-patch16-224-ONNX | 564862230 | `711da56ada0a4aa11c7dd3320df741081a3cae4f0ae1b5e5c6d5b294738d0eb0` |
| [siglip2-large-v1/image.onnx](https://huggingface.co/onnx-community/siglip2-large-patch16-256-ONNX/resolve/c03fb63ddd5fe363212cbd08110f92a35250db6a/onnx/vision_model.onnx) · onnx-community/siglip2-large-patch16-256-ONNX | 1264345246 | `a68762da4fab7335558d4713c77e42d8abe23dcd48d38b5dbe1c00765c07ca56` |
| [siglip2-large-v1/text.onnx](https://huggingface.co/onnx-community/siglip2-large-patch16-256-ONNX/resolve/c03fb63ddd5fe363212cbd08110f92a35250db6a/onnx/text_model_fp16.onnx) · onnx-community/siglip2-large-patch16-256-ONNX | 1131647648 | `bf7195836ddeb5beea9277e689ffa8142cbdbb375e2e4b699487f50234884280` |
| [ocr-mobile-det-v1/model.onnx](https://huggingface.co/OllmOne/PP-OCRv5/resolve/c829ee2e168d6b90a5d9585ca564db19718afb80/pp-ocrv5_mobile_det.onnx) · OllmOne/PP-OCRv5 | 4826518 | `1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039` |
| [ocr-server-det-v1/model.onnx](https://huggingface.co/OllmOne/PP-OCRv5/resolve/c829ee2e168d6b90a5d9585ca564db19718afb80/pp-ocrv5_server_det.onnx) · OllmOne/PP-OCRv5 | 88116836 | `9a910baffbefb807ff2f7bfaa72910e3e470bd17014d798386d87bb46f442839` |
| [ocr-cyrillic-rec-v1/model.onnx](https://huggingface.co/OllmOne/PP-OCRv5/resolve/c829ee2e168d6b90a5d9585ca564db19718afb80/cyrillic_pp-ocrv5_mobile_rec.onnx) · OllmOne/PP-OCRv5 | 8076390 | `a18d96d7c8d73d90f2ed056549caa1de3a8e6cb744cccba16cd593ea8cd2d569` |
| [yunet-v1/model.onnx](https://huggingface.co/opencv/face_detection_yunet/resolve/3cc26e7f1014a5ee5d74a42acee58bafc9d0a310/face_detection_yunet_2023mar.onnx) · opencv/face_detection_yunet | 232589 | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` |
| [sface-v1/model.onnx](https://huggingface.co/opencv/face_recognition_sface/resolve/3d7082438a6e4551e840c9b2bb60b71e8da4b524/face_recognition_sface_2021dec.onnx) · opencv/face_recognition_sface | 38696353 | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` |
| [sensitive-v1/model.onnx](https://huggingface.co/Marqo/nsfw-image-detection-384/resolve/b2d8f0c3abe6e05062288460ecf58e42ea690e84/onnx/model.onnx) · Marqo/nsfw-image-detection-384 | 22527617 | `7b81155313d894bba3a3b9bace059a6da2a0c509d10cea43fcb0b3c5a4edd26d` |

The deduplicated runtime set is **3,663,983,902 bytes in 29 files**, including 12 ONNX graphs, tokenizers, the multilingual projection, preprocessing files and Cyrillic dictionary. Compact requires **390,318,893** bytes; Balanced **863,924,753**; Extended **2,592,306,219**. Profile totals overlap shared artifacts and must not be summed. These are download/storage bytes, not APK or RAM measurements. Synthetic `.smoke.f32` references are developer-only and are not required product downloads.

## Source and converter provenance

Xenova CLIP and onnx-community SigLIP 2 cards identify their original OpenAI/Google base models. Their metadata does not declare a new license; the verified derivative weights retain the publisher MIT/Apache-2.0 terms, with notices preserved. `hf-delivery-v1.json` pins exact converter README/configuration/quantization receipts and graph producer metadata is in the catalog. Upstream export commands and complete tool environments are **not disclosed**; they are not invented. Lik independently loaded the hash-verified publishers with its locked tools and compared the converted outputs.

The sentence-transformers publisher supplies the ARM64 QInt8 transformer ONNX directly. That file emits 768-dimensional token states, not the final CLIP embedding: Lik applies attention-masked mean, the original `2_Dense/model.safetensors` 768→512 weight and L2 normalization. The projection is itself a pinned HF download.

OllmOne’s PP-OCRv5 is a community conversion with an Apache-2.0 declaration, not a Paddle release. All three selected graphs passed independent Paddle CPU comparison against the pinned original inference weights. Its exact 850-line Cyrillic dictionary equals the original Paddle YAML character list; append space and blank as specified to match 852 output classes. Official OpenCV HF YuNet/SFace graphs are byte-identical to the pinned OpenCV Zoo originals and carry their directory licenses.

Marqo’s ONNX is pinned at `b2d8f0c3abe6e05062288460ecf58e42ea690e84`, the HF ONNX export bot commit on behalf of intellichain in unmerged PR #5, parent `0c26ec22111b83f106d72a55f611ec35962bcb65`. It is not described as a merged publisher release. FP32 logits followed by stable softmax passed direct comparison to the original publisher safetensors. The ordered labels are NSFW/SFW. Threshold calibration remains unperformed.

Historical official Google Base commit `5ffaac51d5e2f3367f7dab0cad4be4cb07c0caa2` exposes a combined ONNX tree; current pinned publisher heads used here contain original weights. Verified community per-tower files permit loading image/text sessions separately. No model family was substituted.

## Numerical conversion evidence

Host reference: Python 3.11.16, PyTorch 2.8.0, Transformers 4.55.4, ONNX Runtime 1.22.1, timm 1.0.19. Paddle reference: Paddle 3.3.1, Paddle2ONNX 2.1.0. Full dependency locks and executable commands are in [models/README.md](../models/README.md) and `scripts/models/requirements-*.lock`. Android runtime is independently pinned to ORT 1.29.0. All selected graphs are self-contained, use standard ONNX domains and expose FP32/INT64 I/O; actual dynamic axes, operators, producer versions and measured smoke shapes are catalogued. Runtime callers restrict inputs to the documented batch/image/sequence contracts.

| Tower | Samples | Minimum normalized cosine to publisher | Maximum absolute difference |
| --- | ---: | ---: | ---: |
| clip-image-v1/image.onnx | 3 | 0.9999988079 | 0.0003557279706 |
| multilingual-text-v1/model.onnx | 60 | 0.9968038797 | 0.02289769053 |
| siglip2-base-v1/image.onnx | 3 | 0.9999993443 | 0.000247657299 |
| siglip2-base-v1/text.onnx | 60 | 0.9999994636 | 0.0002310574055 |
| siglip2-large-v1/image.onnx | 3 | 0.9999998808 | 6.793998182e-07 |
| siglip2-large-v1/text.onnx | 60 | 0.9999997616 | 0.0001536607742 |
| sensitive-v1/model.onnx | 3 | 0.9999999404 | 2.384185791e-07 |

Image/classifier parity used zero/random/gradient tensors; text parity used all 30 Russian and 30 English fixed queries. The fixed cosine gate was 0.98. Marqo additionally passed direct probability allclose at atol 1e-4 / rtol 1e-3. OCR mobile/server/recognizer maximum absolute errors were 1.1070e-7 / 8.5463e-8 / 8.5235e-6 with direct Paddle/ORT allclose. Faces require no numerical conversion because the HF bytes equal the publisher originals. These checks are conversion evidence, **not** retrieval/OCR/face/sensitive accuracy, complete preprocessing parity, calibrated thresholds or Fold speed.

SigLIP outputs include `last_hidden_state` before `pooler_output`; the runner selects the latter by name and forbids shape broadcasting. Both semantic families normalize outside ONNX. SigLIP uses actual cased GemmaTokenizerFast length 64 and no attention mask; golden IDs cover case, whitespace, punctuation and truncation. Paddle detector resize_long=960 uses resize_type2 (ceil to 128 after truncation), not a guessed 32-pixel variant. The OCR Android smoke uses a reduced 96×160 detector tensor; it is not the product-size memory benchmark.

## Reproduction, packaging and superseded experiment

`download_hf.py` obtains frozen HF files into an external cache; `verify_hf_runtime.py` verifies source hashes before deserialization and checks publisher parity. `artifacts.py verify` rejects any missing or changed runtime file. `stageModelMetadata` never reads weights. All debug/release/distribution APKs contain metadata/licenses only, with no model or tokenizer payload; `assembleDistribution` rejects such payloads even if a complete developer cache is present. The 250-MiB gate also rejects stale unreferenced data left in incremental ZIP files. Build/check commands and Android test provisioning are in [models/README.md](../models/README.md).

An earlier bundling experiment produced about 2.95 GB of custom reference exports and a large APK before the user changed delivery to Settings downloads. It is **superseded and is not current distribution acceptance**. The reference manifest and locked exporter remain in `prepared-reference-v1.json` / `prepare.py`; their bytes are external and never shipped. Reference SigLIP INT8 candidates failed the fixed 0.98 gate (Base image 0.9239/0.9295; text 0.9318) and were rejected. Custom FP16-storage/FP32-compute exports passed, then the HF per-tower artifacts were independently validated for the new delivery requirement. Android then rejected Large image FP16: last_hidden_state cosine 0.9401959606, despite pooled embedding 0.9974879621; disabling graph optimizations did not change it. The release candidate therefore uses published FP32 Large image (host cosine 0.9999998808) with the independently passing FP16 text tower. The fixed gate was not relaxed. Exact rejected FP16 size/hash is recorded in the verification report; it is not a selected runtime artifact.

Initial HF/Xet downloads stalled despite an installed Xet library. Disabling Xet and using bounded 16-worker 8-MiB HTTPS ranges to the same immutable URLs restored transfer. Every interval and final full SHA was checked; no signed storage URLs or credentials are committed. Interrupted files are not accepted as complete.

## Unmeasured acceptance and future integration

[The external dataset contract](../models/evaluation/DATASET_CONTRACT.md) defines fixed licensed/consented photo, OCR, face and RU/EN sensitive slices. No real dataset run, task accuracy, sensitive threshold, physical Fold latency/RAM/thermal score or NNAPI partition evidence is claimed. Android CPU smoke results are recorded in [verification](VERIFICATION.md) and [machine-readable evidence](../models/evidence/android-cpu-v1.json), including the exact catalog/probe/runtime-library hashes and sampled process memory. All 12 graphs passed on the 4-GB API 37 / 16-KiB emulator. Largest sampled process PSS was 1,704,590 KiB during the Large text session; this is a sampled smoke observation, not a guaranteed memory ceiling.

[Samsung research](SAMSUNG_BACKENDS.md) establishes no supported public Galaxy AI foundation-model API for this integration. CPU is mandatory; NNAPI/NPU experiments need measured per-pipeline parity and actual Fold assignments/unsupported-op/fallback/resource evidence. [AiGate](AIGATE_INTEGRATION.md) remains a separate Task 10 opt-in loopback consumer. [Task 13](SENSITIVE_MEDIA.md) implements quarantine and BIOMETRIC_STRONG reveal; classifier availability does not imply current privacy enforcement.
