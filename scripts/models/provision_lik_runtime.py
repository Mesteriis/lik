#!/usr/bin/env python3
"""Provision verified product-format model files into an explicitly selected emulator for Task 10 QA."""
import argparse, json, shlex, subprocess
from pathlib import Path
from artifacts import verify

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io.github.mesteriis.lik"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    if subprocess.check_output(adb + ["shell", "getprop", "ro.kernel.qemu"], text=True).strip() != "1":
        parser.error("This QA helper only provisions an explicitly selected emulator")
    catalog = json.loads((ROOT / "models/catalog-v1.json").read_text())
    files = verify(catalog, args.cache)
    source_root = args.cache / "hf-runtime"
    for descriptor in files:
        source = source_root / descriptor["path"]
        destination = "files/ai/artifacts/" + descriptor["sha256"]
        command = f"mkdir -p files/ai/artifacts && cat > {shlex.quote(destination)}"
        with source.open("rb") as stream:
            subprocess.run(adb + ["shell", f"run-as {PACKAGE} sh -c {shlex.quote(command)}"], stdin=stream, check=True)
        print("PROVISIONED", descriptor["path"], flush=True)
    print("External emulator QA files only; nothing was added to an APK or Git")

if __name__ == "__main__": main()
