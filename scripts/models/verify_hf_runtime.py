#!/usr/bin/env python3
"""Validate HF runtime candidates against pinned publishers; no quality claim."""
import argparse
import json
from pathlib import Path
import subprocess

from artifacts import verify_file
from prepare import source_directory, source_info, inspect_model, COMPONENTS, ROOT


def normalized(value):
    import numpy as np
    value = np.asarray(value, dtype=np.float32)
    norm = np.linalg.norm(value, axis=-1, keepdims=True)
    if not np.isfinite(value).all() or np.any(norm <= 0):
        raise ValueError("Non-finite or zero embedding")
    return value / norm


def project_multilingual(hidden, mask, weight):
    import numpy as np
    expanded = np.asarray(mask)[..., None]
    mean = (hidden * expanded).sum(1) / expanded.sum(1).clip(min=1e-9)
    return normalized(mean @ weight.T)


def validate_component(cache, component, requests, candidate_directory="hf-runtime-candidates", evidence_suffix=""):
    import numpy as np
    import torch
    import onnxruntime as ort
    files = [f for f in requests if f["path"].startswith(component + "/")]
    if not files:
        raise ValueError("No pinned candidate files")
    for file in files:
        verify_file(cache / candidate_directory, file)
    source = source_directory(cache, COMPONENTS[component])
    for file in source_info(COMPONENTS[component])["files"]:
        verify_file(source, file)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    torch.manual_seed(0)
    torch.set_num_threads(4)
    evidence = {"component": component, "models": {}, "parity": {}, "qualityBenchmark": False}
    if component.startswith("ocr-"):
        file = files[0]
        path = cache / candidate_directory / file["path"]
        out = cache / "research/hf-delivery" / (component + "-paddle-parity.json")
        subprocess.run([str(cache / "paddle-venv/bin/python"), str(ROOT / "scripts/models/paddle_parity.py"),
                        "--source", str(source), "--model", str(path), "--component", component,
                        "--output", str(out)], check=True)
        shape = [1, 3, 48, 320] if component == "ocr-cyrillic-rec-v1" else [1, 3, 96, 160]
        evidence["models"][path.name] = inspect_model(path, {"x": shape}, informative=True)
        evidence["parity"][path.name] = json.loads(out.read_text())
    elif component in ("yunet-v1", "sface-v1"):
        path = cache / candidate_directory / files[0]["path"]
        evidence["models"][path.name] = inspect_model(path)
        evidence["parity"][path.name] = {"method": "byte-identical original publisher ONNX", "verified": True}
    else:
        from transformers import AutoModel, AutoTokenizer, CLIPVisionModelWithProjection, SiglipModel
        from safetensors.torch import load_file
        if component == "clip-image-v1":
            model = CLIPVisionModelWithProjection.from_pretrained(str(source), local_files_only=True, attn_implementation="eager").eval()
        elif component == "multilingual-text-v1":
            model = AutoModel.from_pretrained(str(source), local_files_only=True, attn_implementation="eager").eval()
            projection = load_file(str(source / "2_Dense/model.safetensors"))["linear.weight"].numpy()
            tokenizer = AutoTokenizer.from_pretrained(str(source), local_files_only=True)
        elif component == "sensitive-v1":
            import timm
            cfg = json.loads((source / "config.json").read_text())
            model = timm.create_model(cfg["architecture"], pretrained=False, num_classes=2).eval()
            model.load_state_dict(load_file(str(source / "model.safetensors")), strict=True)
            for block in model.blocks:
                block.attn.fused_attn = False
        else:
            model = SiglipModel.from_pretrained(str(source), local_files_only=True, attn_implementation="eager").eval()
            tokenizer = AutoTokenizer.from_pretrained(str(source), local_files_only=True)
        for file in files:
            path = cache / candidate_directory / file["path"]
            session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
            is_text = component == "multilingual-text-v1" or path.name == "text.onnx"
            output_names = {i.name for i in session.get_outputs()}
            primary_output = "last_hidden_state" if component == "multilingual-text-v1" else "logits" if component == "sensitive-v1" else "pooler_output" if "pooler_output" in output_names else "image_embeds"
            if primary_output not in output_names:
                raise ValueError("Expected publisher feature output is missing")
            cosine, errors = [], []
            if is_text:
                length = 128 if component == "multilingual-text-v1" else 64
                queries = json.loads((ROOT / "models/evaluation/queries-v1.json").read_text())["queries"]
                for query in queries:
                    tokens = tokenizer(query["text"], max_length=length, padding="max_length", truncation=True, return_tensors="pt")
                    feed = {i.name: tokens[i.name].numpy().astype(np.int64) for i in session.get_inputs()}
                    with torch.inference_mode():
                        if component == "multilingual-text-v1":
                            hidden = model(input_ids=tokens["input_ids"], attention_mask=tokens["attention_mask"]).last_hidden_state.numpy()
                            expected = project_multilingual(hidden, tokens["attention_mask"].numpy(), projection)
                        else:
                            expected = normalized(model.text_model(**{name: torch.from_numpy(value) for name, value in feed.items()}).pooler_output.numpy())
                    raw = session.run([primary_output], feed)[0]
                    actual = project_multilingual(raw, tokens["attention_mask"].numpy(), projection) if component == "multilingual-text-v1" else normalized(raw)
                    if actual.shape != expected.shape:
                        raise ValueError("Embedding shapes differ; broadcasting is forbidden")
                    cosine.append(float(np.sum(actual * expected)))
                    errors.append(float(np.max(np.abs(actual - expected))))
                shapes = {i.name: [1, length] for i in session.get_inputs()}
            else:
                size = 384 if component == "sensitive-v1" else 224 if component == "clip-image-v1" else model.config.vision_config.image_size
                values = [torch.zeros(1, 3, size, size), torch.randn(1, 3, size, size), torch.linspace(-1, 1, 3 * size * size).reshape(1, 3, size, size)]
                for value in values:
                    with torch.inference_mode():
                        expected = model(value).softmax(-1).numpy() if component == "sensitive-v1" else normalized(model(pixel_values=value).image_embeds.numpy()) if component == "clip-image-v1" else normalized(model.vision_model(pixel_values=value).pooler_output.numpy())
                    dtype = np.float16 if session.get_inputs()[0].type == "tensor(float16)" else np.float32
                    raw = session.run([primary_output], {session.get_inputs()[0].name: value.numpy().astype(dtype)})[0].astype(np.float32)
                    if component == "sensitive-v1":
                        exponent = np.exp(raw - raw.max(-1, keepdims=True))
                        actual = exponent / exponent.sum(-1, keepdims=True)
                        if not np.allclose(actual, expected, atol=1e-4, rtol=1e-3):
                            raise ValueError("Classifier publisher probability parity failed")
                        cosine.append(float(np.sum(normalized(actual) * normalized(expected))))
                    else:
                        actual = normalized(raw)
                        if actual.shape != expected.shape:
                            raise ValueError("Embedding shapes differ; broadcasting is forbidden")
                        cosine.append(float(np.sum(actual * expected)))
                    errors.append(float(np.max(np.abs(actual - expected))))
                shapes = {session.get_inputs()[0].name: [1, 3, size, size]}
            if not np.isfinite(cosine).all() or min(cosine) < .98:
                raise ValueError(f"Publisher parity rejected {component}/{path.name}: minimum cosine {min(cosine)}")
            evidence["models"][path.name] = inspect_model(path, shapes)
            evidence["models"][path.name]["primaryOutput"] = primary_output
            evidence["parity"][path.name] = {"samples": len(cosine), "fixture": "fixed 30 RU + 30 EN queries" if is_text else "zero/random/gradient synthetic image tensors",
                                             "minimumCosine": min(cosine), "maximumAbsoluteError": max(errors), "minimumAcceptedCosine": .98,
                                             "publisher": COMPONENTS[component], "publisherRevision": source_info(COMPONENTS[component])["revision"], "qualityBenchmark": False}
    target = cache / "research/hf-delivery" / (component + evidence_suffix + "-validation.json")
    target.write_text(json.dumps(evidence, indent=2) + "\n")
    print("ACCEPTED HF candidate", component, json.dumps(evidence["parity"]), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--requests", type=Path, default=ROOT / "models/hf-delivery-v1.json")
    parser.add_argument("--component", choices=list(COMPONENTS))
    parser.add_argument("--candidate-directory", default="hf-runtime-candidates")
    parser.add_argument("--evidence-suffix", default="")
    args = parser.parse_args()
    requests = json.loads(args.requests.read_text())
    if isinstance(requests, dict):
        requests = requests["artifacts"]
    requests = [file for file in requests if file["path"].endswith(".onnx")]
    for component in [args.component] if args.component else COMPONENTS:
        validate_component(args.cache, component, requests, args.candidate_directory, args.evidence_suffix)


if __name__ == "__main__":
    main()
