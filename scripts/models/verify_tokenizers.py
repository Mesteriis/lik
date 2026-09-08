#!/usr/bin/env python3
"""Verify standalone downloaded tokenizers against all fixed publisher token vectors."""
import argparse
import json
from pathlib import Path

from artifacts import verify_file

ROOT = Path(__file__).resolve().parents[2]


def load_tokenizer(directory, component):
    from transformers import DistilBertTokenizerFast, GemmaTokenizerFast
    implementations = {'multilingual-text-v1': DistilBertTokenizerFast,
                       'siglip2-tokenizer-v1': GemmaTokenizerFast}
    return implementations[component].from_pretrained(str(directory), local_files_only=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    catalog = json.loads((ROOT / 'models/catalog-v1.json').read_text())
    golden = json.loads((ROOT / 'models/evaluation/tokenizer-golden-v1.json').read_text())
    count = 0
    for fixture in golden['fixtures']:
        component = next(c for c in catalog['components'] if c['id'] == fixture['component'])
        for file in component['artifacts']:
            if not file['path'].endswith(('.onnx', '.safetensors')):
                verify_file(args.cache / 'hf-runtime', file)
        tokenizer = load_tokenizer(args.cache / 'hf-runtime' / component['id'], component['id'])
        if type(tokenizer).__name__ != fixture['implementation']:
            raise ValueError('Tokenizer implementation differs from pinned publisher')
        for case in fixture['cases']:
            actual = tokenizer(case['text'], max_length=fixture['maxLength'], padding=fixture['padding'], truncation=fixture['truncation'])
            for key in ('input_ids', 'attention_mask'):
                if key in case and actual[key] != case[key]:
                    raise ValueError(f"Golden token mismatch: {component['id']}/{case['id']}/{key}")
            count += 1
    print('Verified', count, 'publisher golden token vectors from standalone downloaded files')


if __name__ == '__main__':
    main()
