#!/usr/bin/env python3
"""Reject model/tokenizer payloads in distribution APKs; models download later from HF."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def inspect_apk(path, catalog):
    # Also catches stale APK holes left by incremental ZIP updates after removing
    # an old bundled-model build: listing entries alone is insufficient.
    if path.stat().st_size > 250 * 1024 * 1024:
        raise ValueError("APK exceeds the 250 MiB metadata/runtime budget; clean stale packaged payloads")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries")
        base = "assets/models/"
        if json.loads(archive.read(base + "catalog-v1.json")) != catalog:
            raise ValueError("Packaged catalog differs from trust anchor")
        availability = json.loads(archive.read(base + "availability.json"))
        if availability["available"] or availability["bundledPayloads"]:
            raise ValueError("APK incorrectly claims bundled/runtime-ready profiles")
        metadata = {"catalog-v1.json", "contracts-v1.json", "backend-policy-v1.json", "availability.json"}
        notices = catalog.get("licenseNotices", [])
        expected = {base + name for name in metadata} | {base + n["path"] for n in notices}
        allowed_assets = expected | {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
        if {name for name in names if name.startswith(base)} != expected:
            raise ValueError("Model assets must contain manifests/licenses only")
        if any(name.startswith("assets/") and name not in allowed_assets for name in names):
            raise ValueError("Unexpected APK asset; model payloads are forbidden")
        forbidden = (".onnx", ".onnx_data", ".safetensors", ".pdiparams", ".tflite")
        if any(name.endswith(forbidden) for name in names):
            raise ValueError("Model weights are forbidden anywhere in the APK")
        for name in expected:
            if archive.getinfo(name).file_size > 2 * 1024 * 1024:
                raise ValueError("Oversized metadata entry: " + name)
        for notice in notices:
            data = archive.read(base + notice["path"])
            if len(data) != notice["size"] or hashlib.sha256(data).hexdigest() != notice["sha256"]:
                raise ValueError("Packaged license mismatch")
        return {"path": str(path), "bytes": path.stat().st_size, "entryCount": len(entries),
                "uncompressedBytes": sum(e.file_size for e in entries),
                "compressedPayloadBytes": sum(e.compress_size for e in entries),
                "metadataOnly": True, "onnxCount": 0, "tokenizerPayloadCount": 0,
                "maximumApkBytes": 250 * 1024 * 1024}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, action="append", required=True)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[2] / "models/catalog-v1.json")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    reports = []
    for directory in args.directory:
        paths = list(directory.glob("*.apk"))
        if len(paths) != 1:
            raise ValueError("Expected exactly one APK in " + str(directory))
        reports.append(inspect_apk(paths[0], catalog))
    value = json.dumps(reports, indent=2) + "\n"
    if args.output:
        args.output.write_text(value)
    print(value)


if __name__ == "__main__":
    main()
