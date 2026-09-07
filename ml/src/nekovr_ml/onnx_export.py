from __future__ import annotations

from dataclasses import asdict
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

from .model import CompactCausalModel, ModelBatch
from .model_metadata import ModelSidecar, TensorMetadata, write_sidecar
from .provenance import canonical_hash, file_sha256


OPSET_VERSION = 18
INPUT_NAMES = ("features", "role_ids", "slot_mask", "channel_validity", "time_deltas_s", "time_mask")
OUTPUT_NAMES = ("correction_rotation_vectors", "confidence", "drift_rate")


def _array(name: str, values: Any, dtype: Any = np.float32) -> onnx.TensorProto:
    return numpy_helper.from_array(np.asarray(values, dtype=dtype), name=name)


def _constant(name: str, values: Any, dtype: Any = np.int64) -> onnx.NodeProto:
    return helper.make_node("Constant", [], [name], value=_array(name + "_value", values, dtype))


def _build_graph(model: CompactCausalModel) -> onnx.ModelProto:
    cfg = model.config
    inputs = [
        helper.make_tensor_value_info("features", TensorProto.FLOAT, ["batch", "time", "slots", cfg.feature_count]),
        helper.make_tensor_value_info("role_ids", TensorProto.INT64, ["batch", "slots"]),
        helper.make_tensor_value_info("slot_mask", TensorProto.BOOL, ["batch", "slots"]),
        helper.make_tensor_value_info("channel_validity", TensorProto.BOOL, ["batch", "time", "slots", cfg.feature_count]),
        helper.make_tensor_value_info("time_deltas_s", TensorProto.FLOAT, ["batch", "time"]),
        helper.make_tensor_value_info("time_mask", TensorProto.BOOL, ["batch", "time"]),
    ]
    outputs = [
        helper.make_tensor_value_info("correction_rotation_vectors", TensorProto.FLOAT, ["batch", "slots", cfg.output_axes]),
        helper.make_tensor_value_info("confidence", TensorProto.FLOAT, ["batch", "slots"]),
        helper.make_tensor_value_info("drift_rate", TensorProto.FLOAT, ["batch", "slots"]),
    ]
    initializers = [
        _array("input_weights", np.asarray(model.input_weights).T), _array("input_bias", model.input_bias),
        _array("time_weights", model.time_weights), _array("role_embeddings", model.role_embeddings),
        _array("correction_weights", np.asarray(model.correction_head).T), _array("correction_bias", model.correction_bias),
        _array("confidence_weights", np.asarray(model.confidence_head)[:, None]), _array("confidence_bias", [model.confidence_bias]),
        _array("drift_weights", np.asarray(model.drift_head)[:, None]), _array("drift_bias", [model.drift_bias]),
        _array("maximum_correction", [cfg.maximum_correction_radians]),
        _array("maximum_drift_rate", [cfg.maximum_drift_rate_radians_per_second]),
        _array("zero_float", [0.0]), _array("one_float", [1.0]),
    ]
    nodes = [
        helper.make_node("IsNaN", ["features"], ["features_nan"]),
        helper.make_node("IsInf", ["features"], ["features_inf"]),
        helper.make_node("Or", ["features_nan", "features_inf"], ["features_nonfinite"]),
        helper.make_node("Not", ["features_nonfinite"], ["features_finite"]),
        helper.make_node("And", ["channel_validity", "features_finite"], ["effective_channel_validity"]),
        helper.make_node("Where", ["effective_channel_validity", "features", "zero_float"], ["masked_features"]),
        helper.make_node("MatMul", ["masked_features", "input_weights"], ["projected_features"]),
        helper.make_node("Gather", ["role_embeddings", "role_ids"], ["gathered_roles"], axis=0),
        _constant("axis_one", [1]),
        _constant("axes_time_slot", [2, 3]),
        _constant("axes_slot_hidden", [1, 3]),
        helper.make_node("Unsqueeze", ["gathered_roles", "axis_one"], ["role_context"]),
        helper.make_node("Add", ["time_deltas_s", "one_float"], ["time_plus_one"]),
        helper.make_node("Log", ["time_plus_one"], ["log_time_delta"]),
        helper.make_node("Unsqueeze", ["log_time_delta", "axes_time_slot"], ["time_context_scalar"]),
        helper.make_node("Mul", ["time_context_scalar", "time_weights"], ["time_context"]),
        helper.make_node("Add", ["projected_features", "input_bias"], ["projected_bias"]),
        helper.make_node("Add", ["projected_bias", "role_context"], ["projected_role"]),
        helper.make_node("Add", ["projected_role", "time_context"], ["encoder_preactivation"]),
        helper.make_node("Tanh", ["encoder_preactivation"], ["encoder_unmasked"]),
        helper.make_node("Unsqueeze", ["time_mask", "axes_time_slot"], ["time_mask_4d"]),
        helper.make_node("Unsqueeze", ["slot_mask", "axes_slot_hidden"], ["slot_mask_4d"]),
        helper.make_node("And", ["time_mask_4d", "slot_mask_4d"], ["state_mask_bool"]),
        helper.make_node("Cast", ["state_mask_bool"], ["state_mask"], to=TensorProto.FLOAT),
        helper.make_node("Mul", ["encoder_unmasked", "state_mask"], ["encoded"]),
        helper.make_node("Shape", ["features"], ["features_shape"]),
        _constant("index_batch", [0]), _constant("index_time", [1]), _constant("index_slots", [2]),
        helper.make_node("Gather", ["features_shape", "index_batch"], ["batch_dimension"], axis=0),
        helper.make_node("Gather", ["features_shape", "index_time"], ["time_dimension"], axis=0),
        helper.make_node("Gather", ["features_shape", "index_slots"], ["slot_dimension"], axis=0),
        helper.make_node("Mul", ["batch_dimension", "slot_dimension"], ["batch_slots"]),
        _constant("hidden_dimension", [cfg.hidden_size]), _constant("one_dimension", [1]),
        helper.make_node("Concat", ["batch_slots", "hidden_dimension", "time_dimension"], ["temporal_shape"], axis=0),
        helper.make_node("Concat", ["batch_slots", "one_dimension", "time_dimension"], ["temporal_mask_shape"], axis=0),
        helper.make_node("Concat", ["batch_dimension", "slot_dimension", "hidden_dimension", "time_dimension"], ["state_4d_shape"], axis=0),
        helper.make_node("Transpose", ["encoded"], ["temporal_4d"], perm=[0, 2, 3, 1]),
        helper.make_node("Reshape", ["temporal_4d", "temporal_shape"], ["temporal_0"]),
        helper.make_node("Transpose", ["state_mask"], ["state_mask_transposed"], perm=[0, 2, 3, 1]),
        helper.make_node("Reshape", ["state_mask_transposed", "temporal_mask_shape"], ["temporal_mask"]),
    ]
    temporal = "temporal_0"
    for layer in range(cfg.temporal_layers):
        dilation = 2**layer
        kernel_name = f"depthwise_kernel_{layer}"
        pointwise_name = f"pointwise_kernel_{layer}"
        depth = f"depthwise_{layer}"
        point = f"pointwise_{layer}"
        mixed = f"temporal_mixed_{layer}"
        output = f"temporal_{layer + 1}"
        kernels = np.asarray(model.depthwise_kernels[layer], dtype=np.float32)[:, ::-1, None].transpose(0, 2, 1)
        pointwise = np.asarray(model.pointwise_weights[layer], dtype=np.float32)[:, :, None]
        initializers.extend((_array(kernel_name, kernels), _array(pointwise_name, pointwise)))
        nodes.extend((
            helper.make_node("Conv", [temporal, kernel_name], [depth], dilations=[dilation], group=cfg.hidden_size, pads=[dilation * (cfg.kernel_size - 1), 0], strides=[1]),
            helper.make_node("Conv", [depth, pointwise_name], [point], pads=[0, 0], strides=[1]),
            helper.make_node("Add", [temporal, point], [mixed]),
            helper.make_node("Tanh", [mixed], [output + "_unmasked"]),
            helper.make_node("Mul", [output + "_unmasked", "temporal_mask"], [output]),
        ))
        temporal = output
    nodes.extend((
        helper.make_node("Reshape", [temporal, "state_4d_shape"], ["temporal_4d_final"]),
        helper.make_node("Transpose", ["temporal_4d_final"], ["encoded_final"], perm=[0, 3, 1, 2]),
        _constant("last_index", [-1]),
        helper.make_node("Gather", ["encoded_final", "last_index"], ["last_state_4d"], axis=1),
        helper.make_node("Squeeze", ["last_state_4d", "axis_one"], ["last_state"]),
        helper.make_node("Gather", ["effective_channel_validity", "last_index"], ["latest_validity_4d"], axis=1),
        helper.make_node("Squeeze", ["latest_validity_4d", "axis_one"], ["latest_validity"]),
        helper.make_node("Cast", ["latest_validity"], ["latest_validity_float"], to=TensorProto.FLOAT),
        _constant("axis_features", [-1]),
        helper.make_node("ReduceMax", ["latest_validity_float", "axis_features"], ["latest_any_float"], keepdims=0),
        helper.make_node("Greater", ["latest_any_float", "zero_float"], ["latest_any"]),
        helper.make_node("And", ["slot_mask", "latest_any"], ["output_mask_bool"]),
        helper.make_node("Cast", ["output_mask_bool"], ["output_mask"], to=TensorProto.FLOAT),
        _constant("axis_hidden", [2]),
        helper.make_node("Unsqueeze", ["output_mask", "axis_hidden"], ["output_mask_3d"]),
        helper.make_node("Mul", ["last_state", "output_mask_3d"], ["masked_last_state"]),
        helper.make_node("ReduceSum", ["masked_last_state", "axis_one"], ["global_sum"], keepdims=0),
        helper.make_node("ReduceSum", ["output_mask_3d", "axis_one"], ["valid_slot_count"], keepdims=0),
        helper.make_node("Max", ["valid_slot_count", "one_float"], ["safe_slot_count"]),
        helper.make_node("Div", ["global_sum", "safe_slot_count"], ["global_context_2d"]),
        helper.make_node("Unsqueeze", ["global_context_2d", "axis_one"], ["global_context_3d"]),
        helper.make_node("Shape", ["last_state"], ["last_state_shape"]),
        helper.make_node("Expand", ["global_context_3d", "last_state_shape"], ["global_context"]),
        helper.make_node("Concat", ["last_state", "global_context"], ["head_input"], axis=2),
        helper.make_node("MatMul", ["head_input", "correction_weights"], ["correction_linear"]),
        helper.make_node("Add", ["correction_linear", "correction_bias"], ["correction_biased"]),
        helper.make_node("Tanh", ["correction_biased"], ["correction_unit"]),
        helper.make_node("Mul", ["correction_unit", "maximum_correction"], ["correction_bounded"]),
        helper.make_node("Mul", ["correction_bounded", "output_mask_3d"], ["correction_rotation_vectors"]),
        helper.make_node("MatMul", ["head_input", "confidence_weights"], ["confidence_linear"]),
        helper.make_node("Add", ["confidence_linear", "confidence_bias"], ["confidence_biased"]),
        helper.make_node("Sigmoid", ["confidence_biased"], ["confidence_3d"]),
        helper.make_node("Squeeze", ["confidence_3d", "axis_hidden"], ["confidence_unmasked"]),
        helper.make_node("Mul", ["confidence_unmasked", "output_mask"], ["confidence"]),
        helper.make_node("MatMul", ["head_input", "drift_weights"], ["drift_linear"]),
        helper.make_node("Add", ["drift_linear", "drift_bias"], ["drift_biased"]),
        helper.make_node("Tanh", ["drift_biased"], ["drift_unit_3d"]),
        helper.make_node("Mul", ["drift_unit_3d", "maximum_drift_rate"], ["drift_bounded_3d"]),
        helper.make_node("Squeeze", ["drift_bounded_3d", "axis_hidden"], ["drift_unmasked"]),
        helper.make_node("Mul", ["drift_unmasked", "output_mask"], ["drift_rate"]),
    ))
    graph = helper.make_graph(nodes, "nekovr_compact_causal_model", inputs, outputs, initializer=initializers)
    exported = helper.make_model(graph, producer_name="nekovr-ml", opset_imports=[helper.make_opsetid("", OPSET_VERSION)])
    exported.ir_version = 10
    onnx.checker.check_model(exported)
    return exported


def batch_to_numpy(batch: ModelBatch) -> dict[str, np.ndarray]:
    return {
        "features": np.asarray(batch.features, dtype=np.float32),
        "role_ids": np.asarray(batch.role_ids, dtype=np.int64),
        "slot_mask": np.asarray(batch.slot_mask, dtype=np.bool_),
        "channel_validity": np.asarray(batch.channel_validity, dtype=np.bool_),
        "time_deltas_s": np.asarray(batch.time_deltas_s, dtype=np.float32),
        "time_mask": np.asarray(batch.time_mask, dtype=np.bool_),
    }


def export_model(
    model: CompactCausalModel,
    output: str | Path,
    *,
    model_id: str,
    model_version: str,
    feature_schema: Mapping[str, Any],
    normalization: Mapping[str, Any],
    supported_roles: Sequence[int],
    minimum_slots: int,
    minimum_context: int,
    maximum_context: int,
    provenance: Mapping[str, Any],
    validation_metrics: Mapping[str, Any],
    performance_tier: str = "small",
) -> tuple[Path, Path]:
    if minimum_slots <= 0 or minimum_slots > model.config.max_slots or minimum_context <= 0 or minimum_context > maximum_context:
        raise ValueError("invalid model slot/context bounds")
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    onnx.save_model(_build_graph(model), temporary)
    temporary.replace(target)
    inputs = (
        TensorMetadata("features", "float32", ("batch", "time", "slots", model.config.feature_count), "normalized causal feature history"),
        TensorMetadata("role_ids", "int64", ("batch", "slots"), "canonical body-role IDs"),
        TensorMetadata("slot_mask", "bool", ("batch", "slots"), "mapped tracker slots"),
        TensorMetadata("channel_validity", "bool", ("batch", "time", "slots", model.config.feature_count), "per-channel validity"),
        TensorMetadata("time_deltas_s", "float32", ("batch", "time"), "monotonic elapsed seconds"),
        TensorMetadata("time_mask", "bool", ("batch", "time"), "valid causal frames; rightmost frame must be valid"),
    )
    outputs = (
        TensorMetadata("correction_rotation_vectors", "float32", ("batch", "slots", model.config.output_axes), "bounded world-frame rotation vector in radians"),
        TensorMetadata("confidence", "float32", ("batch", "slots"), "per-slot confidence in [0,1]"),
        TensorMetadata("drift_rate", "float32", ("batch", "slots"), "bounded yaw drift-rate estimate in radians/second"),
    )
    sidecar = ModelSidecar(
        format="nekovr-model-sidecar-v1", schema_version=1, model_id=model_id, model_version=model_version,
        feature_schema_sha256=canonical_hash(feature_schema), inputs=inputs, outputs=outputs,
        normalization=dict(normalization), supported_roles=tuple(sorted(set(int(role) for role in supported_roles))),
        slot_bounds={"minimum": minimum_slots, "maximum": model.config.max_slots},
        context_bounds={"minimum": minimum_context, "maximum": maximum_context},
        output_convention={
            "correction": "q_corrected = rotation_vector_world * q_pre_ai", "quaternion_order": "xyzw",
            "correction_axes": "world_xyz", "maximum_correction_radians": model.config.maximum_correction_radians,
            "maximum_drift_rate_radians_per_second": model.config.maximum_drift_rate_radians_per_second,
            "masked_slot_output": "zero", "time_padding": "left",
        },
        provenance=dict(provenance), validation_metrics=dict(validation_metrics), opset=OPSET_VERSION,
        performance_tier=performance_tier, model_size_bytes=target.stat().st_size, model_sha256=file_sha256(target),
    )
    sidecar_path = target.with_suffix(target.suffix + ".json")
    write_sidecar(sidecar, sidecar_path)
    return target, sidecar_path
