from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Sequence

import numpy as np
import onnx
from onnx import TensorProto, numpy_helper
import onnxruntime as ort

from .model import CompactCausalModel, ModelBatch
from .model_metadata import ModelSidecar, load_sidecar
from .onnx_export import (
    INPUT_NAMES, OPSET_VERSION, OUTPUT_NAMES, SUPPORTED_BATCH_BOUNDS, SUPPORTED_CONTEXT_BOUNDS,
    SUPPORTED_SLOT_BOUNDS, batch_to_numpy, canonical_tensor_contract,
)
from .provenance import canonical_hash, file_sha256


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


def _runtime_dtype(value: str) -> str | None:
    return {"tensor(float)": "float32", "tensor(int64)": "int64", "tensor(bool)": "bool"}.get(value)


def _shape(value: onnx.ValueInfoProto) -> tuple[str | int, ...]:
    result: list[str | int] = []
    for dimension in value.type.tensor_type.shape.dim:
        if dimension.HasField("dim_value"):
            result.append(int(dimension.dim_value))
        elif dimension.HasField("dim_param") and dimension.dim_param:
            result.append(dimension.dim_param)
        else:
            raise ValueError(f"ONNX tensor {value.name} has an unspecified dimension")
    return tuple(result)


def _tensor_metadata(value: onnx.ValueInfoProto) -> tuple[str, str | None, tuple[str | int, ...]]:
    dtype = {TensorProto.FLOAT: "float32", TensorProto.INT64: "int64", TensorProto.BOOL: "bool"}.get(value.type.tensor_type.elem_type)
    return value.name, dtype, _shape(value)


def _static_dimension(value: onnx.ValueInfoProto, index: int, label: str) -> int:
    shape = _shape(value)
    if index >= len(shape) or not isinstance(shape[index], int) or shape[index] <= 0:
        raise ValueError(f"ONNX {label} must be a positive static dimension")
    return shape[index]


def _contract_inputs(batch: int, context: int, slots: int, feature_width: int, roles: Sequence[int]) -> dict[str, np.ndarray]:
    role_values = np.asarray([roles[index % len(roles)] for index in range(slots)], dtype=np.int64)
    features = np.fromfunction(
        lambda b, t, s, f: ((b + 1) * 11 + (t + 1) * 7 + (s + 1) * 3 + f) / 100.0,
        (batch, context, slots, feature_width), dtype=int,
    ).astype(np.float32)
    return {
        "features": features,
        "role_ids": np.repeat(role_values[None, :], batch, axis=0),
        "slot_mask": np.ones((batch, slots), dtype=np.bool_),
        "channel_validity": np.ones((batch, context, slots, feature_width), dtype=np.bool_),
        "time_deltas_s": np.full((batch, context), 0.02, dtype=np.float32),
        "time_mask": np.ones((batch, context), dtype=np.bool_),
    }


def validate_runtime_inputs(inputs: dict[str, np.ndarray], sidecar: ModelSidecar, feature_width: int, role_count: int) -> None:
    """Validate invocation bounds before passing an input to ONNX Runtime."""
    if set(inputs) != set(INPUT_NAMES):
        raise ValueError("runtime input names differ from the canonical contract")
    features = inputs["features"]
    if features.ndim != 4 or features.shape[3] != feature_width:
        raise ValueError("runtime feature rank or width differs from the canonical contract")
    batch, context, slots, _ = features.shape
    if not SUPPORTED_BATCH_BOUNDS["minimum"] <= batch <= SUPPORTED_BATCH_BOUNDS["maximum"]:
        raise ValueError("runtime batch exceeds supported bounds")
    if not int(sidecar.context_bounds["minimum"]) <= context <= int(sidecar.context_bounds["maximum"]):
        raise ValueError("runtime context exceeds sidecar bounds")
    if not int(sidecar.slot_bounds["minimum"]) <= slots <= int(sidecar.slot_bounds["maximum"]):
        raise ValueError("runtime slots exceed sidecar bounds")
    expected = {
        "role_ids": ((batch, slots), np.int64), "slot_mask": ((batch, slots), np.bool_),
        "channel_validity": ((batch, context, slots, feature_width), np.bool_),
        "time_deltas_s": ((batch, context), np.float32), "time_mask": ((batch, context), np.bool_),
    }
    if features.dtype != np.float32:
        raise ValueError("runtime features must be float32")
    for name, (shape, dtype) in expected.items():
        if inputs[name].shape != shape or inputs[name].dtype != dtype:
            raise ValueError(f"runtime tensor {name} has a noncanonical shape or dtype")
    if np.any(inputs["role_ids"] < 0) or np.any(inputs["role_ids"] >= role_count):
        raise ValueError("runtime role ID exceeds model bounds")


def _validate_output_values(values: Sequence[np.ndarray], batch: int, slots: int, output_axes: int = 3) -> None:
    expected = ((batch, slots, output_axes), (batch, slots), (batch, slots))
    if len(values) != len(expected):
        raise ValueError("ONNX runtime omitted a declared output")
    for value, shape in zip(values, expected):
        if value.shape != shape or value.dtype != np.float32 or not np.isfinite(value).all():
            raise ValueError("ONNX output shape, dtype, or finiteness violates the canonical contract")


def inspect_export(model_path: str | Path, sidecar_path: str | Path) -> ModelSidecar:
    sidecar = load_sidecar(sidecar_path, model_path)
    graph = onnx.load(model_path)
    onnx.checker.check_model(graph)
    unsupported = sorted({node.op_type for node in graph.graph.node} - SUPPORTED_OPERATORS)
    if unsupported:
        raise ValueError(f"unsupported ONNX operators: {', '.join(unsupported)}")
    if [(value.domain, value.version) for value in graph.opset_import] != [("", OPSET_VERSION)] or sidecar.opset != OPSET_VERSION:
        raise ValueError(f"ONNX model must use the supported default opset {OPSET_VERSION}")

    feature_width = _static_dimension(graph.graph.input[0], 3, "feature width")
    output_axes = _static_dimension(graph.graph.output[0], 2, "correction axes")
    canonical_inputs, canonical_outputs = canonical_tensor_contract(feature_width, output_axes)
    if tuple(_tensor_metadata(value) for value in graph.graph.input) != tuple((value.name, value.dtype, value.shape) for value in canonical_inputs):
        raise ValueError("ONNX inputs differ from the canonical NekoVR contract")
    if tuple(_tensor_metadata(value) for value in graph.graph.output) != tuple((value.name, value.dtype, value.shape) for value in canonical_outputs):
        raise ValueError("ONNX outputs differ from the canonical NekoVR contract")
    if sidecar.inputs != canonical_inputs or sidecar.outputs != canonical_outputs:
        raise ValueError("sidecar tensor names, dtypes, shapes, or semantics are noncanonical")
    if len(sidecar.normalization["mean"]) != feature_width:
        raise ValueError("normalization width differs from the ONNX feature width")

    initializers = {value.name: numpy_helper.to_array(value) for value in graph.graph.initializer}
    required_initializers = {"role_embeddings", "maximum_correction", "maximum_drift_rate"}
    if not required_initializers.issubset(initializers):
        raise ValueError("ONNX graph omits canonical role or output-bound parameters")
    role_count = int(initializers["role_embeddings"].shape[0])
    if not sidecar.supported_roles or any(role >= role_count for role in sidecar.supported_roles):
        raise ValueError("sidecar role bounds exceed the ONNX role embedding table")
    if int(sidecar.slot_bounds["maximum"]) > role_count:
        raise ValueError("sidecar slot bounds exceed the ONNX role capacity")
    if (
        int(sidecar.context_bounds["minimum"]) < SUPPORTED_CONTEXT_BOUNDS["minimum"]
        or int(sidecar.context_bounds["maximum"]) > SUPPORTED_CONTEXT_BOUNDS["maximum"]
        or int(sidecar.slot_bounds["minimum"]) < SUPPORTED_SLOT_BOUNDS["minimum"]
        or int(sidecar.slot_bounds["maximum"]) > SUPPORTED_SLOT_BOUNDS["maximum"]
    ):
        raise ValueError("sidecar context or slot bounds exceed the server-supported contract")
    expected_convention = {
        "correction": "q_corrected = rotation_vector_world * q_pre_ai", "quaternion_order": "xyzw",
        "correction_axes": "world_xyz", "maximum_correction_radians": float(initializers["maximum_correction"].reshape(-1)[0]),
        "maximum_drift_rate_radians_per_second": float(initializers["maximum_drift_rate"].reshape(-1)[0]),
        "masked_slot_output": "zero", "time_padding": "left",
    }
    if set(sidecar.output_convention) != set(expected_convention):
        raise ValueError("sidecar output semantics are noncanonical")
    for name, expected in expected_convention.items():
        actual = sidecar.output_convention[name]
        if isinstance(expected, float):
            if not np.isfinite(float(actual)) or not np.isclose(float(actual), expected, rtol=0.0, atol=1e-7):
                raise ValueError(f"sidecar output bound {name} differs from model bytes")
        elif actual != expected:
            raise ValueError(f"sidecar output semantic {name} is noncanonical")

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    runtime_values = session.get_inputs() + session.get_outputs()
    for runtime, canonical in zip(runtime_values, canonical_inputs + canonical_outputs):
        if runtime.name != canonical.name or _runtime_dtype(runtime.type) != canonical.dtype or tuple(runtime.shape) != canonical.shape:
            raise ValueError(f"ONNX runtime tensor contract is noncanonical for {runtime.name}")
    for context in {int(sidecar.context_bounds["minimum"]), int(sidecar.context_bounds["maximum"])}:
        for slots in {int(sidecar.slot_bounds["minimum"]), int(sidecar.slot_bounds["maximum"])}:
            inputs = _contract_inputs(1, context, slots, feature_width, sidecar.supported_roles)
            validate_runtime_inputs(inputs, sidecar, feature_width, role_count)
            _validate_output_values(session.run(list(OUTPUT_NAMES), inputs), 1, slots, output_axes)
    return sidecar


def compare_framework_and_onnx(model: CompactCausalModel, model_path: str | Path, batches: Sequence[ModelBatch], tolerance: float = 1e-5) -> ParityReport:
    if not batches or tolerance <= 0.0:
        raise ValueError("parity validation needs batches and a positive tolerance")
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    errors: list[np.ndarray] = []
    for batch in batches:
        expected = model.forward(batch)
        expected_values = (
            np.asarray(expected.correction_rotation_vectors, dtype=np.float32), np.asarray(expected.confidence, dtype=np.float32),
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
    return ParityReport(len(batches), int(combined.size), maximum, percentile_99, tolerance, maximum <= tolerance, session.get_providers()[0])


def _boundary_batches(model: CompactCausalModel, sidecar: ModelSidecar) -> tuple[ModelBatch, ...]:
    batches: list[ModelBatch] = []
    for batch_size in SUPPORTED_BATCH_BOUNDS.values():
        for context in {int(sidecar.context_bounds["minimum"]), int(sidecar.context_bounds["maximum"])}:
            for slots in {int(sidecar.slot_bounds["minimum"]), int(sidecar.slot_bounds["maximum"])}:
                arrays = _contract_inputs(batch_size, context, slots, model.config.feature_count, sidecar.supported_roles)
                batches.append(ModelBatch(
                    tuple(tuple(tuple(tuple(cell) for cell in frame) for frame in sample) for sample in arrays["features"].tolist()),
                    tuple(map(tuple, arrays["role_ids"].tolist())), tuple(map(tuple, arrays["slot_mask"].tolist())),
                    tuple(tuple(tuple(tuple(cell) for cell in frame) for frame in sample) for sample in arrays["channel_validity"].tolist()),
                    tuple(map(tuple, arrays["time_deltas_s"].tolist())), tuple(map(tuple, arrays["time_mask"].tolist())),
                ))
    return tuple(batches)


def _cross_process_outputs(model_path: str | Path, inputs: dict[str, np.ndarray]) -> list[np.ndarray]:
    with tempfile.TemporaryDirectory(prefix="nekovr-parity-") as directory:
        request, response = Path(directory) / "input.json", Path(directory) / "output.json"
        request.write_text(json.dumps({name: value.tolist() for name, value in inputs.items()}, sort_keys=True), encoding="utf-8")
        completed = subprocess.run(
            [sys.executable, "-m", "nekovr_ml.onnx_runner", str(model_path), str(request), str(response)],
            check=False, capture_output=True, text=True, timeout=60,
        )
        if completed.returncode != 0:
            raise ValueError(f"cross-process ONNX parity failed: {completed.stderr.strip()}")
        payload = json.loads(response.read_text(encoding="utf-8"))
        return [np.asarray(payload[name], dtype=np.float32) for name in OUTPUT_NAMES]


def generate_parity_evidence(model: CompactCausalModel, checkpoint_path: str | Path, model_path: str | Path, sidecar_path: str | Path, batches: Sequence[ModelBatch], tolerance: float = 1e-5) -> dict[str, object]:
    sidecar = inspect_export(model_path, sidecar_path)
    boundary_batches = _boundary_batches(model, sidecar)
    all_batches = tuple(batches) + boundary_batches
    report = compare_framework_and_onnx(model, model_path, all_batches, tolerance)
    outputs = [model.forward(batch) for batch in all_batches]
    finite = all(np.isfinite(np.asarray(values)).all() for output in outputs for values in (output.correction_rotation_vectors, output.confidence, output.drift_rate))
    bounded = all(
        np.max(np.abs(np.asarray(output.correction_rotation_vectors))) <= model.config.maximum_correction_radians + 1e-12
        and np.max(np.abs(np.asarray(output.drift_rate))) <= model.config.maximum_drift_rate_radians_per_second + 1e-12
        and np.min(np.asarray(output.confidence)) >= 0.0 and np.max(np.asarray(output.confidence)) <= 1.0 for output in outputs
    )
    masked_zero = all(
        active or (not any(output.correction_rotation_vectors[batch_index][slot]) and output.confidence[batch_index][slot] == 0.0 and output.drift_rate[batch_index][slot] == 0.0)
        for batch, output in zip(all_batches, outputs) for batch_index, mask in enumerate(batch.slot_mask) for slot, active in enumerate(mask)
    )
    graph = onnx.load(model_path)
    role_count = int(next(numpy_helper.to_array(value).shape[0] for value in graph.graph.initializer if value.name == "role_embeddings"))
    valid = _contract_inputs(1, int(sidecar.context_bounds["minimum"]), int(sidecar.slot_bounds["minimum"]), model.config.feature_count, sidecar.supported_roles)
    mutations = {
        "feature_width": valid | {"features": np.zeros((*valid["features"].shape[:-1], model.config.feature_count + 1), dtype=np.float32)},
        "feature_rank": valid | {"features": valid["features"][0]},
        "role_id": valid | {"role_ids": np.full(valid["role_ids"].shape, role_count, dtype=np.int64)},
        "slot_upper_bound": _contract_inputs(1, int(sidecar.context_bounds["minimum"]), int(sidecar.slot_bounds["maximum"]) + 1, model.config.feature_count, sidecar.supported_roles),
        "context_upper_bound": _contract_inputs(1, int(sidecar.context_bounds["maximum"]) + 1, int(sidecar.slot_bounds["minimum"]), model.config.feature_count, sidecar.supported_roles),
    }
    negative_rejections = {}
    for name, values in mutations.items():
        try:
            validate_runtime_inputs(values, sidecar, model.config.feature_count, role_count)
        except ValueError:
            negative_rejections[name] = True
        else:
            negative_rejections[name] = False
    repeat_inputs = batch_to_numpy(boundary_batches[-1])
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    first, second, remote = session.run(list(OUTPUT_NAMES), repeat_inputs), session.run(list(OUTPUT_NAMES), repeat_inputs), _cross_process_outputs(model_path, repeat_inputs)
    deterministic = all(np.array_equal(left, right) and np.array_equal(left, other) for left, right, other in zip(first, second, remote))
    boundary_cases = [{"batch": len(batch.features), "context": len(batch.features[0]), "slots": len(batch.role_ids[0])} for batch in boundary_batches]
    return {
        "format": "nekovr-onnx-parity-v2", "checkpoint_sha256": file_sha256(checkpoint_path), "model_sha256": sidecar.model_sha256,
        **report.to_dict(), "finite_outputs": bool(finite), "bounded_outputs": bool(bounded), "masked_slots_zero": masked_zero,
        "all_declared_outputs": list(OUTPUT_NAMES), "boundary_cases": boundary_cases, "boundary_cases_sha256": canonical_hash(boundary_cases),
        "invalid_input_rejections": negative_rejections, "deterministic_same_process": deterministic, "deterministic_cross_process": deterministic,
        "passed": bool(report.passed and finite and bounded and masked_zero and deterministic and all(negative_rejections.values())),
    }
