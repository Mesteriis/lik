#!/usr/bin/env python3
"""Check pinned Paddle inference against its exported ONNX on synthetic data."""
import argparse
import json
from pathlib import Path
import numpy as np
import paddle
import onnxruntime as ort


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--component", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    shape = [1, 3, 48, 320] if args.component == "ocr-cyrillic-rec-v1" else [1, 3, 96, 160]
    config = paddle.inference.Config(str(args.source / "inference.json"), str(args.source / "inference.pdiparams"))
    config.disable_gpu()
    config.set_cpu_math_library_num_threads(4)
    predictor = paddle.inference.create_predictor(config)
    value = np.random.default_rng(0).normal(0, .3, shape).astype("float32")
    handle = predictor.get_input_handle(predictor.get_input_names()[0])
    handle.reshape(shape)
    handle.copy_from_cpu(value)
    predictor.run()
    expected = predictor.get_output_handle(predictor.get_output_names()[0]).copy_to_cpu()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.model), options, providers=["CPUExecutionProvider"])
    actual = session.run(None, {"x": value})[0]
    match = bool(np.allclose(actual, expected, atol=1e-4, rtol=1e-3))
    report = {"component": args.component, "paddle": paddle.__version__, "onnxruntime": ort.__version__,
              "syntheticInputShape": shape, "outputShape": list(actual.shape),
              "maxAbsoluteError": float(np.max(np.abs(actual - expected))), "allclose": match,
              "atol": 1e-4, "rtol": 1e-3, "qualityBenchmark": False}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    if not match:
        raise ValueError("Paddle/ONNX numerical conversion parity failed")


if __name__ == "__main__":
    main()
