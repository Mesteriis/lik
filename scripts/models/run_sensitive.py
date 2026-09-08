#!/usr/bin/env python3
"""Run the shared classifier on licensed external fixtures; never choose a threshold."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import time

from artifacts import digest, verify, verify_file


def main():
    import onnxruntime as ort
    import numpy as np
    from PIL import Image, ImageOps
    from timm.data import create_transform
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("LIK_MODEL_CACHE", Path.home() / "Library/Caches/Lik/model-artifacts")))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    catalog_path = Path(__file__).resolve().parents[2] / "models/catalog-v1.json"
    catalog = json.loads(catalog_path.read_text())
    verify(catalog, args.cache)
    dataset = json.loads(args.dataset.read_text())
    photos = {p["id"]: p for p in dataset["photos"]}
    if len(photos) != len(dataset["photos"]):
        raise ValueError("Duplicate photo identities")
    component = args.cache / "hf-runtime/sensitive-v1"
    cfg = json.loads((component / "config.json").read_text())["pretrained_cfg"]
    transform = create_transform(input_size=cfg["input_size"], interpolation=cfg["interpolation"],
                                 mean=cfg["mean"], std=cfg["std"], crop_pct=cfg["crop_pct"],
                                 crop_mode=cfg["crop_mode"], is_training=False)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(component / "model.onnx"), options, providers=["CPUExecutionProvider"])
    started = datetime.now(timezone.utc).isoformat()
    predictions, times = [], []
    for fixture in dataset["sensitive"]:
        photo = photos[fixture["photoId"]]
        path = verify_file(args.dataset.parent, photo)
        begin = time.perf_counter()
        with Image.open(path) as original:
            pixels = transform(ImageOps.exif_transpose(original).convert("RGB")).unsqueeze(0).numpy()
        logits = session.run(["logits"], {"pixel_values": pixels})[0][0]
        if not np.isfinite(logits).all():
            raise ValueError("Non-finite classifier logits")
        exponent = np.exp(logits - logits.max())
        values = exponent / exponent.sum()
        predictions.append({"id": fixture["id"], "score": float(values[0])})
        times.append((time.perf_counter() - begin) * 1000)
    report = {"schemaVersion": 1, "datasetSha256": digest(args.dataset), "catalogSha256": digest(catalog_path),
              "profile": "balanced-v1", "component": "sensitive-v1 (shared identically by all profiles)",
              "device": platform.platform(), "runtime": "onnxruntime==" + ort.__version__,
              "producer": "scripts/models/run_sensitive.py", "startedAt": started,
              "backend": "CPUExecutionProvider", "sensitive": predictions,
              "timing": {"photoEndToEndMs": times, "scope": "host CPU; preprocessing included; not Fold timing"},
              "calibration": "not performed by inference producer", "memory": {"status": "not-measured"},
              "thermal": {"status": "not-measured"}}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
