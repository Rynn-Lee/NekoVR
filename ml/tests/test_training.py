import json

from nekovr_ml import cli
from nekovr_ml.model import CompactCausalModel, ModelConfig, SequenceSample
from nekovr_ml.training import TrainingExample, train_adapter_heads


def test_adapter_training_reduces_loss_without_changing_backbone():
    model = CompactCausalModel(ModelConfig(feature_count=1, hidden_size=3, temporal_layers=1, max_slots=1), seed=2)
    sequence = SequenceSample((((1.0,),),), (1,), (True,), (((True,),),), (0.02,))
    example = TrainingExample("a", "train", sequence, ((0.05, 0.0, 0.0),), (1.0,), (1.0,))
    frozen_before = {name: getattr(model, name) for name in model.frozen_parameter_names}
    result = train_adapter_heads(model, (example,), epochs=40, learning_rate=0.05, seed=1)
    assert result.epoch_losses[-1] < result.epoch_losses[0]
    assert frozen_before == {name: getattr(model, name) for name in model.frozen_parameter_names}
    assert set(result.updated_parameters) <= set(model.adapter_parameter_names)


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
        "normalization": {"mean": [0.0], "standard_deviation": [1.0]},
        "examples": [{"sample_id": "a", "split": "train", "features": [[[1.0]]], "role_ids": [1], "slot_mask": [True], "channel_validity": [[[True]]], "time_deltas_s": [0.02], "target_correction_rotation_vectors": [[0.05, 0.0, 0.0]], "target_confidence": [1.0], "loss_weights": [1.0]}],
    }))
    output = tmp_path / "run"
    assert cli._run(["train", "--input", str(input_path), "--output-dir", str(output), "--epochs", "2", "--config", str(config_path)]) == 0
    assert (output / "model-checkpoint.json").is_file()
    assert (output / "personalization" / "fixture" / "manifest.json").is_file()
    assert json.loads((output / "run.json").read_text())["dataset_hashes"]["training_input"]
