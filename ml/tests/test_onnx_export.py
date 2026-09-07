import json
import math

import numpy as np
import onnx
import onnxruntime as ort
import pytest

from nekovr_ml import cli
from nekovr_ml.model import CompactCausalModel, ModelBatch, ModelConfig, SequenceSample, collate_variable_layout
from nekovr_ml.model_metadata import load_sidecar
from nekovr_ml.onnx_export import INPUT_NAMES, OUTPUT_NAMES, batch_to_numpy, export_model
from nekovr_ml.onnx_validation import compare_framework_and_onnx, inspect_export
from nekovr_ml.training import write_model_checkpoint


def _model():
    return CompactCausalModel(ModelConfig(feature_count=4, hidden_size=6, temporal_layers=2, max_slots=10), seed=83)


def _sample(layout, time=5, permutation=None, masked_slot=None, invalid_latest_slot=None):
    permutation = tuple(range(layout)) if permutation is None else tuple(permutation)
    roles = tuple(index + 1 for index in permutation)
    slot_mask = tuple(index != masked_slot for index in permutation)
    features = []
    validity = []
    for frame in range(time):
        feature_frame = []
        validity_frame = []
        for original_slot in permutation:
            values = tuple(math.sin((frame + 1) * (original_slot + 1) * (feature + 1) * 0.07) for feature in range(4))
            valid = [True, True, frame % 2 == 0, True]
            if frame == time - 1 and original_slot == invalid_latest_slot:
                values = (float("nan"), 1.0, 2.0, 3.0)
                valid = [False, False, False, False]
            feature_frame.append(values)
            validity_frame.append(tuple(valid))
        features.append(tuple(feature_frame))
        validity.append(tuple(validity_frame))
    return SequenceSample(tuple(features), roles, slot_mask, tuple(validity), (0.02,) * time)


def _export(tmp_path, model):
    return export_model(
        model, tmp_path / "small.onnx", model_id="nekovr-small-test", model_version="1.0.0",
        feature_schema={"version": 1, "features": ["orientation", "gyro", "temperature", "packet_age"]},
        normalization={"mean": [0.0] * 4, "standard_deviation": [1.0] * 4, "scope": "training-only"},
        supported_roles=range(1, 17), minimum_slots=5, minimum_context=2, maximum_context=60,
        provenance={"seed": 83, "config_sha256": "1" * 64, "source_commit": "fixture", "dataset_hashes": {"synthetic": "2" * 64}},
        validation_metrics={"validation_loss": 0.1}, performance_tier="small",
    )


def test_versioned_sidecar_records_and_verifies_complete_contract(tmp_path):
    model_path, sidecar_path = _export(tmp_path, _model())
    sidecar = load_sidecar(sidecar_path, model_path)
    assert sidecar.format == "nekovr-model-sidecar-v1"
    assert sidecar.schema_version == 1
    assert sidecar.feature_schema_sha256 and sidecar.model_sha256
    assert sidecar.slot_bounds == {"minimum": 5, "maximum": 10}
    assert sidecar.context_bounds == {"minimum": 2, "maximum": 60}
    assert tuple(value.name for value in sidecar.inputs) == INPUT_NAMES
    assert tuple(value.name for value in sidecar.outputs) == OUTPUT_NAMES
    assert sidecar.normalization["scope"] == "training-only"
    assert sidecar.output_convention["masked_slot_output"] == "zero"
    assert sidecar.provenance["dataset_hashes"] and sidecar.validation_metrics
    assert sidecar.opset == 18 and sidecar.model_size_bytes == model_path.stat().st_size
    model_path.write_bytes(model_path.read_bytes() + b"tampered")
    with pytest.raises(ValueError, match="size or SHA-256"):
        load_sidecar(sidecar_path, model_path)


def test_export_uses_supported_ops_and_dynamic_mask_contract(tmp_path):
    model_path, sidecar_path = _export(tmp_path, _model())
    inspect_export(model_path, sidecar_path)
    graph = onnx.load(model_path)
    features = next(value for value in graph.graph.input if value.name == "features")
    dimensions = features.type.tensor_type.shape.dim
    assert [dimensions[index].dim_param for index in range(3)] == ["batch", "time", "slots"]
    assert any(node.op_type == "Conv" and node.attribute for node in graph.graph.node)
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    short = collate_variable_layout([_sample(5, time=2)], 4, 10)
    long = collate_variable_layout([_sample(10, time=9)], 4, 10)
    assert session.run(None, batch_to_numpy(short))[0].shape == (1, 10, 3)
    assert session.run(None, batch_to_numpy(long))[0].shape == (1, 10, 3)


def test_export_and_validate_cli_use_checkpoint_and_sidecar(tmp_path, capsys):
    checkpoint = tmp_path / "checkpoint.json"
    metadata = tmp_path / "metadata.json"
    model_path = tmp_path / "cli.onnx"
    write_model_checkpoint(_model(), checkpoint)
    metadata.write_text(json.dumps({
        "model_id": "nekovr-small-cli", "model_version": "1.0.0",
        "feature_schema": {"version": 1, "features": ["a", "b", "c", "d"]},
        "normalization": {"mean": [0.0] * 4, "standard_deviation": [1.0] * 4},
        "supported_roles": list(range(1, 17)), "slot_bounds": {"minimum": 5, "maximum": 10},
        "context_bounds": {"minimum": 2, "maximum": 60},
        "provenance": {"seed": 83, "config_sha256": "1" * 64, "source_commit": "fixture", "dataset_hashes": {}},
        "validation_metrics": {"loss": 0.1}, "performance_tier": "small",
    }), encoding="utf-8")
    assert cli._run(["export-onnx", "--checkpoint", str(checkpoint), "--metadata", str(metadata), "--output", str(model_path)]) == 0
    exported = json.loads(capsys.readouterr().out)
    assert exported["model"] == str(model_path)
    assert cli._run(["validate-onnx", "--model", str(model_path)]) == 0
    validated = json.loads(capsys.readouterr().out)
    assert validated["provider"] == "CPUExecutionProvider"


def test_framework_onnxruntime_parity_covers_layouts_permutations_masks_and_discontinuities(tmp_path):
    model = _model()
    model_path, sidecar_path = _export(tmp_path, model)
    inspect_export(model_path, sidecar_path)
    batches = []
    for layout in (5, 6, 8, 10):
        batches.append(collate_variable_layout([_sample(layout, time=layout - 2, masked_slot=layout - 1, invalid_latest_slot=layout - 2)], 4, 10))
    original = collate_variable_layout([_sample(10, permutation=range(10))], 4, 10)
    reversed_slots = collate_variable_layout([_sample(10, permutation=reversed(range(10)))], 4, 10)
    batches.extend((original, reversed_slots))
    discontinuous = batches[1]
    time_mask = list(discontinuous.time_mask[0])
    time_mask[-2] = False
    discontinuous = ModelBatch(
        discontinuous.features, discontinuous.role_ids, discontinuous.slot_mask,
        discontinuous.channel_validity, discontinuous.time_deltas_s, (tuple(time_mask),),
    )
    batches.append(discontinuous)
    report = compare_framework_and_onnx(model, model_path, batches, tolerance=1e-5)
    assert report.passed
    assert report.cases == 7
    assert report.maximum_absolute_error < 1e-5
    assert report.provider == "CPUExecutionProvider"

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    original_outputs = session.run(None, batch_to_numpy(original))
    reversed_outputs = session.run(None, batch_to_numpy(reversed_slots))
    for original_output, reversed_output in zip(original_outputs, reversed_outputs):
        assert original_output[0] == pytest.approx(reversed_output[0][::-1], abs=1e-6)
    for batch in batches[:4]:
        correction, confidence, drift = session.run(None, batch_to_numpy(batch))
        invalid = ~batch_to_numpy(batch)["slot_mask"]
        assert np.all(correction[invalid] == 0.0)
        assert np.all(confidence[invalid] == 0.0)
        assert np.all(drift[invalid] == 0.0)
    for layout, batch in zip((5, 6, 8, 10), batches[:4]):
        correction, confidence, drift = session.run(None, batch_to_numpy(batch))
        invalid_latest_slot = layout - 2
        assert np.all(correction[0, invalid_latest_slot] == 0.0)
        assert confidence[0, invalid_latest_slot] == 0.0
        assert drift[0, invalid_latest_slot] == 0.0
