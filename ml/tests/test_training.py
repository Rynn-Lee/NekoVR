import json

from nekovr_ml import cli
from nekovr_ml.model import CompactCausalModel, ModelConfig, SequenceSample
from nekovr_ml.losses import Domain, SupervisionExample, TargetProvenance, build_loss_mask
from nekovr_ml.training import TrainingExample, train_adapter_heads, train_global_model, training_example_from_mapping


def test_adapter_training_reduces_loss_without_changing_backbone():
    model = CompactCausalModel(ModelConfig(feature_count=1, hidden_size=3, temporal_layers=1, max_slots=1), seed=2)
    sequence = SequenceSample((((1.0,),),), (1,), (True,), (((True,),),), (0.02,))
    example = TrainingExample("a", "train", sequence, ((0.05, 0.0, 0.0),), (1.0,), (1.0,))
    frozen_before = {name: getattr(model, name) for name in model.frozen_parameter_names}
    result = train_adapter_heads(model, (example,), epochs=40, learning_rate=0.05, seed=1)
    assert result.epoch_losses[-1] < result.epoch_losses[0]
    assert frozen_before == {name: getattr(model, name) for name in model.frozen_parameter_names}
    assert set(result.updated_parameters) <= set(model.adapter_parameter_names)


def test_global_training_consumes_declared_losses_and_updates_encoder_and_heads():
    model = CompactCausalModel(ModelConfig(feature_count=1, hidden_size=3, temporal_layers=1, max_slots=1), seed=2)
    sequence = SequenceSample((((1.0,),), ((0.8,),)), (1,), (True,), (((True,),), ((True,),)), (0.02, 0.02))
    mask = build_loss_mask(SupervisionExample(Domain.SYNTHETIC, 7, TargetProvenance.SYNTHETIC))
    example = TrainingExample("a", "train", sequence, ((0.05, 0.0, 0.0),), (1.0,), (1.0,), mask, {"domain": "synthetic"})
    before = model.state_dict()
    result = train_global_model(model, (example,), epochs=2, learning_rate=0.01, seed=1)
    assert set(result.updated_parameters) == set(model.frozen_parameter_names + model.adapter_parameter_names)
    assert any(before[name] != getattr(model, name) for name in model.frozen_parameter_names)
    assert result.loss_summary["active_dense_synthetic"] > 0
    assert result.loss_summary["active_temporal"] > 0
    assert result.loss_summary["active_self_supervised"] > 0
    assert result.loss_summary["active_domain"] > 0


def test_global_training_applies_real_yaw_axis_provenance_and_quality_masks():
    model = CompactCausalModel(ModelConfig(feature_count=1, hidden_size=2, temporal_layers=1, max_slots=1), seed=4)
    value = {
        "sample_id": "real", "split": "train", "domain": "real", "features": [[[1.0]], [[1.1]]],
        "role_ids": [1], "slot_mask": [True], "channel_validity": [[[True]], [[True]]], "time_deltas_s": [0.02, 0.02],
        "target_correction_rotation_vectors": [[1.0, 2.0, 0.1]], "target_confidence": [1.0], "loss_weights": [1.0],
        "masks": {"axis_mask": 1, "quality_flags": 1}, "quality_decision": {"policy": "DOWNWEIGHT"},
    }
    example = training_example_from_mapping(value)
    result = train_global_model(model, (example,), epochs=1, learning_rate=0.01, seed=2)
    assert result.loss_summary["active_sparse_real_reset"] > 0
    assert result.loss_summary["active_dense_synthetic"] == 0
    assert example.loss_mask.axes[0] == example.loss_mask.axes[1] == 0
    assert example.loss_mask.axes[2] > 0


def test_train_cli_writes_checkpoint_personalization_and_run_manifest(tmp_path):
    config = {
        "schema_version": 1, "seed": 2, "deterministic": True, "sample_rate_hz": 50,
        "layouts": [1], "max_slots": 1,
        "model": {"feature_count": 1, "hidden_size": 3, "temporal_layers": 1, "kernel_size": 2, "role_count": 4, "output_axes": 3, "maximum_correction_degrees": 30, "maximum_drift_rate_degrees_per_second": 5},
    }
    config_path = tmp_path / "config.json"
    config_path.write_text(json.dumps(config))
    input_path = tmp_path / "input.json"
    input_path.write_text(json.dumps({
        "format": "nekovr-training-input-v1", "model_id": "fixture", "supported_roles": ["body:hip"], "supported_layouts": [1],
        "preparation_format": "nekovr-canonical-preparation-v1", "feature_schema": ["orientation"],
        "examples": [
            {"sample_id": name, "split": "unassigned", "domain": "synthetic", "source_sha256": char * 64,
             "group": {"source_id": name, "subject_id": name, "session_id": name, "device_cohort": name, "layout": "1", "chipset": "fixture"},
             "features": [[[value]], [[value + 0.1]]], "role_ids": [1], "slot_mask": [True],
             "channel_validity": [[[True]], [[True]]], "time_deltas_s": [0.02, 0.02],
             "target_correction_rotation_vectors": [[0.05, 0.0, 0.0]], "target_confidence": [1.0],
             "loss_weights": [1.0], "masks": {}, "quality_decision": {"policy": "INCLUDE"}}
            for name, char, value in (("a", "a", 1.0), ("b", "b", 2.0), ("c", "c", 3.0))
        ],
    }))
    output = tmp_path / "run"
    assert cli._run(["train", "--input", str(input_path), "--output-dir", str(output), "--epochs", "2", "--config", str(config_path)]) == 0
    assert (output / "model-checkpoint.json").is_file()
    assert json.loads((output / "split-audit.json").read_text())["phase_order"][0] == "group_assignment"
    assert json.loads((output / "normalization.json").read_text())["scope"] == "training-groups-only"
    assert (output / "feature-qualification.json").is_file()
    assert (output / "personalization" / "fixture" / "manifest.json").is_file()
    run = json.loads((output / "run.json").read_text())
    assert run["dataset_hashes"]["training_input"]
    assert run["lineage"]["groups"] and run["lineage"]["windows"]
    assert run["lineage"]["loss_summary"] and "feature_decisions" in run["lineage"]
    assert run["lineage"]["checkpoint"]["sha256"] == run["artifact_hashes"]["checkpoint"]
