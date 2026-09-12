from __future__ import annotations

import json
import math
from pathlib import Path

from nekovr_ml import cli
from nekovr_ml.amass import CanonicalMotion, MotionFrame
from nekovr_ml.layouts import LAYOUT_ROLES
from nekovr_ml.personalization import execute_portable_personalization_probe
from nekovr_ml.provenance import file_sha256
from nekovr_ml.sessions import PreparedSession, SessionFrame, SessionResetWindow, SessionTrackerFrame


FIXTURE = Path(__file__).parents[1] / "fixtures" / "pipeline-e2e-v1.json"


def _motion() -> CanonicalMotion:
    roles = LAYOUT_ROLES[5]
    frames = tuple(
        MotionFrame(
            index * 0.02,
            {role: (0.0, math.sin(index * 0.01), 0.0, math.cos(index * 0.01)) for role in roles},
            {role: (role_index * 0.1, index * 0.01, 0.0) for role_index, role in enumerate(roles)},
        )
        for index in range(2)
    )
    return CanonicalMotion(frames, 50, "a" * 64, "b" * 64, "AMASS", "SMPL")


def _session(index: int, activity: str) -> PreparedSession:
    tracker = SessionTrackerFrame("tracker-1", (0.0, 0.0, 0.0, 1.0), (0.0, 0.0, 0.0, 1.0), 3, 2, 4)
    digest = f"{index + 16:064x}"
    manifest = {
        "sessionId": f"session-{index}", "subjectPseudonym": f"person-{index}",
        "deviceCohort": f"device-{index}", "trackerLayout": 1, "chipset": "BMI160",
        "transport": "WIFI", "activity": activity, "activityConfidence": 0.95, "driftSeverity": "LOW",
    }
    return PreparedSession(
        digest, manifest,
        (SessionFrame(0, 100, 20_000_000, (tracker,)), SessionFrame(1, 200, 20_000_000, (tracker,))),
        (SessionResetWindow(index, "tracker-1", "YAW", (0.0, 0.01, 0.0, 0.99995), 1, 0, "INCLUDE", (0, 1), (1, 2)),),
        {"valid_reset_windows": 1},
    )


def test_connected_cli_pipeline_is_repeatable_and_retains_every_contract(tmp_path, monkeypatch):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    sessions = {
        f"session-{index}.nvrdata": _session(index, fixture["activities"][index % len(fixture["activities"])])
        for index in range(fixture["real_session_count"])
    }
    monkeypatch.setattr(cli, "load_amass", lambda *args, **kwargs: _motion())
    monkeypatch.setattr(cli, "load_session", lambda path: sessions[Path(path).name])
    config = tmp_path / "config.json"
    config.write_text(json.dumps({
        "schema_version": 1, "seed": fixture["seed"], "deterministic": True, "sample_rate_hz": 50,
        "layouts": [5], "max_slots": 5,
        "model": {"feature_count": 16, "hidden_size": 2, "temporal_layers": 1, "kernel_size": 2,
                  "role_count": 64, "output_axes": 3, "maximum_correction_degrees": 30,
                  "maximum_drift_rate_degrees_per_second": 5},
        "splits": {"train": 0.25, "validation": 0.375, "test": 0.375,
                   "group_fields": ["domain", "subject_id", "source_id", "device_cohort"]},
        "losses": {"synthetic_dense": 1.0, "real_reset": 2.0, "temporal": 0.2,
                   "self_supervised": 0.2, "domain": 0.1, "quality_downweight": 0.25,
                   "exclude_quality_flags": [64, 128]},
        "features": {"contextual_missingness_probability": 0.15,
                     "maximum_unseen_cohort_regression": 100.0, "require_ablation_improvement": False},
    }), encoding="utf-8")
    prepared = tmp_path / "training-input.json"
    prepare_args = [
        "prepare", "--amass", "licensed-amass.npz", "--body-model", "licensed-smpl.npz",
        "--layout", "5", "--output", str(prepared), "--config", str(config),
    ]
    for name in sessions:
        prepare_args.extend(("--archive", name))
    assert cli._run(prepare_args) == 0
    prepared_payload = json.loads(prepared.read_text())
    assert {value["domain"] for value in prepared_payload["examples"]} == {"synthetic", "real"}

    first, second = tmp_path / "run-first", tmp_path / "run-second"
    for output in (first, second):
        assert cli._run(["train", "--input", str(prepared), "--output-dir", str(output),
                         "--epochs", "1", "--config", str(config)]) == 0
    comparison = tmp_path / "repeat-comparison.json"
    assert cli._run(["compare-runs", str(first / "run.json"), str(second / "run.json"),
                     "--output", str(comparison), "--metric-absolute-tolerance",
                     str(fixture["reproducibility"]["metric_absolute_tolerance"]), "--config", str(config)]) == 0
    assert json.loads(comparison.read_text())["passed"]
    assert execute_portable_personalization_probe(first / "personalization" / "nekovr-small-v1")["passed"]

    evaluation_dir = tmp_path / "evaluation"
    checkpoint = first / "model-checkpoint.json"
    assert cli._run(["evaluate", "--input", str(prepared), "--checkpoint", str(checkpoint),
                     "--output-dir", str(evaluation_dir), "--config", str(config)]) == 0
    evaluation = json.loads((evaluation_dir / "metrics.json").read_text())
    metadata = tmp_path / "export-metadata.json"
    run = json.loads((first / "run.json").read_text())
    metadata.write_text(json.dumps({
        "model_id": "nekovr-small-v1", "model_version": "e2e",
        "feature_schema": {"version": 1, "features": prepared_payload["feature_schema"]},
        "normalization": run["normalization"], "supported_roles": list(range(1, 6)),
        "slot_bounds": {"minimum": 1, "maximum": 5}, "context_bounds": {"minimum": 1, "maximum": 2},
        "provenance": {"seed": fixture["seed"], "config_sha256": run["config_sha256"],
                       "source_commit": run["source_commit"], "dataset_hashes": run["dataset_hashes"]},
        "validation_metrics": evaluation["candidate"]["overall"], "performance_tier": "small",
        "split_assignments": run["split_assignments"], "sources": prepared_payload["sources"],
        "evaluation": {"path": "metrics.json", "sha256": file_sha256(evaluation_dir / "metrics.json")},
    }), encoding="utf-8")
    model = tmp_path / "model.onnx"
    assert cli._run(["export-onnx", "--checkpoint", str(checkpoint), "--metadata", str(metadata),
                     "--output", str(model), "--config", str(config)]) == 0
    parity = tmp_path / "onnx-parity.json"
    assert cli._run(["parity", "--input", str(prepared), "--checkpoint", str(checkpoint),
                     "--model", str(model), "--sidecar", str(model) + ".json", "--output", str(parity),
                     "--config", str(config)]) == 0
    assert json.loads(parity.read_text())["passed"]
    decision = tmp_path / "promotion-decision.json"
    assert cli._run(["promote", "--model", str(model), "--sidecar", str(model) + ".json",
                     "--evaluation", str(evaluation_dir / "metrics.json"), "--parity", str(parity),
                     "--feature-qualification", str(first / "feature-qualification.json"),
                     "--output", str(decision), "--config", str(config)]) == 0
    assert json.loads(decision.read_text())["format"] == "nekovr-promotion-decision-v2"
    export_run = json.loads((tmp_path / "model.onnx.run.json").read_text())
    assert set(export_run["lineage"]["exported_artifacts"]) == {"model", "sidecar"}
    assert fixture["expected_stages"] == [
        "prepare", "split", "normalization", "global_training", "checkpoint_evaluation",
        "onnx_export", "onnx_parity", "promotion_decision", "personalization",
    ]
