from __future__ import annotations

import pytest

from nekovr_ml.evaluation import EvaluationRecord
from nekovr_ml.math3d import IDENTITY, rotation_vector_to_quat
from nekovr_ml.model import CompactCausalModel, ModelConfig, SequenceSample, collate_variable_layout
from nekovr_ml.model_metadata import load_sidecar
from nekovr_ml.personal_validation import (
    PersonalExportProvenance,
    PersonalValidationPolicy,
    export_validated_personal_model,
    validate_personal_model,
)


def _records(prediction: tuple[float, float, float]) -> tuple[EvaluationRecord, ...]:
    values = []
    for index, (activity, layout) in enumerate((("SEATED", "5"), ("SEATED", "5"), ("DANCE", "8"), ("DANCE", "8"))):
        is_reset = index % 2 == 0
        values.append(EvaluationRecord(
            sequence_id=f"held-out-{index}", timestamp_s=float(index), layout=layout,
            domain="real", person_id="profile-local", chipset="BNO085", transport="WIFI",
            activity=activity, activity_confidence=0.95, drift_severity="medium",
            observed_xyzw=IDENTITY,
            target_xyzw=rotation_vector_to_quat((0.0, 0.0, 0.1)) if is_reset else IDENTITY,
            predicted_correction_rotation_vector=prediction if is_reset else (0.0, 0.0, 0.0),
            confidence=0.9, clean_motion=not is_reset, reset_kind="YAW" if is_reset else None,
        ))
    return tuple(values)


def test_personal_validation_compares_reset_safety_and_every_represented_cohort():
    report = validate_personal_model(
        _records((0.0, 0.0, -0.1)), _records((0.0, 0.0, 0.1)),
        PersonalValidationPolicy(minimum_cohort_samples=2),
    )
    assert report.passed
    assert report.evaluated_cohorts == ("activity:DANCE", "activity:SEATED", "layout:5", "layout:8")
    assert len(report.sha256) == 64

    regressed = validate_personal_model(
        _records((0.0, 0.0, 0.1)), _records((0.0, 0.0, -0.1)),
        PersonalValidationPolicy(minimum_cohort_samples=2),
    )
    assert not regressed.passed
    assert {finding.scope for finding in regressed.findings} >= {"overall", "activity:DANCE", "layout:8"}


def test_personal_export_is_atomic_and_records_all_hashes_and_three_runtime_parity(tmp_path):
    validation = validate_personal_model(_records((0.0, 0.0, -0.1)), _records((0.0, 0.0, 0.1)))
    model = CompactCausalModel(ModelConfig(2, 3, 1, 2, 16, 2, 3, 0.5, 0.1), seed=7)
    sample = SequenceSample(
        features=(((0.1, 0.2),),), role_ids=(1,), slot_mask=(True,),
        channel_validity=(((True, True),),),
        time_deltas_s=(0.02,),
    )
    batch = collate_variable_layout((sample,), 2, 2)
    hashes = [character * 64 for character in "abcdef"]
    result = export_validated_personal_model(
        model, tmp_path / "personal.onnx", validation=validation,
        personal=PersonalExportProvenance(hashes[0], "profile-local", (hashes[1], hashes[2]), hashes[3], hashes[4]),
        parity_batches=(batch,), training_runtime=model.forward,
        model_id="personal-profile-local", model_version="1.0.0",
        feature_schema={"features": ["a", "b"]},
        normalization={"mean": [0.0, 0.0], "standard_deviation": [1.0, 1.0]},
        supported_roles=(1,), minimum_slots=1, minimum_context=1, maximum_context=8,
        provenance={"seed": 7, "config_sha256": hashes[5], "source_commit": "test", "dataset_hashes": {"one": hashes[1]}},
    )
    assert result.parity.passed
    sidecar = load_sidecar(result.sidecar_path, result.model_path)
    assert sidecar.provenance["base_model_sha256"] == hashes[0]
    assert sidecar.model_kind == "personal" and sidecar.profile_id == "profile-local"
    assert sidecar.provenance["selected_session_sha256"] == sorted((hashes[1], hashes[2]))
    assert sidecar.provenance["split_manifest_sha256"] == hashes[3]
    assert sidecar.provenance["checkpoint_sha256"] == hashes[4]
    assert sidecar.provenance["metrics_sha256"] == result.metrics_sha256
    assert sidecar.validation_metrics["runtime_parity"]["passed"] is True
    assert not list(tmp_path.glob("*.partial"))


def test_failed_personal_validation_cannot_publish(tmp_path):
    validation = validate_personal_model(_records((0.0, 0.0, 0.1)), _records((0.0, 0.0, -0.1)))
    with pytest.raises(ValueError, match="validation passes"):
        export_validated_personal_model(
            CompactCausalModel(ModelConfig(1)), tmp_path / "blocked.onnx",
            validation=validation, personal=PersonalExportProvenance("a" * 64, "profile", ("b" * 64,), "c" * 64, "d" * 64),
            parity_batches=(), training_runtime=lambda batch: None,
            model_id="blocked", model_version="1", feature_schema={}, normalization={}, supported_roles=(),
            minimum_slots=1, minimum_context=1, maximum_context=1,
            provenance={},
        )
    assert not (tmp_path / "blocked.onnx").exists()
