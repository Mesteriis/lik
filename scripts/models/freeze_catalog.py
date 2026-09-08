#!/usr/bin/env python3
"""Create a developer reference-export catalog; never a downloadable/packaged catalog."""
import argparse
import hashlib
import json
from pathlib import Path

from artifacts import digest, validate_catalog, verify_file
from prepare import COMPONENTS, ROOT


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Review candidate already exists; choose a new output path")
    contracts = {c["id"]: c["contract"] for c in json.loads((ROOT / "models/prepared-reference-v1.json").read_text())["components"]}
    sources = json.loads((ROOT / "models/sources-v1.json").read_text())["sources"]
    catalog = {"schemaVersion": 1, "catalogVersion": "lik-builtins-2026-09-08-v1",
               "defaultProfile": "balanced-v1", "components": [], "profiles": [],
               "backendPolicy": "backend-policy-v1.json", "qualityAcceptance": "not-run; external fixtures required",
               "licenseNotices": []}
    for notice in sorted((ROOT / "models/licenses").iterdir()):
        catalog["licenseNotices"].append({"path": str(notice.relative_to(ROOT / "models")),
                                         "size": notice.stat().st_size, "sha256": digest(notice)})
    for component_id in [*COMPONENTS, "siglip2-tokenizer-v1"]:
        directory = args.cache / "candidates" / component_id
        component = {"id": component_id, "contract": contracts[component_id], "artifacts": []}
        if component_id == "siglip2-tokenizer-v1":
            names = {p.name for p in directory.iterdir()}
            component["sources"] = [dict(s, files=[f for f in s["files"] if f["path"] in names])
                                    for s in sources if s["repo"].startswith("google/siglip2-")]
            validation = {"models": {}, "parity": {}}
            component["preparation"] = "Byte-identical pinned tokenizer files; both publisher revisions verified"
        else:
            source = next(s for s in sources if s["repo"] == COMPONENTS[component_id])
            if component_id in ("yunet-v1", "sface-v1"):
                folder = "face_detection_yunet" if component_id == "yunet-v1" else "face_recognition_sface"
                source = dict(source, files=[f for f in source["files"] if f["path"].startswith("models/" + folder + "/")])
            component["sources"] = [source]
            validation = json.loads((args.cache / "research" / (component_id + "-validation.json")).read_text())
            component["preparation"] = {"command": f"python scripts/models/prepare.py prepare --component {component_id}",
                                        "exportToolLockSha256": digest(ROOT / "scripts/models/requirements-export.lock"),
                                        "paddleToolLockSha256": digest(ROOT / "scripts/models/requirements-paddle.lock")}
        for source in component["sources"]:
            for source_file in source["files"]:
                verify_file(args.cache / "sources" / source["repo"] / source["revision"], source_file)
        for path in sorted(directory.rglob("*")):
            if not path.is_file():
                continue
            artifact = {"path": str(path.relative_to(args.cache / "candidates")),
                        "size": path.stat().st_size, "sha256": digest(path)}
            if path.suffix == ".onnx":
                artifact["onnx"] = validation["models"][path.name]
                artifact["conversionParity"] = validation["parity"].get(path.name, {"kind": "byte-identical publisher ONNX; no conversion"})
            component["artifacts"].append(artifact)
        component["fingerprint"] = fingerprint(component)
        catalog["components"].append(component)
    common = ["ocr-cyrillic-rec-v1", "yunet-v1", "sface-v1", "sensitive-v1"]
    for profile_id, semantic, detector, dimension in [
        ("compact-v1", ["clip-image-v1", "multilingual-text-v1"], "ocr-mobile-det-v1", 512),
        ("balanced-v1", ["siglip2-base-v1", "siglip2-tokenizer-v1"], "ocr-mobile-det-v1", 768),
        ("extended-v1", ["siglip2-large-v1", "siglip2-tokenizer-v1"], "ocr-server-det-v1", 1024),
    ]:
        profile = {"id": profile_id, "components": semantic + [detector] + common,
                   "pipelines": {"search": {"components": semantic, "dimension": dimension},
                                 "ocr": {"components": [detector, "ocr-cyrillic-rec-v1"]},
                                 "people": {"components": ["yunet-v1", "sface-v1"], "dimension": 128},
                                 "sensitive": {"components": ["sensitive-v1"], "calibration": "not-run"}}}
        for pipeline in profile["pipelines"].values():
            pipeline["fingerprint"] = fingerprint([next(c["fingerprint"] for c in catalog["components"] if c["id"] == key) for key in pipeline["components"]])
        profile["fingerprint"] = fingerprint(profile)
        catalog["profiles"].append(profile)
    validate_catalog(catalog)
    args.output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    print("Review candidate:", args.output, "sha256:", digest(args.output))


if __name__ == "__main__":
    main()
