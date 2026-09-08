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

ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = Path(__file__).with_name("apk-content-policy-v1.json")
MAX_APK_BYTES = 250 * 1024 * 1024
RESOURCE_PATH = re.compile(r"res/(?:[A-Za-z0-9_]+|(?:color|drawable|layout|mipmap|xml)(?:-[A-Za-z0-9_+-]+)?/[A-Za-z0-9_.]+)\.(xml|png)")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def matches(data, receipt):
    return len(data) == receipt["size"] and hashlib.sha256(data).hexdigest() == receipt["sha256"]


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


def signing_block(data):
    require(32 <= len(data) <= 64 * 1024 and data[-16:] == b"APK Sig Block 42",
            "Unexpected bytes before ZIP central directory")
    require(struct.unpack_from("<Q", data)[0] == len(data) - 8 == struct.unpack_from("<Q", data, len(data) - 24)[0],
            "Invalid APK signing block length")
    offset, signing_ids = 8, set()
    while offset < len(data) - 24:
        require(offset + 12 <= len(data) - 24, "Truncated APK signing record")
        size, identifier = struct.unpack_from("<QI", data, offset)
        require(4 <= size <= len(data) - 24 - offset - 8 and
                identifier in {0x7109871A, 0xF05368C0, 0x1B93AD61, 0x6DFF800D, 0x42726577},
                "Unknown APK signing record")
        if identifier == 0x42726577:
            require(not any(data[offset + 12:offset + 8 + size]), "Payload in APK signing padding")
        else:
            signing_ids.add(identifier)
        offset += size + 8
    require(signing_ids, "Missing APK signature record")


def zip_envelope(path, archive):
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
            signing_block(remaining)


def compiled_resource(data, kind):
    """Check bounded Android binary chunks, not merely a .xml/.arsc suffix."""
    require(len(data) >= 8, "Truncated Android resource")
    actual, header, size = struct.unpack_from("<HHI", data)
    require(actual == kind and 8 <= header <= size == len(data), "Invalid Android resource header/length")
    offset = header
    while offset < size:
        require(offset + 8 <= size, "Truncated Android resource chunk")
        child, child_header, child_size = struct.unpack_from("<HHI", data, offset)
        require(8 <= child_header <= child_size <= size - offset, "Invalid Android resource chunk length")
        require(child in {0x0001, 0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0180,
                          0x0200, 0x0201, 0x0202, 0x0203, 0x0204, 0x0205, 0x0206},
                "Unknown Android resource chunk")
        if child == 0x0200:
            compiled_resource(data[offset:offset + child_size], child)
        offset += child_size


def compiled_dex(data):
    require(112 <= len(data) <= 32 * 1024 * 1024, "DEX outside compiled-code budget")
    require(re.fullmatch(rb"dex\n0(?:35|37|38|39|40)\x00", data[:8]), "Invalid DEX magic")
    size, header, endian = struct.unpack_from("<III", data, 32)
    require(size == len(data) and header == 112 and endian == 0x12345678, "Invalid DEX header/length")
    require(hashlib.sha1(data[32:]).digest() == data[12:32] and
            zlib.adler32(data[12:]) == struct.unpack_from("<I", data, 8)[0], "Invalid DEX checksum")


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


def check_entry(name, data, policy):
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
        require(matches(data, policy["entries"][name]), "Unapproved bytes at pinned APK entry: " + name)
    elif name == "AndroidManifest.xml":
        require(len(data) <= 256 * 1024, "Oversized manifest")
        compiled_resource(data, 0x0003)
    elif name == "resources.arsc":
        require(len(data) <= 2 * 1024 * 1024, "Oversized compiled resource table")
        compiled_resource(data, 0x0002)
    elif re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
        compiled_dex(data)
    elif RESOURCE_PATH.fullmatch(name):
        if name.endswith(".png"):
            receipt = policy["pngs"].get(hashlib.sha256(data).hexdigest())
            require(receipt is not None and len(data) == receipt["size"], "Unapproved PNG content: " + name)
        else:
            require(len(data) <= 256 * 1024, "Oversized compiled XML: " + name)
            compiled_resource(data, 0x0003)
    elif name in {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}:
        # AGP profile checksums change with compiled classes, so validate the
        # bounded generated format rather than pinning each application's hash.
        magic = b"pro\x00" if name.endswith(".prof") else b"prm\x00"
        require(8 <= len(data) <= 64 * 1024 and data[:4] == magic and
                re.fullmatch(rb"[0-9]{3}\x00", data[4:8]), "Invalid baseline profile: " + name)
    elif name.startswith("META-INF/services/"):
        # R8 renames both service types and implementations. Their only accepted
        # payload is a short Java provider-name list, not arbitrary classpath data.
        java_name = rb"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*"
        require(re.fullmatch(java_name, name.removeprefix("META-INF/services/").encode()) and
                0 < len(data) <= 1024 and re.fullmatch(java_name + rb"(?:\r?\n" + java_name + rb")*\r?\n?", data),
                "Invalid service provider metadata: " + name)
    else:
        raise ValueError("Unapproved APK entry (raw resources and arbitrary blobs are forbidden): " + name)


def inspect_apk(path, catalog, kind="app"):
    # Also catches stale APK holes left by incremental ZIP updates after removing
    # an old bundled-model build: listing entries alone is insufficient.
    require(path.stat().st_size <= MAX_APK_BYTES, "APK exceeds the 250 MiB metadata/runtime budget; clean stale packaged payloads")
    policy = json.loads(POLICY_PATH.read_text())
    require(policy["schemaVersion"] == 1, "Unknown APK content policy")
    expected = metadata_entries(catalog, kind)
    forbidden_receipts = {(f["size"], f["sha256"]) for c in catalog["components"] for f in c["artifacts"]}
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        require(len(names) == len(set(names)), "APK contains duplicate ZIP entries")
        require(sum(e.file_size for e in entries) <= MAX_APK_BYTES, "APK uncompressed content exceeds runtime budget")
        require(set(expected) <= set(names), "APK missing required trusted metadata")
        zip_envelope(path, archive)
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
                check_entry(name, data, policy)
        return {"path": str(path), "kind": kind, "bytes": path.stat().st_size, "entryCount": len(entries),
                "inspectedEntryCount": len(entries), "contentPolicySha256": hashlib.sha256(POLICY_PATH.read_bytes()).hexdigest(),
                "uncompressedBytes": sum(e.file_size for e in entries),
                "compressedPayloadBytes": sum(e.compress_size for e in entries),
                "metadataOnly": True, "onnxCount": 0, "tokenizerPayloadCount": 0,
                "maximumApkBytes": MAX_APK_BYTES}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, action="append", required=True)
    parser.add_argument("--kind", choices=("app", "android-test"), default="app")
    parser.add_argument("--catalog", type=Path, default=ROOT / "models/catalog-v1.json")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    reports = []
    for directory in args.directory:
        paths = list(directory.glob("*.apk"))
        require(len(paths) >= 1, "Expected at least one APK in " + str(directory))
        reports.extend(inspect_apk(path, catalog, args.kind) for path in sorted(paths))
    value = json.dumps(reports, indent=2) + "\n"
    if args.output:
        args.output.write_text(value)
    print(value)


if __name__ == "__main__":
    main()
