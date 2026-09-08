#!/usr/bin/env python3
"""Create a reviewed HF catalog candidate from downloaded, parity-checked bytes."""
import argparse
import json
from pathlib import Path

from artifacts import digest, validate_catalog, verify_file
from freeze_catalog import fingerprint
from prepare import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Choose a new review candidate; never overwrite a published catalog')
    reference = json.loads((ROOT / 'models/prepared-reference-v1.json').read_text())
    contracts = json.loads((ROOT / 'models/contracts-v1.json').read_text())['components']
    delivery = json.loads((ROOT / 'models/hf-delivery-v1.json').read_text())
    catalog = {key: value for key, value in reference.items() if key not in ('components', 'profiles')}
    catalog.update(catalogVersion='lik-hf-presets-2026-09-08-v1', delivery='settings-download-from-huggingface',
                   initialRuntimeReady=False, backendPolicy='backend-policy-v1.json',
                   qualityAcceptance='not-run; licensed external fixtures and calibration required',
                   components=[], profiles=[])
    for original in reference['components']:
        component_id = original['id']
        files = [dict(file) for file in delivery['artifacts'] if file['path'].startswith(component_id + '/')]
        evidence_path = args.cache / 'research/hf-delivery' / (component_id + '-validation.json')
        evidence = json.loads(evidence_path.read_text()) if evidence_path.exists() else {'models': {}, 'parity': {}}
        component = {'id': component_id, 'contract': contracts[component_id], 'sources': original['sources'],
                     'artifacts': files,
                     'preparation': {'kind': 'existing Hugging Face artifacts; never modified by Lik downloader',
                                     'verificationCommand': f'python scripts/models/verify_hf_runtime.py --cache CACHE --component {component_id}' if component_id != 'siglip2-tokenizer-v1' else 'verify publisher tokenizer bytes and fixed golden vectors',
                                     'toolLockSha256': digest(ROOT / 'scripts/models/requirements-export.lock'),
                                     'upstreamExportCommand': None,
                                     'upstreamExportCommandStatus': 'not disclosed in pinned conversion repositories; producer metadata is inspected in each graph'}}
        for file in files:
            verify_file(args.cache / 'hf-runtime-candidates', file)
            if file['path'].endswith('.onnx'):
                name = Path(file['path']).name
                file['onnx'] = evidence['models'][name]
                file['onnx']['smokeReference']['developmentOnly'] = True
                file['conversionParity'] = evidence['parity'][name]
                if not file['onnx']['hostValidation']['finiteOutputs']:
                    raise ValueError('Missing host runtime acceptance')
        if component_id == 'sensitive-v1':
            component['preparation']['conversionProvenance'] = 'HF ONNX export bot on behalf of intellichain, immutable unmerged PR #5 commit; parent 0c26ec22111b83f106d72a55f611ec35962bcb65; independently validated against publisher safetensors'
        elif component_id in ('yunet-v1', 'sface-v1'):
            component['preparation']['conversionProvenance'] = 'Official OpenCV HF ONNX; byte-identical to pinned OpenCV Zoo source'
        elif component_id.startswith('ocr-'):
            component['preparation']['conversionProvenance'] = 'OllmOne community conversion; independently checked against pinned PaddlePaddle inference weights and dictionary'
        elif component_id in ('clip-image-v1', 'siglip2-base-v1', 'siglip2-large-v1'):
            component['preparation']['conversionProvenance'] = 'Xenova / onnx-community conversion; linked publisher model and numerical parity independently verified'
        else:
            component['preparation']['conversionProvenance'] = 'Original publisher Hugging Face files'
        component['fingerprint'] = fingerprint(component)
        catalog['components'].append(component)
    for original in reference['profiles']:
        profile = {key: value for key, value in original.items() if key != 'fingerprint'}
        for pipeline in profile['pipelines'].values():
            pipeline['fingerprint'] = fingerprint([next(c['fingerprint'] for c in catalog['components'] if c['id'] == key) for key in pipeline['components']])
        profile['fingerprint'] = fingerprint(profile)
        catalog['profiles'].append(profile)
    validate_catalog(catalog)
    args.output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    print('HF catalog review candidate:', args.output, digest(args.output))


if __name__ == '__main__':
    main()
