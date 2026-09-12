import pytest

from nekovr_ml.model import CompactCausalModel, ModelConfig
from nekovr_ml.training import training_example_from_mapping
from nekovr_ml.workflow import build_training_plan, qualify_context_features


def _payload(count=4, split="unassigned"):
    return {
        "format": "nekovr-training-input-v1",
        "preparation_format": "nekovr-canonical-preparation-v1",
        "feature_schema": ["temperature_c"],
        "examples": [
            {
                "sample_id": f"sample-{index}", "split": split, "domain": "synthetic",
                "source_sha256": f"{index + 1:064x}",
                "group": {"source_id": f"source-{index}", "subject_id": f"person-{index}",
                          "session_id": f"session-{index}", "device_cohort": f"device-{index}",
                          "layout": "1", "chipset": "fixture"},
                "features": [[[10.0 + index]], [[11.0 + index]]], "role_ids": [1], "slot_mask": [True],
                "channel_validity": [[[True]], [[True]]], "time_deltas_s": [0.02, 0.02],
                "target_correction_rotation_vectors": [[0.01, 0.0, 0.0]], "target_confidence": [1.0],
                "loss_weights": [1.0], "masks": {}, "quality_decision": {"policy": "INCLUDE"},
            }
            for index in range(count)
        ],
    }


def _config():
    return {
        "splits": {"train": 0.5, "validation": 0.25, "test": 0.25,
                   "group_fields": ["subject_id", "source_id", "session_id", "device_cohort"]},
        "model": {"kernel_size": 2, "temporal_layers": 1},
        "features": {"contextual_missingness_probability": 1.0,
                     "maximum_unseen_cohort_regression": 100.0, "require_ablation_improvement": False},
    }


def test_group_assignment_precedes_windows_and_normalization_membership_is_training_only():
    plan = build_training_plan(_payload(), _config(), 9)
    assert plan.split_audit["phase_order"] == ["group_assignment", "window_extraction", "normalization_application"]
    assert not plan.split_audit["forbidden_overlaps"]
    assert all(value["window"]["assigned_before_extraction"] for value in plan.examples)
    train_parents = sorted(name for name, split in plan.assignments.items() if split == "train")
    assert plan.normalization["training_sample_ids"] == train_parents
    holdout_values = [value for value in _payload()["examples"] if value["sample_id"] not in train_parents]
    expected = sum(slot[0] for value in _payload()["examples"] if value["sample_id"] in train_parents for frame in value["features"] for slot in frame) / (2 * len(train_parents))
    assert plan.normalization["mean"] == pytest.approx([expected])
    assert holdout_values


def test_global_workflow_rejects_caller_splits_and_empty_holdouts():
    with pytest.raises(ValueError, match="caller-supplied split"):
        build_training_plan(_payload(split="train"), _config(), 9)
    with pytest.raises(ValueError, match="empty holdouts"):
        build_training_plan(_payload(count=2), _config(), 9)


def test_context_feature_qualification_executes_ablation_missingness_and_unseen_checks():
    plan = build_training_plan(_payload(), _config(), 9)
    examples = tuple(training_example_from_mapping(value) for value in plan.examples)
    model = CompactCausalModel(ModelConfig(feature_count=1, hidden_size=2, temporal_layers=1, kernel_size=2, role_count=4, max_slots=1), seed=1)
    report = qualify_context_features(model, examples, plan.feature_specifications, plan.normalization, _config()["features"])
    assert report["missingness_executed"] == ["temperature_c"]
    assert "temperature_c" in report["ablation_improvement"]
    assert {value["cohort_kind"] for value in report["unseen_checks"]} == {"device", "session"}
    assert report["decisions"][0]["feature"] == "temperature_c"
