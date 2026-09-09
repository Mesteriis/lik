#!/usr/bin/env python3
"""Reproduce pinned model artifacts outside Git. See models/README.md."""
import argparse
import gc
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

from artifacts import ArtifactError, digest, verify_file

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = {
    "clip-image-v1": "openai/clip-vit-base-patch32",
    "multilingual-text-v1": "sentence-transformers/clip-ViT-B-32-multilingual-v1",
    "siglip2-base-v1": "google/siglip2-base-patch16-224",
    "siglip2-large-v1": "google/siglip2-large-patch16-256",
    "ocr-mobile-det-v1": "PaddlePaddle/PP-OCRv5_mobile_det",
    "ocr-server-det-v1": "PaddlePaddle/PP-OCRv5_server_det",
    "ocr-cyrillic-rec-v1": "PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec",
    "yunet-v1": "opencv/opencv_zoo",
    "sface-v1": "opencv/opencv_zoo",
    "sensitive-v1": "Marqo/nsfw-image-detection-384",
}


def source_info(repo):
    return next(s for s in json.loads((ROOT / "models/sources-v1.json").read_text())["sources"] if s["repo"] == repo)


def source_directory(cache, repo):
    source = source_info(repo)
    return cache / "sources" / repo / source["revision"]


def download(cache, range_workers=0):
    for source in json.loads((ROOT / "models/sources-v1.json").read_text())["sources"]:
        target = source_directory(cache, source["repo"])
        missing = []
        for file in source["files"]:
            try:
                verify_file(target, file)
            except ArtifactError:
                missing.append(file)
        if range_workers and missing:
            from download_ranges import download_ranges
            for file in missing:
                if file["size"] > 8 * 1024 * 1024:
                    download_ranges(file, target / file["path"], workers=range_workers)
                else:
                    download_file(target, file)
        elif source["kind"] == "huggingface" and missing:
            # Do not authenticate; all selected publisher repositories are public.
            subprocess.run([str(Path(sys.executable).parent / "hf"), "download", source["repo"],
                            "--revision", source["revision"], "--local-dir", str(target),
                            "--include", *[f["path"] for f in missing]], check=True)
        elif missing:
            for file in missing:
                download_file(target, file)
        for file in source["files"]:
            verify_file(target, file)
        print("Verified source", source["repo"], flush=True)


def download_file(target, file):
    path = target / file["path"]
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".download")
    with urllib.request.urlopen(file["url"], timeout=120) as remote, temporary.open("wb") as output:
        shutil.copyfileobj(remote, output)
    verify_file(temporary.parent, dict(file, path=temporary.name))
    temporary.replace(path)


def inspect_model(path, input_shapes=None, informative=False):
    import numpy as np
    import onnx
    import onnxruntime as ort
    onnx.checker.check_model(str(path))
    graph = onnx.load(str(path), load_external_data=False)
    domains = {node.domain for node in graph.graph.node}
    if domains - {"", "ai.onnx"}:
        raise ValueError(f"Custom ONNX operator domains: {domains}")
    if any(t.external_data for t in graph.graph.initializer):
        raise ValueError("This inspector requires self-contained ONNX; external tensor data needs an explicit verified dependency contract")
    options = ort.SessionOptions()
    options.log_severity_level = 3
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    inputs = []
    feed = {}
    for info in session.get_inputs():
        shape = (input_shapes or {}).get(info.name, info.shape)
        if not all(isinstance(d, int) and d > 0 for d in shape):
            raise ValueError(f"Specify smoke shape for {info.name}: {shape}")
        dtype = {"tensor(float)": "float32", "tensor(float16)": "float16", "tensor(int64)": "int64"}[info.type]
        count = math.prod(shape)
        if dtype.startswith("float") and informative:
            detector = "-det-v1" in str(path.parent)
            values = np.linspace(0, 255, count, dtype=np.float32).reshape(shape).astype(dtype) if detector else \
                np.linspace(-.75, .75, count, dtype=np.float32).reshape(shape).astype(dtype)
            pattern, value = ("byte-ramp-v1" if detector else "deterministic-ramp-v1"), 0
        elif info.name == "attention_mask":
            values = np.ones(shape, dtype=dtype)
            pattern, value = "ones-v1", 1
        elif dtype.startswith("float"):
            values = np.zeros(shape, dtype=dtype)
            pattern, value = "zeros-v1", 0
        else:
            values = np.zeros(shape, dtype=dtype)
            pattern, value = "zeros-v1", 0
        feed[info.name] = values
        inputs.append({"name": info.name, "type": dtype, "shape": info.shape,
                       "smokeShape": shape, "smokeFill": value, "smokePattern": pattern})
    results = session.run(None, feed)
    if not all(np.isfinite(x).all() and x.size > 0 for x in results):
        raise ValueError("ONNX smoke inference returned empty or non-finite output")
    reference = path.with_name(path.name + ".smoke.f32")
    with reference.open("wb") as output:
        for value in results:
            output.write(value.astype("<f4").tobytes())
    return {"irVersion": graph.ir_version, "producer": {"name": graph.producer_name, "version": graph.producer_version},
            "opsets": {s.domain or "ai.onnx": s.version for s in graph.opset_import},
            "inputs": inputs,
            "outputs": [{"name": info.name, "type": info.type, "shape": info.shape,
                         "smokeShape": list(value.shape)} for info, value in zip(session.get_outputs(), results)],
            "operators": sorted({node.op_type for node in graph.graph.node}),
            "smokeReference": {"file": reference.name, "size": reference.stat().st_size,
                               "sha256": digest(reference), "format": "little-endian-float32-output-order",
                               "comparison": "cosine" if all(i.name in {"embedding", "image_embeds", "text_embeds", "last_hidden_state", "pooler_output"} for i in session.get_outputs()) else "allclose",
                               "minimumCosine": .98, "atol": 1e-4, "rtol": 1e-3},
            "hostValidation": {"runtime": "onnxruntime==" + ort.__version__, "provider": "CPUExecutionProvider",
                               "fixture": ("deterministic nonzero ramp tensors" if informative else "deterministic zero tensors") +
                                          "; not a quality or speed benchmark", "finiteOutputs": True}}


def store_float16_weights(source, target):
    """Compress finite model parameters only; all original operators stay FP32."""
    import numpy as np
    import onnx
    from onnx import helper, numpy_helper, TensorProto
    model = onnx.load(str(source))
    casts = []
    names = {t.name for t in model.graph.initializer} | {n for node in model.graph.node for n in node.output}
    for tensor in model.graph.initializer:
        if tensor.data_type != TensorProto.FLOAT:
            continue
        array = numpy_helper.to_array(tensor)
        # Keep scalar constants, epsilon and extreme values exact.
        if array.size < 256 or not np.isfinite(array).all() or np.max(np.abs(array)) > 65504:
            continue
        original = tensor.name
        stored = "lik.fp16_storage." + original
        if stored in names:
            raise ValueError("Weight storage name collision")
        tensor.CopyFrom(numpy_helper.from_array(array.astype(np.float16), stored))
        casts.append(helper.make_node("Cast", [stored], [original], to=TensorProto.FLOAT))
    existing = list(model.graph.node)
    del model.graph.node[:]
    model.graph.node.extend(casts + existing)
    onnx.save(model, str(target))


def export_torch(wrapper, inputs, names, path, work, precision="int8", output_name="embedding"):
    import numpy as np
    import torch
    import onnxruntime as ort
    from onnxruntime.quantization import quantize_dynamic, QuantType
    wrapper.eval()
    float_path = work / path.name
    with torch.inference_mode():
        expected = wrapper(*inputs).cpu().numpy()
        torch.onnx.export(wrapper, inputs, str(float_path), input_names=names,
                          output_names=[output_name], opset_version=17, dynamo=False,
                          do_constant_folding=True, external_data=True)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    feed = {name: value.cpu().numpy() for name, value in zip(names, inputs)}
    float_session = ort.InferenceSession(str(float_path), options, providers=["CPUExecutionProvider"])
    float_actual = float_session.run(None, feed)[0]
    float_error = float(np.max(np.abs(float_actual - expected)))
    if not np.allclose(float_actual, expected, atol=1e-4, rtol=1e-3):
        raise ValueError(f"FP32 ONNX/PyTorch parity failed: max absolute error={float_error}")
    del float_session
    if precision == "int8":
        quantize_dynamic(str(float_path), str(path), op_types_to_quantize=["MatMul", "Gather"],
                         per_channel=True, reduce_range=False, weight_type=QuantType.QInt8,
                         use_external_data_format=False)
    elif precision == "fp16-storage":
        store_float16_weights(float_path, path)
    else:
        shutil.copyfile(float_path, path)
    session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    actual = session.run(None, feed)[0]
    cosine = float(np.sum(actual * expected) / (np.linalg.norm(actual) * np.linalg.norm(expected)))
    if not np.isfinite(actual).all() or cosine < .98:
        raise ValueError(f"Quantized export synthetic parity failed: cosine={cosine}")
    return {"precision": "dynamic-QInt8-MatMul-Gather-per-channel" if precision == "int8" else "FP16-weight-storage-FP32-computation" if precision == "fp16-storage" else "FP32",
            "fp32OnnxMaxAbsoluteErrorToTorch": float_error,
            "syntheticCosineToTorchFp32": cosine, "syntheticMaxAbsoluteError": float(np.max(np.abs(actual - expected))),
            "acceptanceThreshold": .98, "qualityBenchmark": False}


def prepare_component(cache, component, paddle_python):
    repo = COMPONENTS[component]
    source = source_directory(cache, repo)
    # Validate selected source bytes before a framework deserializes them.
    selected = source_info(repo)["files"]
    if component in ("yunet-v1", "sface-v1"):
        folder = "face_detection_yunet" if component == "yunet-v1" else "face_recognition_sface"
        selected = [f for f in selected if f["path"].startswith("models/" + folder + "/")]
    for file in selected:
        verify_file(source, file)
    output = cache / "candidates" / component
    work = cache / "export-work" / component
    output.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)
    models = []
    parity = {}
    if component in ("yunet-v1", "sface-v1"):
        model = next(f for f in selected if f["path"].endswith(".onnx"))
        path = output / "model.onnx"
        shutil.copyfile(source / model["path"], path)
        models.append((path, None))
    elif component.startswith("ocr-"):
        path = output / "model.onnx"
        subprocess.run([str(paddle_python.parent / "paddle2onnx"), "--model_dir", str(source),
                        "--model_filename", "inference.json", "--params_filename", "inference.pdiparams",
                        "--save_file", str(path), "--opset_version", "19", "--enable_onnx_checker", "True",
                        "--enable_auto_update_opset", "False", "--optimize_tool", "None"], check=True)
        shape = [1, 3, 48, 320] if component == "ocr-cyrillic-rec-v1" else [1, 3, 736, 736]
        models.append((path, {"x": shape}))
        parity_file = cache / "research" / (component + "-parity.json")
        subprocess.run([str(paddle_python), str(ROOT / "scripts/models/paddle_parity.py"),
                        "--source", str(source), "--model", str(path), "--component", component,
                        "--output", str(parity_file)], check=True)
        parity[path.name] = json.loads(parity_file.read_text())
        shutil.copyfile(source / "inference.yml", output / "inference.yml")
        if component == "ocr-cyrillic-rec-v1":
            import yaml
            characters = yaml.safe_load((source / "inference.yml").read_text())["PostProcess"]["character_dict"]
            (output / "characters.json").write_text(json.dumps(characters, ensure_ascii=False, separators=(",", ":")) + "\n")
    else:
        import torch
        from transformers import AutoModel, AutoTokenizer, CLIPVisionModelWithProjection, SiglipModel
        from safetensors.torch import load_file
        torch.manual_seed(0)
        torch.set_num_threads(4)

        class ImageEncoder(torch.nn.Module):
            def __init__(self, encoder, clip=False):
                super().__init__()
                self.encoder, self.clip = encoder, clip

            def forward(self, pixel_values):
                result = self.encoder(pixel_values=pixel_values)
                return torch.nn.functional.normalize(result.image_embeds if self.clip else result.pooler_output, dim=-1)

        class SiglipText(torch.nn.Module):
            def __init__(self, encoder):
                super().__init__()
                self.encoder = encoder

            def forward(self, input_ids):
                return torch.nn.functional.normalize(self.encoder(input_ids=input_ids).pooler_output, dim=-1)

        class MultilingualText(torch.nn.Module):
            def __init__(self, encoder, weight):
                super().__init__()
                self.encoder = encoder
                self.register_buffer("projection", weight)

            def forward(self, input_ids, attention_mask):
                hidden = self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
                mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
                mean = (hidden * mask).sum(1) / mask.sum(1).clamp(min=1e-9)
                return torch.nn.functional.normalize(mean @ self.projection.T, dim=-1)

        if component == "clip-image-v1":
            encoder = CLIPVisionModelWithProjection.from_pretrained(str(source), local_files_only=True, attn_implementation="eager")
            path = output / "model.onnx"
            parity[path.name] = export_torch(ImageEncoder(encoder, clip=True), (torch.randn(1, 3, 224, 224),), ["pixel_values"], path, work)
            models.append((path, None))
        elif component == "multilingual-text-v1":
            encoder = AutoModel.from_pretrained(str(source), local_files_only=True, attn_implementation="eager")
            weight = load_file(str(source / "2_Dense/model.safetensors"))["linear.weight"]
            tokenizer = AutoTokenizer.from_pretrained(str(source), local_files_only=True)
            tokens = tokenizer("Кот спит на диване", max_length=128, padding="max_length", truncation=True, return_tensors="pt")
            path = output / "model.onnx"
            parity[path.name] = export_torch(MultilingualText(encoder, weight),
                                             (tokens["input_ids"], tokens["attention_mask"]), ["input_ids", "attention_mask"], path, work)
            models.append((path, None))
            for name in ("tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "vocab.txt"):
                shutil.copyfile(source / name, output / name)
        elif component == "sensitive-v1":
            import timm
            from safetensors.torch import load_file
            configuration = json.loads((source / "config.json").read_text())
            encoder = timm.create_model(configuration["architecture"], pretrained=False,
                                        num_classes=configuration["num_classes"])
            encoder.load_state_dict(load_file(str(source / "model.safetensors")), strict=True)
            # Static eager attention exports standard CPU ONNX operators.
            for block in encoder.blocks:
                block.attn.fused_attn = False
            class Classifier(torch.nn.Module):
                def __init__(self, encoder):
                    super().__init__()
                    self.encoder = encoder

                def forward(self, pixel_values):
                    return self.encoder(pixel_values).softmax(dim=-1)
            path = output / "model.onnx"
            parity[path.name] = export_torch(Classifier(encoder), (torch.randn(1, 3, 384, 384),),
                                             ["pixel_values"], path, work, precision="fp32",
                                             output_name="probabilities")
            models.append((path, None))
            shutil.copyfile(source / "config.json", output / "config.json")
        else:
            model = SiglipModel.from_pretrained(str(source), local_files_only=True, attn_implementation="eager")
            size = model.config.vision_config.image_size
            path = output / "image.onnx"
            parity[path.name] = export_torch(ImageEncoder(model.vision_model), (torch.randn(1, 3, size, size),), ["pixel_values"], path, work, precision="fp16-storage")
            models.append((path, None))
            tokenizer = AutoTokenizer.from_pretrained(str(source), local_files_only=True)
            tokens = tokenizer("Кот спит на диване", max_length=64, padding="max_length", truncation=True, return_tensors="pt")
            path = output / "text.onnx"
            parity[path.name] = export_torch(SiglipText(model.text_model), (tokens["input_ids"],), ["input_ids"], path, work, precision="fp16-storage")
            models.append((path, None))
            # Shared between Base and Large; separately hash-checked by the catalog.
            tokenizer_dir = cache / "candidates/siglip2-tokenizer-v1"
            tokenizer_dir.mkdir(exist_ok=True)
            for name in ("tokenizer.json", "tokenizer.model", "tokenizer_config.json", "special_tokens_map.json"):
                destination = tokenizer_dir / name
                if destination.exists() and digest(destination) != digest(source / name):
                    raise ValueError("SigLIP 2 tokenizer revisions are not compatible")
                shutil.copyfile(source / name, destination)
        if (source / "preprocessor_config.json").exists():
            shutil.copyfile(source / "preprocessor_config.json", output / "preprocessor_config.json")
    evidence = {"component": component, "models": {str(path.relative_to(output)): inspect_model(path, shapes) for path, shapes in models}, "parity": parity}
    (cache / "research" / (component + "-validation.json")).write_text(json.dumps(evidence, indent=2) + "\n")
    print("Prepared and host-validated", component, flush=True)
    gc.collect()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["download", "prepare", "publish"])
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("LIK_MODEL_CACHE", Path.home() / "Library/Caches/Lik/model-artifacts")))
    parser.add_argument("--component", choices=list(COMPONENTS))
    parser.add_argument("--paddle-python", type=Path)
    parser.add_argument("--range-workers", type=int, default=0, help="Optional bounded Range fallback (1–16); default uses hf")
    args = parser.parse_args()
    cache = args.cache.resolve()
    if cache.is_relative_to(ROOT):
        parser.error("Model cache must be outside the Git checkout")
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "research").mkdir(exist_ok=True)
    if args.command == "download":
        download(cache, args.range_workers)
    elif args.command == "prepare":
        for component in [args.component] if args.component else COMPONENTS:
            prepare_component(cache, component, args.paddle_python or cache / "paddle-venv/bin/python")
    else:
        from artifacts import validate_catalog
        catalog = json.loads((ROOT / "models/prepared-reference-v1.json").read_text())
        for file in validate_catalog(catalog):
            verify_file(cache / "candidates", file)
        if (cache / "prepared").exists():
            raise ValueError("prepared already exists; verify it or move it aside explicitly")
        # A complete, reviewed, byte-identical candidate set is published at once.
        (cache / "candidates").rename(cache / "prepared")
        print("Published all verified profile artifacts")


if __name__ == "__main__":
    main()
