#!/usr/bin/env python3
"""Provision verified model fixtures into a debug emulator's private test directory."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

from artifacts import verify, verify_file

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'io.github.mesteriis.lik'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--component', help='Optional one-component diagnostic; full acceptance requires all models')
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]
    if subprocess.check_output(adb + ['shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() != '1':
        parser.error('This QA helper only provisions an explicitly selected emulator')
    catalog = json.loads((ROOT / 'models/catalog-v1.json').read_text())
    files = verify(catalog, args.cache)
    root = args.cache / 'hf-runtime'
    for file in files:
        if not file['path'].endswith('.onnx') or (args.component and not file['path'].startswith(args.component + '/')):
            continue
        reference = file['onnx']['smokeReference']
        reference_file = {'path': str(Path(file['path']).parent / reference['file']),
                          'size': reference['size'], 'sha256': reference['sha256']}
        for descriptor in (file, reference_file):
            # Product downloads omit synthetic QA outputs. The host validator writes
            # those separately, including after the runtime directory is published.
            fixture_root = root if descriptor is file else args.cache / 'hf-runtime-candidates'
            path = verify_file(fixture_root, descriptor)
            destination = 'files/model-probe/' + descriptor['path']
            command = f'mkdir -p {shlex.quote(str(Path(destination).parent))} && cat > {shlex.quote(destination)}'
            with path.open('rb') as stream:
                subprocess.run(adb + ['shell', f'run-as {PACKAGE} sh -c {shlex.quote(command)}'], stdin=stream, check=True)
            print('PROVISIONED', descriptor['path'], flush=True)
    print('External QA files only; APK and product readiness remain unchanged')


if __name__ == '__main__':
    main()
