#!/usr/bin/env python3
"""Offline verification/staging. Standard library only; never downloads models."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile


class ArtifactError(ValueError):
    pass


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def safe_path(value):
    path = PurePosixPath(value)
    if (not value or path.is_absolute() or "\\" in value or
            any(part in ("", ".", "..") for part in value.split("/"))):
        raise ArtifactError(f"Unsafe artifact path: {value!r}")
    return path


def file_contract(file):
    safe_path(file["path"])
    if not isinstance(file.get("size"), int) or file["size"] <= 0:
        raise ArtifactError(f"Missing positive size: {file['path']}")
    if not re.fullmatch(r"[a-f0-9]{64}", file.get("sha256", "")):
        raise ArtifactError(f"Missing SHA-256: {file['path']}")


def validate_catalog(catalog):
    if catalog.get("schemaVersion") != 1:
        raise ArtifactError("Unsupported catalog schema")
    profiles = catalog["profiles"]
    if {p["id"] for p in profiles} != {"compact-v1", "balanced-v1", "extended-v1"} or len(profiles) != 3:
        raise ArtifactError("Catalog must contain exactly all three built-in profiles")
    if catalog["defaultProfile"] != "balanced-v1":
        raise ArtifactError("Balanced must be the default profile")
    components = {c["id"]: c for c in catalog["components"]}
    if len(components) != len(catalog["components"]):
        raise ArtifactError("Duplicate component identity")
    used = set()
    for profile in profiles:
        if not profile["components"] or len(set(profile["components"])) != len(profile["components"]):
            raise ArtifactError("Empty or duplicate profile components")
        for pipeline in profile.get("pipelines", {}).values():
            compatible = pipeline.get("compatibleFingerprints")
            if (not isinstance(compatible, list) or not compatible or
                    len(compatible) != len(set(compatible)) or
                    any(not re.fullmatch(r"[a-f0-9]{64}", value) for value in compatible) or
                    pipeline.get("fingerprint") in compatible):
                raise ArtifactError("Invalid compatible pipeline fingerprints")
        for component in profile["components"]:
            if component not in components:
                raise ArtifactError(f"Missing component {component}")
            used.add(component)
    if used != set(components):
        raise ArtifactError("Unreferenced catalog component")
    paths = set()
    for notice in catalog.get("licenseNotices", []):
        file_contract(notice)
        if notice["path"] in paths:
            raise ArtifactError("Duplicate license path")
        paths.add(notice["path"])
    for component in components.values():
        if not component.get("artifacts") or not component.get("sources"):
            raise ArtifactError(f"Missing artifacts/sources: {component['id']}")
        for source in component["sources"]:
            if not re.fullmatch(r"[a-f0-9]{40}", source.get("revision", "")):
                raise ArtifactError("Source revision must be an immutable commit")
            for file in source["files"]:
                file_contract(file)
        for file in component["artifacts"]:
            file_contract(file)
            if catalog.get("delivery") == "settings-download-from-huggingface":
                repo, revision, remote = (file.get(key, "") for key in ("repo", "revision", "remoteFile"))
                if (not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or
                        not re.fullmatch(r"[a-f0-9]{40}", revision) or not remote or
                        file.get("url") != f"https://huggingface.co/{repo}/resolve/{revision}/{remote}"):
                    raise ArtifactError(f"Missing immutable Hugging Face download: {file['path']}")
                safe_path(remote)
            if file["path"] in paths:
                raise ArtifactError(f"Duplicate artifact path: {file['path']}")
            paths.add(file["path"])
    onnx_paths = {file["path"] for component in components.values() for file in component["artifacts"]
                  if "onnx" in file}
    activation = catalog.get("activationSmokeReferences", [])
    if not isinstance(activation, list) or {entry.get("path") for entry in activation} != onnx_paths:
        raise ArtifactError("Every ONNX graph needs one activation smoke reference")
    if len(activation) != len(onnx_paths):
        raise ArtifactError("Duplicate activation smoke reference")
    for entry in activation:
        if (not isinstance(entry.get("outputName"), str) or not entry["outputName"] or
                not isinstance(entry.get("outputSize"), int) or entry["outputSize"] <= 0 or
                not isinstance(entry.get("minimumNormRatio"), (int, float)) or
                not isinstance(entry.get("maximumNormRatio"), (int, float)) or
                not isinstance(entry.get("sampleAbsoluteTolerance"), (int, float)) or
                not isinstance(entry.get("sampleRelativeTolerance"), (int, float)) or
                not isinstance(entry.get("minimumRangeRatio"), (int, float)) or
                not isinstance(entry.get("maximumRangeRatio"), (int, float)) or
                not isinstance(entry.get("scaleAware"), bool) or
                not 0 < entry["minimumNormRatio"] <= 1 <= entry["maximumNormRatio"] or
                not 0 < entry["sampleAbsoluteTolerance"] < 1 or
                not 0 < entry["sampleRelativeTolerance"] < 1 or
                not 0 < entry["minimumRangeRatio"] <= 1 <= entry["maximumRangeRatio"] or
                not re.fullmatch(r"[a-f0-9]{64}", entry.get("referenceSha256", ""))):
            raise ArtifactError("Invalid activation smoke reference contract")
        samples = entry.get("samples")
        if not isinstance(samples, list) or len(samples) < 2:
            raise ArtifactError("Activation smoke reference needs expected samples")
        indices = [sample.get("index") for sample in samples]
        if (len(set(indices)) != len(indices) or any(not isinstance(index, int) or index < 0 or
                                                    index >= entry["outputSize"] for index in indices) or
                any(not re.fullmatch(r"[0-9a-f]{8}", sample.get("floatBits", "")) for sample in samples)):
            raise ArtifactError("Invalid activation smoke samples")
        if entry["path"].startswith("ocr-") and not entry["scaleAware"]:
            raise ArtifactError("OCR activation smoke must use scale-aware verification")
    if onnx_paths and (not isinstance(catalog.get("oracleRevision"), str) or not catalog["oracleRevision"]):
        raise ArtifactError("Missing activation oracle revision")
    for component in components.values():
        for file in component["artifacts"]:
            if "onnx" in file and any(i.get("smokePattern") not in
                    {"deterministic-ramp-v1", "byte-ramp-v1", "zeros-v1", "ones-v1"} for i in file["onnx"]["inputs"]):
                raise ArtifactError("Invalid activation smoke input")
            if component["id"].startswith("ocr-") and "onnx" in file and not any(
                    i.get("smokePattern") in {"deterministic-ramp-v1", "byte-ramp-v1"} for i in file["onnx"]["inputs"]):
                raise ArtifactError("OCR activation smoke input must be informative")
    return [f for c in components.values() for f in c["artifacts"]]


def verify_file(root, file):
    relative = safe_path(file["path"])
    path = root / relative
    # Reject links even when their target happens to be within the cache.
    if root.is_symlink() or any((root / Path(*relative.parts[:i])).is_symlink()
                                for i in range(1, len(relative.parts) + 1)):
        raise ArtifactError(f"Symlink is not a verified artifact: {relative}")
    if not path.is_file():
        raise ArtifactError(f"Missing artifact: {relative}")
    if path.stat().st_size != file["size"]:
        raise ArtifactError(f"Size mismatch: {relative}")
    if digest(path) != file["sha256"]:
        raise ArtifactError(f"SHA-256 mismatch: {relative}")
    return path


def verify(catalog, cache, directory=None):
    files = validate_catalog(catalog)
    directory = directory or ("hf-runtime" if catalog.get("delivery") == "settings-download-from-huggingface" else "prepared")
    for file in files:
        verify_file(cache / directory, file)
    return files


def stage(catalog, cache, output):
    """APK assets are metadata-only, regardless of any external model cache."""
    validate_catalog(catalog)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="model-metadata-", dir=output.parent))
    try:
        target = temporary / "models"
        target.mkdir()
        root = Path(__file__).resolve().parents[2] / "models"
        for notice in catalog.get("licenseNotices", []):
            source = verify_file(root, notice)
            destination = target / notice["path"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            verify_file(target, notice)
        for name in ("contracts-v1.json", "backend-policy-v1.json"):
            shutil.copyfile(root / name, target / name)
        (target / "catalog-v1.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
        (target / "availability.json").write_text(json.dumps({
            "schemaVersion": 1, "available": False,
            "catalogVersion": catalog["catalogVersion"], "selectedProfile": "balanced-v1",
            "mode": "settings-download-from-huggingface", "bundledPayloads": False,
        }) + "\n")
        if output.exists():
            shutil.rmtree(output)
        temporary.rename(output)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["verify", "stage"])
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[2] / "models/catalog-v1.json")
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("LIK_MODEL_CACHE", Path.home() / "Library/Caches/Lik/model-artifacts")))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--artifact-directory", help="Developer cache subdirectory; defaults to hf-runtime for the download catalog")
    args = parser.parse_args()
    try:
        catalog = json.loads(args.catalog.read_text())
        if args.command == "verify":
            files = verify(catalog, args.cache, args.artifact_directory)
            print(f"Verified all 3 profiles: {len(files)} unique files, {sum(f['size'] for f in files)} bytes")
        else:
            if args.output is None:
                parser.error("stage requires --output")
            stage(catalog, args.cache, args.output)
            print("METADATA ONLY: 3 presets; download required; no weights or tokenizers bundled")
    except (ArtifactError, KeyError, OSError, json.JSONDecodeError) as error:
        print(f"Model artifact verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
