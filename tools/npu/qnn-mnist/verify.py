#!/usr/bin/env python3
"""Compare SM8635 QNN MNIST outputs with the original ONNX model and labels."""

import argparse
import gzip
from pathlib import Path

import numpy as np
import onnxruntime as ort


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workdir", type=Path, required=True)
    parser.add_argument("--start", type=int, choices=(0, 100), required=True)
    args = parser.parse_args()

    root = args.workdir
    with gzip.open(root / "t10k-images-idx3-ubyte.gz", "rb") as source:
        images = np.frombuffer(source.read(), dtype=np.uint8, offset=16)
        images = images.reshape(-1, 1, 28, 28)
    with gzip.open(root / "t10k-labels-idx1-ubyte.gz", "rb") as source:
        labels = np.frombuffer(source.read(), dtype=np.uint8, offset=8)

    output_dir = root / "android" / ("output" if args.start == 0 else "heldout_output")
    native = []
    for result in range(100):
        file = output_dir / f"Result_{result}" / "Plus214_Output_0_native.raw"
        logits = np.fromfile(file, dtype=np.uint8)
        if logits.size != 10:
            raise ValueError(f"{file}: expected 10 U8 logits, got {logits.size}")
        native.append(logits)
    native = np.stack(native)
    decoded = (native.astype(np.float32) - 115) * 0.21635721623897553

    session = ort.InferenceSession(str(root / "mnist-12.onnx"), providers=["CPUExecutionProvider"])
    cpu = np.concatenate([
        session.run(None, {"Input3": images[i:i + 1].astype(np.float32) / 255})[0]
        for i in range(args.start, args.start + 100)
    ])
    truth = labels[args.start:args.start + 100]
    cpu_classes = cpu.argmax(axis=1)
    npu_classes = native.argmax(axis=1)
    print(f"range={args.start}-{args.start + 99}")
    print(f"cpu_accuracy={np.sum(cpu_classes == truth)}/100")
    print(f"npu_accuracy={np.sum(npu_classes == truth)}/100")
    print(f"cpu_npu_agreement={np.sum(cpu_classes == npu_classes)}/100")
    print(f"mean_abs_logit_error={np.mean(np.abs(decoded - cpu)):.9f}")
    print(f"max_abs_logit_error={np.max(np.abs(decoded - cpu)):.9f}")
    print(f"npu_classes={sorted(np.unique(npu_classes).tolist())}")


if __name__ == "__main__":
    main()
