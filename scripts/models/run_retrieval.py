#!/usr/bin/env python3
"""Run real CPU retrieval on an externally supplied, hash-locked photo fixture set."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import time

from artifacts import digest, verify, verify_file
from verify_hf_runtime import normalized, project_multilingual
from verify_tokenizers import load_tokenizer


def main():
    import numpy as np
    import onnxruntime as ort
    from PIL import Image, ImageOps
    from transformers import CLIPImageProcessor, SiglipImageProcessor
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--profile", choices=["compact-v1", "balanced-v1", "extended-v1"], required=True)
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("LIK_MODEL_CACHE", Path.home() / "Library/Caches/Lik/model-artifacts")))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    catalog_path = Path(__file__).resolve().parents[2] / "models/catalog-v1.json"
    catalog = json.loads(catalog_path.read_text())
    verify(catalog, args.cache)
    dataset = json.loads(args.dataset.read_text())
    if len({p["id"] for p in dataset["photos"]}) != len(dataset["photos"]):
        raise ValueError("Duplicate photo identities")
    for photo in dataset["photos"]:
        verify_file(args.dataset.parent, photo)
    prepared = args.cache / "hf-runtime"
    compact = args.profile == "compact-v1"
    image_component = "clip-image-v1" if compact else "siglip2-base-v1" if args.profile == "balanced-v1" else "siglip2-large-v1"
    text_component = "multilingual-text-v1" if compact else image_component
    tokenizer_component = text_component if compact else "siglip2-tokenizer-v1"
    processor_class = CLIPImageProcessor if compact else SiglipImageProcessor
    processor = processor_class.from_pretrained(str(prepared / image_component), local_files_only=True)
    tokenizer = load_tokenizer(prepared / tokenizer_component, tokenizer_component)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    image_path = prepared / image_component / "image.onnx"
    text_path = prepared / text_component / ("model.onnx" if compact else "text.onnx")
    started = datetime.now(timezone.utc).isoformat()
    begin = time.perf_counter()
    image_session = ort.InferenceSession(str(image_path), options, providers=["CPUExecutionProvider"])
    image_load_ms = (time.perf_counter() - begin) * 1000
    vectors, image_times = [], []
    for photo in dataset["photos"]:
        begin = time.perf_counter()
        with Image.open(args.dataset.parent / photo["path"]) as original:
            oriented = ImageOps.exif_transpose(original).convert("RGB")
            pixels = processor(images=oriented, return_tensors="np")["pixel_values"]
        output_name = "image_embeds" if compact else "pooler_output"
        vectors.append(normalized(image_session.run([output_name], {"pixel_values": pixels})[0])[0])
        image_times.append((time.perf_counter() - begin) * 1000)
    del image_session
    matrix = np.stack(vectors)
    begin = time.perf_counter()
    text_session = ort.InferenceSession(str(text_path), options, providers=["CPUExecutionProvider"])
    text_load_ms = (time.perf_counter() - begin) * 1000
    projection = None
    if compact:
        from safetensors.numpy import load_file
        projection = load_file(str(prepared / text_component / "projection.safetensors"))["linear.weight"]
    predictions, query_times = [], []
    for query in dataset["queries"]:
        begin = time.perf_counter()
        tokens = tokenizer(query["text"], max_length=128 if compact else 64,
                           padding="max_length", truncation=True, return_tensors="np")
        inputs = {info.name: tokens[info.name].astype("int64") for info in text_session.get_inputs()}
        raw = text_session.run(["last_hidden_state" if compact else "pooler_output"], inputs)[0]
        vector = (project_multilingual(raw, inputs["attention_mask"], projection) if compact else normalized(raw))[0]
        if not np.isfinite(vector).all():
            raise ValueError("Non-finite query output")
        scores = matrix @ vector
        order = np.argsort(-scores, kind="stable")
        predictions.append({"id": query["id"], "ranking": [dataset["photos"][i]["id"] for i in order]})
        query_times.append((time.perf_counter() - begin) * 1000)
    report = {"schemaVersion": 1, "datasetSha256": digest(args.dataset), "catalogSha256": digest(catalog_path),
              "profile": args.profile, "device": platform.platform(), "runtime": "onnxruntime==" + ort.__version__,
              "producer": "scripts/models/run_retrieval.py", "startedAt": started, "backend": "CPUExecutionProvider",
              "retrieval": predictions, "timing": {"imageSessionLoadMs": image_load_ms, "textSessionLoadMs": text_load_ms,
              "photoEndToEndMs": image_times, "queryEndToEndMs": query_times,
              "measurementScope": "host CPU, 4 intra-op threads; includes preprocessing; not Android/Fold timing"},
              "memory": {"status": "not-measured"}, "thermal": {"status": "not-measured"}}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
