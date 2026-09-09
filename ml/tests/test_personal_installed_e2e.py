from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path

from nekovr_ml.evaluation import EvaluationRecord
from nekovr_ml.math3d import IDENTITY, rotation_vector_to_quat
from nekovr_ml.model import CompactCausalModel, ModelConfig, SequenceSample, collate_variable_layout
from nekovr_ml.personal_data import (
    ACTIVITIES,
    EligibilityPolicy,
    PersonalWindow,
    analyze_session_eligibility,
    create_personal_split,
    iter_session_eligibility_manifests,
)
from nekovr_ml.personal_trainer import IpcMessage, TrainingPolicy
from nekovr_ml.personal_validation import (
    PersonalExportProvenance,
    export_validated_personal_model,
    validate_personal_model,
)
from nekovr_ml.trainer_worker import handle_message
from nekovr_ml.training import write_model_checkpoint


ARTIFACTS = Path(__file__).parents[1] / "artifacts" / "nekovr-small-v1"


def _evaluation(prediction: float) -> tuple[EvaluationRecord, ...]:
    records = []
    for index, activity in enumerate(("SEATED", "SEATED", "DANCE", "DANCE")):
        is_reset = index % 2 == 0
        records.append(EvaluationRecord(
            sequence_id=f"test-session-{index}", timestamp_s=float(index), layout="6", domain="real",
            person_id="profile-nine", chipset="BNO085", transport="WIFI", activity=activity,
            activity_confidence=0.95, drift_severity="medium", observed_xyzw=IDENTITY,
            target_xyzw=rotation_vector_to_quat((0.0, 0.0, 0.08)) if is_reset else IDENTITY,
            predicted_correction_rotation_vector=(0.0, 0.0, prediction) if is_reset else (0.0, 0.0, 0.0),
            confidence=0.9, clean_motion=not is_reset, reset_kind="YAW" if is_reset else None,
        ))
    return tuple(records)


def test_installed_personal_flow_handles_nine_long_manifests_resume_validate_and_export(tmp_path, monkeypatch):
    base_hash = "a" * 64
    feature_hash = "b" * 64
    manifests = []
    started = datetime(2026, 1, 1, tzinfo=timezone.utc)
    for index in range(9):
        session_hash = f"{index + 1:064x}"
        telemetry_path = tmp_path / f"session-{index}.telemetry.fbs.zst"
        telemetry_path.write_bytes(b"telemetry must stay unopened")
        manifest = tmp_path / f"session-{index}.manifest.json"
        manifest.write_text(json.dumps({
            "format": "nekovr-personal-session-summary-v1", "schema_version": 1,
            "session_sha256": session_hash, "profile_id": "profile-nine",
            "started_utc": (started + timedelta(days=index)).isoformat(),
            "schema_major": 1, "integrity_valid": True, "fatal_findings": [],
            "feature_schema_sha256": feature_hash, "compatible_base_hashes": [base_hash],
            "duration_s": 6 * 3600, "valid_duration_s": 5.9 * 3600, "usable_windows": 200,
            "reset_labels": 4, "clean_interval_s": 5 * 3600, "stable_body_assignments": True,
            "body_role_ids": [1, 2, 3, 4, 5, 6], "sensor_families": ["BNO085"],
            "layout": [1, 2, 3, 4, 5, 6], "activity_seconds": {name: 60.0 for name in ACTIVITIES},
            "channel_quality": 0.99, "excluded_ranges": [],
        }), encoding="utf-8")
        manifests.append(manifest)

    telemetry_opens = []
    original_open = Path.open
    def monitored_open(path, *args, **kwargs):
        if path.name.endswith("telemetry.fbs.zst"):
            telemetry_opens.append(path)
        return original_open(path, *args, **kwargs)
    with monkeypatch.context() as context:
        context.setattr(Path, "open", monitored_open)
        sessions = tuple(iter_session_eligibility_manifests(manifests))
    assert len(sessions) == 9
    assert not telemetry_opens
    eligibility = analyze_session_eligibility(sessions, "profile-nine", EligibilityPolicy(
        1, feature_hash, base_hash, minimum_total_valid_seconds=40 * 3600, minimum_usable_windows=900,
    ))
    assert eligibility.ready and eligibility.usable_hours > 50
    windows = tuple(PersonalWindow(
        f"window-{index}", session.session_sha256, 10.0, 20.0,
        ACTIVITIES[index % len(ACTIVITIES)], session.layout, "reset" if index % 2 else "clean", 0.99,
    ) for index, session in enumerate(sessions))
    split = create_personal_split(eligibility, sessions, windows, seed=42)
    assert set(split.session_assignments.values()) == {"train", "validation", "test"}

    model = CompactCausalModel(ModelConfig(1, 2, 1, 2, 8, 2, 3, 0.5, 0.1), seed=4)
    base_checkpoint = tmp_path / "base.json"
    write_model_checkpoint(model, base_checkpoint)
    training_input = tmp_path / "training.json"
    training_input.write_text(json.dumps({
        "format": "nekovr-training-input-v1", "examples": [{
            "sample_id": "train-window", "split": "train", "features": [[[0.2]]],
            "role_ids": [1], "slot_mask": [True], "channel_validity": [[[True]]],
            "time_deltas_s": [0.02], "target_correction_rotation_vectors": [[0.02, 0.0, 0.0]],
            "target_confidence": [1.0], "loss_weights": [1.0],
        }],
    }), encoding="utf-8")
    policy = TrainingPolicy(200, 0.1, 8 * 1024 * 1024, 1, 1, 0.0, 0.6, 0.6, 0.6)
    first_checkpoint = tmp_path / "first.zip"
    first = handle_message(IpcMessage("train", "first", {
        "artifact_dir": str(ARTIFACTS), "provider": "CPU", "base_checkpoint": str(base_checkpoint),
        "training_input": str(training_input), "learning_rate": 0.01, "seed": 7,
        "checkpoint": str(first_checkpoint), "policy": asdict(policy),
    }))
    resumed_checkpoint = tmp_path / "resumed.zip"
    resumed = handle_message(IpcMessage("train", "resume", {
        "artifact_dir": str(ARTIFACTS), "provider": "CPU", "base_checkpoint": str(base_checkpoint),
        "resume_checkpoint": str(first_checkpoint), "training_input": str(training_input),
        "learning_rate": 0.01, "seed": 7, "checkpoint": str(resumed_checkpoint), "policy": asdict(policy),
    }))
    assert first.kind == resumed.kind == "train_result"
    assert resumed_checkpoint.is_file()

    validation = validate_personal_model(_evaluation(-0.08), _evaluation(0.08))
    batch = collate_variable_layout((SequenceSample(
        (((0.2,),),), (1,), (True,), (((True,),),), (0.02,),
    ),), 1, 2)
    export = export_validated_personal_model(
        model, tmp_path / "installed-personal.onnx", validation=validation,
        personal=PersonalExportProvenance(
            base_hash, "profile-nine", tuple(session.session_sha256 for session in sessions),
            split.sha256, resumed.payload["checkpoint_sha256"],
        ),
        parity_batches=(batch,), training_runtime=model.forward,
        model_id="personal-nine", model_version="1.0.0", feature_schema={"features": ["x"]},
        normalization={"mean": [0.0], "standard_deviation": [1.0]}, supported_roles=(1,),
        minimum_slots=1, minimum_context=1, maximum_context=8,
        provenance={"seed": 42, "config_sha256": "c" * 64, "source_commit": "installed-test", "dataset_hashes": {"sessions": "d" * 64}},
    )
    assert export.model_path.is_file() and export.sidecar_path.is_file()
