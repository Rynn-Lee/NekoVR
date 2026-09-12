from __future__ import annotations

import json
from pathlib import Path
import sys

import numpy as np
import onnxruntime as ort

from .onnx_export import OUTPUT_NAMES


def main(arguments: list[str] | None = None) -> int:
    values = sys.argv[1:] if arguments is None else arguments
    if len(values) != 3:
        raise SystemExit("usage: python -m nekovr_ml.onnx_runner MODEL INPUT_JSON OUTPUT_JSON")
    model, request_path, output_path = map(Path, values)
    request = json.loads(request_path.read_text(encoding="utf-8"))
    dtypes = {
        "features": np.float32, "role_ids": np.int64, "slot_mask": np.bool_, "channel_validity": np.bool_,
        "time_deltas_s": np.float32, "time_mask": np.bool_,
    }
    inputs = {name: np.asarray(request[name], dtype=dtype) for name, dtype in dtypes.items()}
    outputs = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"]).run(list(OUTPUT_NAMES), inputs)
    output_path.write_text(json.dumps({name: value.tolist() for name, value in zip(OUTPUT_NAMES, outputs)}, sort_keys=True), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
