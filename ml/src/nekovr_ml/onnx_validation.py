from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence

import numpy as np
import onnx
import onnxruntime as ort

from .model import CompactCausalModel, ModelBatch
from .model_metadata import ModelSidecar, load_sidecar
from .onnx_export import INPUT_NAMES, OUTPUT_NAMES, batch_to_numpy


SUPPORTED_OPERATORS = frozenset({
    "Add", "And", "Cast", "Concat", "Constant", "Conv", "Div", "Expand", "Gather", "Greater",
    "IsInf", "IsNaN", "Log", "MatMul", "Max", "Mul", "Not", "Or", "ReduceMax", "ReduceSum",
    "Reshape", "Shape", "Sigmoid", "Squeeze", "Tanh", "Transpose", "Unsqueeze", "Where",
})


@dataclass(frozen=True)
class ParityReport:
    cases: int
    values_compared: int
    maximum_absolute_error: float
    percentile_99_absolute_error: float
    tolerance: float
    passed: bool
    provider: str

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def inspect_export(model_path: str | Path, sidecar_path: str | Path) -> ModelSidecar:
    sidecar = load_sidecar(sidecar_path, model_path)
    graph = onnx.load(model_path)
    operators = {node.op_type for node in graph.graph.node}
    unsupported = sorted(operators - SUPPORTED_OPERATORS)
    if unsupported:
        raise ValueError(f"unsupported ONNX operators: {', '.join(unsupported)}")
    if graph.opset_import[0].version != sidecar.opset:
        raise ValueError("ONNX graph and sidecar opset differ")
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    runtime_inputs = session.get_inputs()
    runtime_outputs = session.get_outputs()
    if tuple(value.name for value in runtime_inputs) != INPUT_NAMES:
        raise ValueError("ONNX input names differ from the NekoVR contract")
    if tuple(value.name for value in runtime_outputs) != OUTPUT_NAMES:
        raise ValueError("ONNX output names differ from the NekoVR contract")
    type_names = {"tensor(float)": "float32", "tensor(int64)": "int64", "tensor(bool)": "bool"}
    for runtime, declared in zip(runtime_inputs + runtime_outputs, sidecar.inputs + sidecar.outputs):
        if type_names.get(runtime.type) != declared.dtype or tuple(runtime.shape) != declared.shape:
            raise ValueError(f"ONNX tensor contract differs from sidecar for {runtime.name}")
    return sidecar


def compare_framework_and_onnx(
    model: CompactCausalModel,
    model_path: str | Path,
    batches: Sequence[ModelBatch],
    tolerance: float = 1e-5,
) -> ParityReport:
    if not batches or tolerance <= 0.0:
        raise ValueError("parity validation needs batches and a positive tolerance")
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    errors: list[np.ndarray] = []
    for batch in batches:
        expected = model.forward(batch)
        expected_values = (
            np.asarray(expected.correction_rotation_vectors, dtype=np.float32),
            np.asarray(expected.confidence, dtype=np.float32),
            np.asarray(expected.drift_rate, dtype=np.float32),
        )
        actual_values = session.run(list(OUTPUT_NAMES), batch_to_numpy(batch))
        for expected_value, actual_value in zip(expected_values, actual_values):
            if expected_value.shape != actual_value.shape or not np.isfinite(actual_value).all():
                raise ValueError("ONNX output shape differs or contains a non-finite value")
            errors.append(np.abs(expected_value - actual_value).reshape(-1))
    combined = np.concatenate(errors)
    maximum = float(np.max(combined))
    percentile_99 = float(np.percentile(combined, 99))
    return ParityReport(
        cases=len(batches), values_compared=int(combined.size), maximum_absolute_error=maximum,
        percentile_99_absolute_error=percentile_99, tolerance=tolerance,
        passed=maximum <= tolerance, provider=session.get_providers()[0],
    )
