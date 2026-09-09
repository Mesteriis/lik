#!/usr/bin/env python3
"""Create a reviewed HF catalog candidate from downloaded, parity-checked bytes."""
import argparse
import json
import math
from pathlib import Path
import struct

from artifacts import digest, validate_catalog, verify_file
from freeze_catalog import fingerprint
from prepare import ROOT

PREVIOUS_PIPELINE_FINGERPRINTS = {
    ('compact-v1', 'search'): '14648614c8e58f03f27ad3284fa54fc09b4289ff1afb4e260b0ce9b8a08d5199',
    ('balanced-v1', 'search'): 'f2d786d5ce3d4dbfe79da4e561eb93c4567926767fefc9104f2fa2e71da52a46',
    ('extended-v1', 'search'): '8e04be78106fd195556b5abadb932e86e98a6a49961b74f0b551861ad46f9342',
    ('compact-v1', 'ocr'): '4ed00992ff50205fd6d3674b6e74d94c782f3465fffbc5ec84f55a8ec0c9d0a0',
    ('balanced-v1', 'ocr'): '4ed00992ff50205fd6d3674b6e74d94c782f3465fffbc5ec84f55a8ec0c9d0a0',
    ('extended-v1', 'ocr'): 'e31c88d47c28f840ddc3098d7b3d0c1764b8a41d00ab4c58d5c4434d3027eabc',
    ('compact-v1', 'people'): 'a462e3648939f08db300d2d28e85b1eba05b696439c67ea32b6bb753c173085b',
    ('balanced-v1', 'people'): 'a462e3648939f08db300d2d28e85b1eba05b696439c67ea32b6bb753c173085b',
    ('extended-v1', 'people'): 'a462e3648939f08db300d2d28e85b1eba05b696439c67ea32b6bb753c173085b',
    ('compact-v1', 'sensitive'): 'a895a3c1aed33cafc279402e7719e2d71d432554b0a11aa4116b3b30d3674ea6',
    ('balanced-v1', 'sensitive'): 'a895a3c1aed33cafc279402e7719e2d71d432554b0a11aa4116b3b30d3674ea6',
    ('extended-v1', 'sensitive'): 'a895a3c1aed33cafc279402e7719e2d71d432554b0a11aa4116b3b30d3674ea6',
}


def activation_samples(reference_path, graph, maximum=96):
    """Keep small, exact float slices as trust-anchored metadata; never package the reference tensor."""
    outputs = graph['outputs']
    primary = graph.get('primaryOutput', outputs[0]['name'])
    before = outputs[:next(i for i, value in enumerate(outputs) if value['name'] == primary)]
    offset = sum(math.prod(value['smokeShape']) for value in before)
    count = math.prod(next(value['smokeShape'] for value in outputs if value['name'] == primary))
    payload = reference_path.read_bytes()
    values = struct.unpack('<' + 'f' * (len(payload) // 4), payload)
    primary_values = values[offset:offset + count]
    if len(primary_values) != count or not all(math.isfinite(value) for value in primary_values):
        raise ValueError('Invalid primary smoke reference')
    evenly = {round(i * (count - 1) / (min(count, maximum * 2 // 3) - 1))
              for i in range(min(count, maximum * 2 // 3))} if count > 1 else {0}
    strongest = sorted(range(count), key=lambda index: abs(primary_values[index]), reverse=True)[:maximum - len(evenly)]
    indices = sorted(evenly | set(strongest))[:maximum]
    sampled = [primary_values[index] for index in indices]
    rms = math.sqrt(sum(value * value for value in sampled) / len(sampled))
    spread = max(sampled) - min(sampled)
    absolute_tolerance = max(1e-8, rms * .002)
    if not rms > 0 or not spread > absolute_tolerance * 2:
        raise ValueError('Activation smoke reference is not informative')
    return {'path': graph['_artifactPath'], 'outputName': primary, 'outputSize': count,
            'referenceSha256': graph['smokeReference']['sha256'],
            'scaleAware': graph['_artifactPath'].startswith('ocr-'),
            'minimumNormRatio': 0.5, 'maximumNormRatio': 1.5,
            'sampleAbsoluteTolerance': absolute_tolerance, 'sampleRelativeTolerance': .02,
            'minimumRangeRatio': .5, 'maximumRangeRatio': 1.5,
            'samples': [{'index': index,
                         'floatBits': struct.pack('<f', primary_values[index])[::-1].hex()}
                        for index in indices]}


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
    activation_references = []
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
                file['onnx']['_artifactPath'] = file['path']
                activation_references.append(activation_samples(
                    args.cache / 'hf-runtime-candidates' / Path(file['path']).parent /
                    file['onnx']['smokeReference']['file'], file['onnx']))
                del file['onnx']['_artifactPath']
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
        for name, pipeline in profile['pipelines'].items():
            pipeline['fingerprint'] = fingerprint([next(c['fingerprint'] for c in catalog['components'] if c['id'] == key) for key in pipeline['components']])
            # Oracle metadata changed, but weights/tokenizers/preprocessing did not.
            pipeline['compatibleFingerprints'] = [PREVIOUS_PIPELINE_FINGERPRINTS[(profile['id'], name)]]
        profile['fingerprint'] = fingerprint(profile)
        catalog['profiles'].append(profile)
    catalog['activationSmokeReferences'] = activation_references
    catalog['catalogVersion'] = 'lik-hf-presets-2026-09-08-v3'
    catalog['oracleRevision'] = 'activation-ramp-v1'
    validate_catalog(catalog)
    args.output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    print('HF catalog review candidate:', args.output, digest(args.output))


if __name__ == '__main__':
    main()
