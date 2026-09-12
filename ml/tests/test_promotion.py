from dataclasses import asdict
import json

import pytest

from nekovr_ml.probe import create_probe_bundle
from nekovr_ml.provenance import canonical_hash
from nekovr_ml.promotion import (
    ActivityCohortMetric, PromotionPolicy, REQUIRED_ACTIVITIES, evaluate_activity_gate,
    generate_promotion_decision, load_atomic_model_bundle, publish_catalog_entry, stage_atomic_model_bundle,
)
from nekovr_ml.evaluation import EvaluationRecord, evaluate


def _metric(activity, candidate=0.9, baseline=1.0):
    return ActivityCohortMetric(activity, 200, 60.0, 0.9, baseline, candidate, 0.01, 0.01, 0.1, 0.1)


def test_every_required_activity_must_have_confident_coverage():
    result = evaluate_activity_gate([_metric(activity) for activity in REQUIRED_ACTIVITIES[:-1]], PromotionPolicy())
    assert not result.passed
    assert result.missing_activities == ("STATIONARY",)


def test_single_lying_regression_blocks_aggregate_improvement():
    metrics = [_metric(activity, candidate=1.1 if activity == "LYING" else 0.5) for activity in REQUIRED_ACTIVITIES]
    result = evaluate_activity_gate(metrics)
    assert not result.passed
    assert result.regressions == {"LYING": ("angular_error",)}


def test_all_covered_non_regressing_cohorts_pass():
    result = evaluate_activity_gate([_metric(activity) for activity in REQUIRED_ACTIVITIES])
    assert result.passed


def test_promotion_decision_is_derived_from_hash_bound_artifacts(tmp_path):
    bundle = tmp_path / "bundle"
    create_probe_bundle(bundle)
    model = bundle / "probe.onnx"
    sidecar = bundle / "probe.onnx.json"
    checkpoint_sha = "c" * 64
    records = [asdict(EvaluationRecord(
        sequence_id=f"sequence-{index}", timestamp_s=0.0, layout="6",
        domain="synthetic" if index % 2 == 0 else "real", person_id=f"person-{index}",
        chipset="BMI160", transport="WIFI", activity=activity, activity_confidence=0.9,
        drift_severity="LOW", observed_xyzw=(0.0, 0.0, 0.0, 1.0), target_xyzw=(0.0, 0.0, 0.0, 1.0),
        predicted_correction_rotation_vector=(0.0, 0.0, 0.0), confidence=0.9, clean_motion=True,
    )) for index, activity in enumerate(REQUIRED_ACTIVITIES)]
    candidate = evaluate(tuple(EvaluationRecord(**value) for value in records)).to_dict()
    evaluation = tmp_path / "evaluation.json"
    evaluation.write_text(json.dumps({
        "format": "nekovr-checkpoint-evaluation-v1", "checkpoint_sha256": checkpoint_sha,
        "predictions_sha256": canonical_hash(records), "records": records,
        "candidate": candidate,
        "comparisons": {"identity": {"angular_error_delta_radians": 0.0, "false_correction_delta": 0.0,
                                       "jitter_delta_radians_per_second2": 0.0}},
    }))
    parity = tmp_path / "parity.json"
    boundary_cases = [
        {"batch": batch, "context": context, "slots": slots}
        for batch in (1, 4) for context in (2, 4) for slots in (1, 2)
    ]
    parity.write_text(json.dumps({
        "format": "nekovr-onnx-parity-v2", "checkpoint_sha256": checkpoint_sha,
        "model_sha256": json.loads(sidecar.read_text())["model_sha256"],
        "maximum_absolute_error": 1e-7, "percentile_99_absolute_error": 1e-8,
        "finite_outputs": True, "bounded_outputs": True, "masked_slots_zero": True,
        "passed": True, "deterministic_same_process": True, "deterministic_cross_process": True,
        "all_declared_outputs": ["correction_rotation_vectors", "confidence", "drift_rate"],
        "boundary_cases": boundary_cases, "boundary_cases_sha256": canonical_hash(boundary_cases),
        "invalid_input_rejections": {"feature_width": True, "feature_rank": True, "role_id": True,
                                     "slot_upper_bound": True, "context_upper_bound": True},
    }))
    features = tmp_path / "features.json"
    features.write_text(json.dumps({"format": "nekovr-feature-qualification-v1", "all_contextual_features_promoted": True,
                                    "decisions": [{"feature": "temperature", "promoted": True, "reasons": []}]}))
    output = tmp_path / "decision.json"
    decision = generate_promotion_decision(model, sidecar, evaluation, parity, features, output)
    assert decision["passed"]
    assert decision["format"] == "nekovr-promotion-decision-v2"
    assert set(decision["evidence_hashes"]) == {"validation_metrics", "onnx_parity", "safety", "quality", "cohort", "sidecar"}
    atomic = stage_atomic_model_bundle(model, sidecar, parity, evaluation, output, tmp_path / "model-bundles")
    manifest = load_atomic_model_bundle(atomic)
    assert atomic.name == manifest["bundle_sha256"]
    catalog = tmp_path / "catalog.json"
    assert publish_catalog_entry(atomic, catalog).passed
    assert json.loads(catalog.read_text())["entries"][0]["bundle_sha256"] == atomic.name

    tampered = json.loads(evaluation.read_text())
    tampered["records"].append({"prediction": [1.0, 0.0, 0.0]})
    evaluation.write_text(json.dumps(tampered))
    with pytest.raises(ValueError, match="prediction hash"):
        generate_promotion_decision(model, sidecar, evaluation, parity, features, output)


def test_atomic_bundle_rejects_changed_evidence_without_partial_publication(tmp_path):
    # Reuse the connected evidence generator above through one successful invocation.
    test_promotion_decision_is_derived_from_hash_bound_artifacts(tmp_path)
    bundles = tmp_path / "model-bundles"
    bundle = next(path for path in bundles.iterdir() if path.is_dir())
    failed_root = tmp_path / "failed-bundles"
    with pytest.raises(ValueError, match="evidence hashes|prediction hash"):
        stage_atomic_model_bundle(
            tmp_path / "bundle" / "probe.onnx", tmp_path / "bundle" / "probe.onnx.json",
            tmp_path / "parity.json", tmp_path / "evaluation.json", tmp_path / "decision.json", failed_root,
        )
    assert not failed_root.exists()
    parity = json.loads((bundle / "parity.json").read_text())
    parity["maximum_absolute_error"] = 1.0
    (bundle / "parity.json").write_text(json.dumps(parity))
    catalog = tmp_path / "tampered-catalog.json"
    with pytest.raises(ValueError, match="content address|artifact hashes"):
        publish_catalog_entry(bundle, catalog)
    assert not catalog.exists()
