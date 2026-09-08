#!/usr/bin/env python3
"""Fail closed on every APK entry; models download later from Hugging Face."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib

from artifacts import safe_path
from apk_signing import signing_block
from compiled_apk import CompiledReceipts, build_tools_directory, verify_signature
from certificate_policy import POLICY_PATH as SIGNING_POLICY_PATH

ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = Path(__file__).with_name("apk-content-policy-v1.json")
MAX_APK_BYTES = 250 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def matches(data, receipt):
    return len(data) == receipt["size"] and hashlib.sha256(data).hexdigest() == receipt["sha256"]


def matches_approved(data, receipt):
    """A source-built native library may have exact per-build-type receipts."""
    receipts = receipt if isinstance(receipt, list) else [receipt]
    require(receipts and all(set(value) == {"size", "sha256"} for value in receipts),
            "Malformed APK content receipt")
    return any(matches(data, value) for value in receipts)


def empty_zip_padding(data):
    # Zipflinger leaves virtual empty local headers in deleted-entry space.
    # Only their structural header and zero padding may remain, never old bytes.
    while any(data):
        require(len(data) >= 30, "Unexpected bytes between ZIP entries")
        fields = struct.unpack_from("<4s5H3I2H", data)
        require(fields[:4] == (b"PK\x03\x04", 0, 0, 0) and fields[6:10] == (0, 0, 0, 0),
                "Nonempty unreferenced ZIP entry")
        end = 30 + fields[10]
        require(end <= len(data) and not any(data[30:end]), "Payload in ZIP padding")
        data = data[end:]


def zip_envelope(path, archive, artifacts):
    """Account for physical bytes too, including renamed or unindexed payloads."""
    entries = sorted(archive.infolist(), key=lambda e: e.header_offset)
    require(entries and entries[0].header_offset == 0 and not archive.comment, "APK prefix/comment data is forbidden")
    with path.open("rb") as stream:
        stream.seek(-22, 2)
        end_record = struct.unpack("<4s4H2IH", stream.read(22))
        require(end_record[:3] == (b"PK\x05\x06", 0, 0) and
                end_record[3] == end_record[4] == len(entries) and end_record[7] == 0 and
                end_record[6] == archive.start_dir and end_record[5] + end_record[6] + 22 == path.stat().st_size,
                "APK trailer or unindexed central-directory data is forbidden")
        end = 0
        for entry in entries:
            require(entry.header_offset >= end and not entry.extra and not entry.comment,
                    "Overlapping ZIP entries or extra/comment data")
            stream.seek(end)
            empty_zip_padding(stream.read(entry.header_offset - end))
            header = struct.unpack("<4s5H3I2H", stream.read(30))
            require(header[0] == b"PK\x03\x04" and header[2] & ~0x800 == 0 and
                    header[3] == entry.compress_type and header[6:9] == (entry.CRC, entry.compress_size, entry.file_size),
                    "Local ZIP header differs from central directory")
            require(stream.read(header[9]) == entry.filename.encode("utf-8") and not any(stream.read(header[10])),
                    "Unexpected local ZIP name or extra payload")
            if entry.compress_type == zipfile.ZIP_DEFLATED:
                decoder = zlib.decompressobj(-15)
                data = decoder.decompress(stream.read(entry.compress_size), entry.file_size + 1)
                require(len(data) == entry.file_size and decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail,
                        "Hidden bytes or invalid length in ZIP deflate stream")
            else:
                require(entry.compress_type == zipfile.ZIP_STORED and entry.compress_size == entry.file_size,
                        "Unsupported ZIP payload encoding")
                stream.seek(entry.compress_size, 1)
            end = stream.tell()
        require(end <= archive.start_dir, "ZIP data overlaps central directory")
        remaining = stream.read(archive.start_dir - end)
        if remaining:
            require(len(remaining) >= 32, "Truncated APK signing envelope")
            block_size = struct.unpack_from("<Q", remaining, len(remaining) - 24)[0] + 8
            require(32 <= block_size <= len(remaining), "Invalid APK signing envelope size")
            padding = remaining[:len(remaining) - block_size]
            require(len(padding) < 4096 and not any(padding), "Payload in APK signing alignment padding")
            signing_block(remaining[len(padding):], artifacts)
        return bool(remaining)


def metadata_entries(catalog, kind):
    if kind == "android-test":
        # Only checked-in Room migration schemas are test assets. QA tensors and
        # tokenizers are provisioned separately to private files, never to an APK.
        schema_root = ROOT / "app/schemas"
        return {"assets/" + path.relative_to(schema_root).as_posix(): path.read_bytes()
                for path in schema_root.glob("io.github.mesteriis.lik.catalog.MediaDatabase/[0-9]*.json")}
    require(kind == "app", "Unknown APK kind")
    base = "assets/models/"
    result = {base + name: (ROOT / "models" / name).read_bytes()
              for name in ("contracts-v1.json", "backend-policy-v1.json")}
    result[base + "catalog-v1.json"] = (json.dumps(catalog, ensure_ascii=False, indent=2) + "\n").encode()
    result[base + "availability.json"] = (json.dumps({
        "schemaVersion": 1, "available": False,
        "catalogVersion": catalog["catalogVersion"], "selectedProfile": "balanced-v1",
        "mode": "settings-download-from-huggingface", "bundledPayloads": False,
    }) + "\n").encode()
    for notice in catalog.get("licenseNotices", []):
        safe_path(notice["path"])
        data = (ROOT / "models" / notice["path"]).read_bytes()
        require(matches(data, notice), "License trust anchor mismatch")
        result[base + notice["path"]] = data
    return result


def check_entry(name, data, policy, compiled):
    # Opaque runtime/classpath/license resources are exact-path AND digest pinned.
    # A new dependency or PNG requires an explicit reviewed policy update; the
    # build never learns an allowlist from whatever happened to be packaged.
    if name == "META-INF/version-control-info.textproto":
        # AGP 9.2.1 ExtractVersionControlInfoTask emits these fixed fields in a
        # normal checkout; linked worktrees emit the pinned NO_VALID_GIT error.
        # Do not pin the commit value or admit arbitrary protobuf/text payloads.
        require(matches(data, policy["entries"][name]) or
                data == b"generate_error_reason: NO_SUPPORTED_VCS_FOUND\n" or
                re.fullmatch(rb'repositories \{\n  system: GIT\n  local_root_path: "\$PROJECT_DIR"\n'
                             rb'  revision: "[0-9a-f]{40}"\n\}\n', data), "Invalid AGP version-control metadata")
    elif name in policy["entries"]:
        require(matches_approved(data, policy["entries"][name]), "Unapproved bytes at pinned APK entry: " + name)
    elif compiled.check(name, data):
        if name.endswith(".png"):
            receipt = policy["pngs"].get(hashlib.sha256(data).hexdigest())
            require(receipt is not None and len(data) == receipt["size"], "Unapproved PNG content: " + name)
    elif name.startswith("META-INF/services/"):
        # R8 renames both service types and implementations. Their only accepted
        # payload is a short Java provider-name list, not arbitrary classpath data.
        java_name = rb"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*"
        require(re.fullmatch(java_name, name.removeprefix("META-INF/services/").encode()) and
                0 < len(data) <= 1024 and re.fullmatch(java_name + rb"(?:\r?\n" + java_name + rb")*\r?\n?", data),
                "Invalid service provider metadata: " + name)
    else:
        raise ValueError("Unapproved APK entry (raw resources and arbitrary blobs are forbidden): " + name)


def inspect_apk(path, catalog, kind="app", variant=None, build_tools=None):
    # Also catches stale APK holes left by incremental ZIP updates after removing
    # an old bundled-model build: listing entries alone is insufficient.
    require(path.stat().st_size <= MAX_APK_BYTES, "APK exceeds the 250 MiB metadata/runtime budget; clean stale packaged payloads")
    policy = json.loads(POLICY_PATH.read_text())
    require(policy["schemaVersion"] == 1, "Unknown APK content policy")
    expected = metadata_entries(catalog, kind)
    compiled = CompiledReceipts(kind, variant, build_tools)
    forbidden_receipts = {(f["size"], f["sha256"]) for c in catalog["components"] for f in c["artifacts"]}
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        require(len(names) == len(set(names)), "APK contains duplicate ZIP entries")
        require(sum(e.file_size for e in entries) <= MAX_APK_BYTES, "APK uncompressed content exceeds runtime budget")
        require(set(expected) <= set(names), "APK missing required trusted metadata")
        signed = zip_envelope(path, archive, [file for component in catalog["components"] for file in component["artifacts"]])
        for entry in entries:
            name = entry.filename
            safe_path(name)
            require(not name.lower().endswith((".onnx", ".onnx_data", ".safetensors", ".pdiparams", ".tflite")),
                    "Model weights are forbidden anywhere in the APK: " + name)
            data = archive.read(entry)
            require((len(data), hashlib.sha256(data).hexdigest()) not in forbidden_receipts,
                    "Known model/tokenizer payload at renamed APK path: " + name)
            if name in expected:
                require(len(data) <= 2 * 1024 * 1024 and data == expected[name], "Packaged metadata differs from trust anchor: " + name)
            else:
                check_entry(name, data, policy, compiled)
        compiler_evidence = compiled.verify(path)
        if signed:
            verify_signature(path, build_tools_directory(build_tools))
        return {"compilerEvidence": compiler_evidence, "signatureVerified": signed, "path": str(path), "kind": kind, "bytes": path.stat().st_size, "entryCount": len(entries),
                "signingPolicySha256": hashlib.sha256(SIGNING_POLICY_PATH.read_bytes()).hexdigest(),
                "inspectedEntryCount": len(entries), "contentPolicySha256": hashlib.sha256(POLICY_PATH.read_bytes()).hexdigest(),
                "uncompressedBytes": sum(e.file_size for e in entries),
                "compressedPayloadBytes": sum(e.compress_size for e in entries),
                "metadataOnly": True, "onnxCount": 0, "tokenizerPayloadCount": 0,
                "maximumApkBytes": MAX_APK_BYTES}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, action="append", required=True)
    parser.add_argument("--kind", choices=("app", "android-test"), default="app")
    parser.add_argument("--variant", help="Exact reviewed Android component name")
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--catalog", type=Path, default=ROOT / "models/catalog-v1.json")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    reports = []
    for directory in args.directory:
        paths = list(directory.glob("*.apk"))
        require(len(paths) >= 1, "Expected at least one APK in " + str(directory))
        reports.extend(inspect_apk(path, catalog, args.kind, args.variant, args.build_tools) for path in sorted(paths))
    value = json.dumps(reports, indent=2) + "\n"
    if args.output:
        args.output.write_text(value)
    print(value)


if __name__ == "__main__":
    main()
