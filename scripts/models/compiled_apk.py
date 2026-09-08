#!/usr/bin/env python3
"""Reviewed compiler receipts and authoritative SDK parsing; never trust APK-derived hashes."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
COMPILED_POLICY = Path(__file__).with_name("apk-compiled-policy-v1.json")
BUILD_TOOLS_VERSION = "36.0.0"
SOURCE_ROOTS = ("app/src", "app/schemas", "gradle")
SOURCE_FILES = ("app/build.gradle.kts", "app/proguard-rules.pro", "build.gradle.kts",
                "settings.gradle.kts", "gradle.properties")
TOOL_FILES = ("aapt2", "dexdump", "apksigner", "lib/apksigner.jar", "source.properties")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def receipt(data):
    return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def source_fingerprint():
    paths = {ROOT / name for name in SOURCE_FILES if (ROOT / name).is_file()}
    for name in SOURCE_ROOTS:
        paths.update(p for p in (ROOT / name).rglob("*") if p.is_file())
    digest = hashlib.sha256()
    for path in sorted(paths):
        require(not path.is_symlink(), "Source fingerprint does not accept symlinks")
        digest.update(path.relative_to(ROOT).as_posix().encode() + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def build_tools_directory(explicit=None):
    if explicit:
        result = Path(explicit)
    else:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            properties = ROOT / "local.properties"
            match = re.search(r"^sdk.dir=(.+)$", properties.read_text(), re.M) if properties.is_file() else None
            sdk = match.group(1) if match else None
        require(sdk, "Android SDK required: set ANDROID_HOME or pass --build-tools")
        result = Path(sdk) / "build-tools" / BUILD_TOOLS_VERSION
    require((result / "source.properties").is_file() and
            re.search(r"^Pkg.Revision=" + re.escape(BUILD_TOOLS_VERSION) + r"\s*$",
                      (result / "source.properties").read_text(), re.M), "Build-tools 36.0.0 required")
    for name in TOOL_FILES:
        require((result / name).is_file(), "Missing SDK parser: " + name)
    return result


def run_tool(command, output_file=None):
    # Parsers are pinned SDK binaries, not shell commands or APK-controlled tools.
    with tempfile.TemporaryFile() as output:
        result = subprocess.run([str(c) for c in command], stdout=output, stderr=subprocess.PIPE, timeout=60)
        require(result.returncode == 0, "SDK parser rejected input: " + str(command[0]) + ": " + result.stderr.decode(errors="replace")[:2000])
        require(not re.search(rb"(?i)(?:^|\n)(?:error|.*\b E (?:dexdump|aapt))", result.stderr), "SDK parser reported an error")
        output.seek(0, 2)
        require(output.tell() <= 16 * 1024 * 1024, "SDK parser output exceeds metadata budget")
        output.seek(0)
        data = output.read()
    if output_file:
        Path(output_file).write_bytes(data)
    return data.decode("utf-8", errors="strict")


def resource_semantics(archive, tools):
    text = run_tool([tools / "aapt2", "dump", "resources", archive])
    packages = re.findall(r"^Package name=(\S+) id=([0-9a-f]+)$", text, re.M)
    require(len(packages) == 1 and packages[0][1] == "7f", "Unexpected resource package")
    types = sorted(set(re.findall(r"^  type (\S+) id=", text, re.M)))
    require(types and "raw" not in types, "Raw resource entries are forbidden, including obfuscated paths")
    files = sorted(set(re.findall(r"\(file\) (res/\S+) type=", text)))
    with zipfile.ZipFile(archive) as zipped:
        actual_files = sorted(n for n in zipped.namelist() if n.startswith("res/"))
        require(files == actual_files, "Resource table must account for every resource file exactly")
        for name in ["AndroidManifest.xml"] + [n for n in actual_files if n.endswith(".xml")]:
            tree = run_tool([tools / "aapt2", "dump", "xmltree", "--file", name, archive])
            require("E: " in tree, "Compiled XML has no parsed element tree: " + name)
    return {"package": packages[0][0], "types": types, "files": files}


def parse_dex(path, tools):
    # -c alone only checks the checksum. Default verification plus disassembly,
    # section/string traversal exercises ART's full DexFileVerifier.
    run_tool([tools / "dexdump", "-d", "-h", "-s", "-o", os.devnull, path])


def verify_signature(path, tools):
    run_tool([tools / "apksigner", "verify", "--min-sdk-version", "36", "--verbose", path])


def verify_parser_files(toolchain, tools):
    actual = {name: receipt((tools / name).read_bytes()) for name in TOOL_FILES}
    approved = [toolchain["parserFiles"]] + [p["parserFiles"] for p in toolchain.get("compatibleParserDistributions", [])]
    require(actual in approved, "SDK parsers differ from every reviewed toolchain distribution")


def compiler_variant(name, tools):
    require(name in {"debug", "release", "debugAndroidTest"}, "New variants need an explicit compiler-input mapping")
    intermediates = ROOT / "app/build/intermediates"
    suffix = name[0].upper() + name[1:]
    stage = "optimized_processed_res" if name == "release" else "linked_resources_binary_format"
    resources = list((intermediates / stage / name).glob("*/*.ap_"))
    require(len(resources) == 1, "Build compiler resources first: " + name)
    resource_archive = resources[0]
    semantics = resource_semantics(resource_archive, tools)
    expected_package = "io.github.mesteriis.lik" + (".test" if name == "debugAndroidTest" else "")
    require(semantics["package"] == expected_package, "Compiler resource package mismatch")
    with zipfile.ZipFile(resource_archive) as archive:
        entries = {n: receipt(archive.read(n)) for n in archive.namelist()}
    require(set(entries) == set(semantics["files"]) | {"AndroidManifest.xml", "resources.arsc"}, "Unrecognized compiler resource entries")
    dex_paths = []
    if name == "release":
        dex_paths.extend((intermediates / "dex" / name / f"minify{suffix}WithR8").rglob("*.dex"))
    else:
        for category in ("Ext", "Lib", "Project"):
            dex_paths.extend((intermediates / "dex" / name / f"merge{category}Dex{suffix}").rglob("*.dex"))
        dex_paths.extend((intermediates / "global_synthetics_dex" / name / f"generate{suffix}GlobalSynthetics").rglob("*.dex"))
    require(dex_paths, "Build D8/R8 outputs first: " + name)
    dex = []
    for path in sorted(dex_paths):
        require(not path.is_symlink(), "Compiler inputs may not be symlinks")
        parse_dex(path, tools)
        dex.append(receipt(path.read_bytes()) | {"compilerPath": path.relative_to(ROOT).as_posix()})
    profiles = {}
    for directory, extension in (("binary_art_profile", "prof"), ("binary_art_profile_metadata", "profm")):
        paths = list((intermediates / directory / name).glob(f"*/baseline.{extension}"))
        require(len(paths) <= 1, "Ambiguous compiler profile output")
        if paths:
            profiles[f"assets/dexopt/baseline.{extension}"] = receipt(paths[0].read_bytes()) | {"compilerPath": paths[0].relative_to(ROOT).as_posix()}
    return {"kind": "android-test" if name == "debugAndroidTest" else "app",
            "resourceArchive": resource_archive.relative_to(ROOT).as_posix(),
            "resourceEntries": entries, "resourceSemantics": semantics, "dex": dex, "profiles": profiles}


class CompiledReceipts:
    def __init__(self, kind, variant=None, build_tools=None):
        self.policy = json.loads(COMPILED_POLICY.read_text())
        require(self.policy["schemaVersion"] == 1, "Unknown compiled receipt policy")
        require(self.policy["sourceFingerprint"] == source_fingerprint(),
                "Android source/toolchain inputs differ from reviewed compiled receipts; prepare and review a new candidate")
        self.candidates = {name: value for name, value in self.policy["variants"].items()
                           if value["kind"] == kind and (variant is None or name == variant)}
        require(self.candidates, "No reviewed receipts for this APK variant")
        self.requested_tools = build_tools
        self.seen_resources, self.seen_dex, self.seen_profiles = {}, [], {}
        self.has_core = False

    def check(self, name, data):
        file = receipt(data)
        if name in {"AndroidManifest.xml", "resources.arsc"} or (name.startswith("res/") and name.endswith(".xml")):
            self.has_core = True
            self.seen_resources[name] = file
            self.candidates = {v: p for v, p in self.candidates.items() if p["resourceEntries"].get(name) == file}
        elif name.startswith("res/") and name.endswith(".png"):
            self.seen_resources[name] = file
            self.candidates = {v: p for v, p in self.candidates.items() if p["resourceEntries"].get(name) == file}
        elif re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
            self.has_core = True
            self.seen_dex.append((name, file))
            self.candidates = {v: p for v, p in self.candidates.items()
                               if any(all(d[k] == file[k] for k in file) for d in p["dex"])}
        elif name in {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}:
            self.has_core = True
            self.seen_profiles[name] = file
            self.candidates = {v: p for v, p in self.candidates.items()
                               if name in p["profiles"] and all(p["profiles"][name][k] == file[k] for k in file)}
        else:
            return False
        require(self.candidates, "APK bytes are not reviewed compiler output: " + name)
        return True

    def verify(self, apk):
        if not self.has_core:
            return None  # Metadata-only unit fixtures; not an APK validity claim.
        valid = {}
        for variant, policy in self.candidates.items():
            dex = Counter((d["size"], d["sha256"]) for d in policy["dex"])
            seen = Counter((d["size"], d["sha256"]) for _, d in self.seen_dex)
            profiles = {n: {k: f[k] for k in ("size", "sha256")} for n, f in policy["profiles"].items()}
            names = {"classes.dex" if n == 1 else f"classes{n}.dex" for n in range(1, len(self.seen_dex) + 1)}
            if (self.seen_resources == policy["resourceEntries"] and seen == dex and
                    {n for n, _ in self.seen_dex} == names and self.seen_profiles == profiles):
                valid[variant] = policy
        require(len(valid) == 1, "APK compiled inventory is incomplete, duplicated, or mixes variants")
        variant, policy = next(iter(valid.items()))
        tools = build_tools_directory(self.requested_tools)
        verify_parser_files(self.policy["toolchain"], tools)
        require(resource_semantics(apk, tools) == policy["resourceSemantics"], "APK resource semantics differ from compiler receipts")
        with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
            for name, _ in self.seen_dex:
                path = Path(temporary) / name
                path.write_bytes(archive.read(name))
                parse_dex(path, tools)
        return {"variant": variant, "policySha256": hashlib.sha256(COMPILED_POLICY.read_bytes()).hexdigest(),
                "sourceFingerprint": self.policy["sourceFingerprint"], "sdkParsers": "aapt2 + ART dexdump 36.0.0"}


def main():
    parser = argparse.ArgumentParser(description="Prepare a REVIEW CANDIDATE from compiler intermediates, never from an APK")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path)
    args = parser.parse_args()
    require(args.output.resolve() != COMPILED_POLICY.resolve(), "Cannot overwrite the trust anchor; review a separate candidate")
    tools = build_tools_directory(args.build_tools)
    before = source_fingerprint()
    toolchain = {"agp": "9.2.1", "gradle": "9.4.1", "jdkMajor": 17, "buildTools": BUILD_TOOLS_VERSION,
                 "parserFiles": {name: receipt((tools / name).read_bytes()) for name in TOOL_FILES}}
    if COMPILED_POLICY.is_file():
        # Carry forward only the already reviewed SDK distributions. New SDK
        # bytes need an explicit provenance review, never automatic enrollment.
        toolchain = json.loads(COMPILED_POLICY.read_text())["toolchain"]
        require(toolchain["buildTools"] == BUILD_TOOLS_VERSION, "Review SDK policy before changing build-tools")
        verify_parser_files(toolchain, tools)
    policy = {"schemaVersion": 1, "sourceFingerprint": before,
              "sourceInputs": {"roots": SOURCE_ROOTS, "files": SOURCE_FILES},
              "provenance": "Explicitly reviewed compiler outputs; never updated by a build or inferred from an inspected APK.",
              "toolchain": toolchain,
              "variants": {name: compiler_variant(name, tools) for name in ("debug", "release", "debugAndroidTest")}}
    require(before == source_fingerprint(), "Sources changed during candidate preparation")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(policy, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print("Candidate only; inspect sources, compiler paths and receipts before replacing the checked-in policy: " + str(args.output))


if __name__ == "__main__":
    main()
