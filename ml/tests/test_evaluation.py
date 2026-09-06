import math
import json

import pytest

from nekovr_ml import cli
from nekovr_ml.evaluation import EvaluationRecord, evaluate
from nekovr_ml.math3d import axis_angle_to_quat


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
    config.write_text(json.dumps({"schema_version": 1, "seed": 1, "deterministic": True, "sample_rate_hz": 50, "layouts": [6]}))
    record = _record(0)
    evaluation_input = tmp_path / "evaluation.json"
    evaluation_input.write_text(json.dumps({"format": "nekovr-evaluation-input-v1", "records": [record.__dict__], "split_assignments": {"session-a": "test"}, "normalization": {}}))
    output = tmp_path / "output"
    assert cli._run(["evaluate", "--input", str(evaluation_input), "--output-dir", str(output), "--config", str(config)]) == 0
    assert json.loads((output / "metrics.json").read_text())["cohorts"]["activity"]["STANDING"]["samples"] == 1
    assert json.loads((output / "run.json").read_text())["artifact_hashes"]["metrics"]
