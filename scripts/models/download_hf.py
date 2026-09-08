#!/usr/bin/env python3
"""Developer reproduction of frozen HF downloads. Android Settings is Task 10."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

from artifacts import digest, validate_catalog, verify_file
from download_ranges import download_ranges

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--hf', default='hf')
    parser.add_argument('--range-workers', type=int, default=0, help='Optional bounded HTTPS fallback to the identical frozen HF URL')
    parser.add_argument('--publish-existing', action='store_true', help='Verify already downloaded candidates and atomically publish a new developer cache')
    args = parser.parse_args()
    cache = args.cache.resolve()
    if cache == ROOT or ROOT in cache.parents:
        parser.error('Model cache must be outside the repository')
    catalog = json.loads((ROOT / 'models/catalog-v1.json').read_text())
    files = validate_catalog(catalog)
    if catalog.get('delivery') != 'settings-download-from-huggingface':
        parser.error('Only the frozen HF delivery catalog is supported')
    staging, final = cache / 'hf-runtime-candidates', cache / 'hf-runtime'
    if final.exists():
        for file in files:
            verify_file(final, file)
        print('Existing HF runtime verified:', len(files), 'files')
        return
    staging.mkdir(parents=True, exist_ok=True)
    for file in files:
        target = staging / file['path']
        if not args.publish_existing and not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            if args.range_workers:
                download_ranges(file, target, workers=args.range_workers)
            else:
                local = cache / 'hf-downloads' / file['repo'] / file['revision']
                subprocess.run([args.hf, 'download', file['repo'], file['remoteFile'], '--revision', file['revision'], '--local-dir', str(local)], check=True)
                downloaded = local / file['remoteFile']
                if downloaded.stat().st_size != file['size'] or digest(downloaded) != file['sha256']:
                    raise ValueError('HF download differs from frozen catalog: ' + file['path'])
                shutil.copyfile(downloaded, target)
        verify_file(staging, file)
        print('VERIFIED', file['path'], flush=True)
    # Keep inspected candidates for reproducibility. This is a developer publication,
    # not the app's future transactional/resumable Settings implementation.
    temporary = cache / 'hf-runtime-publishing'
    if temporary.exists():
        raise ValueError('Remove or inspect a previous publishing directory explicitly')
    shutil.copytree(staging, temporary)
    for file in files:
        verify_file(temporary, file)
    temporary.rename(final)
    print('Published complete HF runtime cache; no APK files changed')


if __name__ == '__main__':
    main()
