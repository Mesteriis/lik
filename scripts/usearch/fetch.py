#!/usr/bin/env python3
"""Fetch pinned USearch C/C++ sources into build output for a 16 KiB Android build."""
import argparse, hashlib, io, pathlib, urllib.request, zipfile

VERSION = "2.26.0"
COMMIT = "cc23bbaf21ef52313c5a495adbc40cbd733cdcfb"
URL = f"https://github.com/unum-cloud/USearch/archive/refs/tags/v{VERSION}.zip"
SHA256 = "b84a8926587c2f768d68393257df66a95235695bdf6194921e4c6a3fe4112565"
FILES = {
    "c/lib.cpp": "lib.cpp",
    "c/usearch.h": "usearch.h",
    "include/usearch/index.hpp": "include/usearch/index.hpp",
    "include/usearch/index_dense.hpp": "include/usearch/index_dense.hpp",
    "include/usearch/index_plugins.hpp": "include/usearch/index_plugins.hpp",
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = args.output / "src"
    marker = root / "receipt.txt"
    expected = f"USearch {VERSION}\ncommit {COMMIT}\narchive-sha256 {SHA256}\n"
    if marker.is_file() and marker.read_text() == expected and all((root / dst).is_file() for dst in FILES.values()):
        print(f"USearch {VERSION}: existing verified source build input")
        return
    with urllib.request.urlopen(URL, timeout=60) as response:
        payload = response.read()
    if hashlib.sha256(payload).hexdigest() != SHA256:
        raise SystemExit("USearch archive SHA-256 mismatch")
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        prefix = f"USearch-{VERSION}/"
        for source, destination in FILES.items():
            target = root / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(prefix + source))
    marker.write_text(expected)
    print(f"USearch {VERSION}: fetched pinned sources for local 16 KiB build")

if __name__ == "__main__": main()
