import math
import json

import pytest

from nekovr_ml import cli
from nekovr_ml.evaluation import EvaluationRecord, evaluate
from nekovr_ml.math3d import axis_angle_to_quat
from nekovr_ml.model import CompactCausalModel, ModelConfig
from nekovr_ml.training import write_model_checkpoint


def _record(index, activity="STANDING", confidence=0.9, reset=None, correction=0.1):
    observed = axis_angle_to_quat((0.0, 0.1, 0.0))
    return EvaluationRecord(
        sequence_id="session-a",
        timestamp_s=index * 0.02,
        layout="6",
        domain="real",
        person_id="person-a",
        chipset="BMI160",
        transport="WIFI",
        activity=activity,
        activity_confidence=confidence,
        drift_severity="LOW",
        observed_xyzw=observed,
        target_xyzw=(0.0, 0.0, 0.0, 1.0),
        predicted_correction_rotation_vector=(0.0, -correction, 0.0),
        confidence=0.9,
        clean_motion=index == 0,
        reset_kind=reset,
    )


def test_evaluation_reports_safety_temporal_reset_and_cohort_metrics():
    report = evaluate([_record(0), _record(1, reset="YAW"), _record(2)])
    assert report.overall.angular_error_after_radians == pytest.approx(0.0, abs=1e-7)
    assert report.overall.angular_error_before_radians == pytest.approx(0.1)
    assert report.overall.yaw_resets_per_hour > 0
    assert report.overall.time_to_first_reset_seconds == pytest.approx(0.02)
    assert report.overall.longest_valid_no_reset_seconds == pytest.approx(0.02)
    assert report.cohorts["layout"]["6"].samples == 3
    assert report.cohorts["chipset"]["BMI160"].samples == 3


def test_unknown_and_low_confidence_activity_are_not_claimed():
    report = evaluate([_record(0, "UNKNOWN"), _record(1, "LYING", 0.2)])
    assert report.cohorts["activity"] == {}


def test_clean_false_correction_and_discontinuity_are_visible():
    report = evaluate([_record(0, correction=0.1), _record(1, correction=-0.1)])
    assert report.overall.clean_false_correction_rate == 1.0
    assert report.overall.discontinuities > 0


def test_evaluate_cli_writes_metrics_and_provenance(tmp_path):
    config = tmp_path / "config.json"
    config.write_text(json.dumps({
        "schema_version": 1, "seed": 1, "deterministic": True, "sample_rate_hz": 50, "layouts": [1], "max_slots": 1,
        "model": {"feature_count": 4, "hidden_size": 3, "temporal_layers": 1, "kernel_size": 2, "role_count": 4,
                  "output_axes": 3, "maximum_correction_degrees": 30, "maximum_drift_rate_degrees_per_second": 5},
    }))
    evaluation_input = tmp_path / "evaluation.json"
    evaluation_input.write_text(json.dumps({
        "format": "nekovr-training-input-v1", "preparation_format": "nekovr-canonical-preparation-v1",
        "feature_schema": ["orientation_x", "orientation_y", "orientation_z", "orientation_w"],
        "examples": [
            {"sample_id": name, "split": "unassigned", "domain": "synthetic", "source_sha256": char * 64,
             "group": {"source_id": name, "subject_id": name, "session_id": name, "device_cohort": name, "layout": "1", "chipset": "fixture"},
             "features": [[[0.0, 0.0, 0.0, 1.0]]], "role_ids": [1], "slot_mask": [True],
             "channel_validity": [[[True, True, True, True]]], "time_deltas_s": [0.02],
             "target_correction_rotation_vectors": [[0.0, 0.0, 0.0]], "target_confidence": [1.0],
             "loss_weights": [1.0], "masks": {}, "quality_decision": {"policy": "INCLUDE"},
             "activity": "STANDING", "activity_confidence": 0.9}
            for name, char in (("a", "a"), ("b", "b"), ("c", "c"))
        ],
    }))
    checkpoint = tmp_path / "checkpoint.json"
    write_model_checkpoint(CompactCausalModel(ModelConfig(feature_count=4, hidden_size=3, temporal_layers=1, kernel_size=2, role_count=4, max_slots=1), seed=3), checkpoint)
    output = tmp_path / "output"
    assert cli._run(["evaluate", "--input", str(evaluation_input), "--checkpoint", str(checkpoint), "--output-dir", str(output), "--config", str(config)]) == 0
    payload = json.loads((output / "metrics.json").read_text())
    assert payload["format"] == "nekovr-checkpoint-evaluation-v1"
    assert payload["candidate"]["cohorts"]["activity"]["STANDING"]["samples"] >= 1
    assert set(payload["baselines"]) == {"identity", "legacy-yaw"}
    assert payload["checkpoint_sha256"]
    assert json.loads((output / "run.json").read_text())["artifact_hashes"]["metrics"]
