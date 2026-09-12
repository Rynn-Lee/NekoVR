from __future__ import annotations

from dataclasses import dataclass, replace
import json
from pathlib import Path
import shutil
import tempfile
from typing import Mapping, Sequence

from .evaluation import EvaluationRecord, evaluate
from .model_metadata import ModelSidecar
from .onnx_export import OUTPUT_NAMES, SUPPORTED_BATCH_BOUNDS
from .onnx_validation import inspect_export
from .provenance import canonical_hash, file_sha256


REQUIRED_ACTIVITIES = (
    "STANDING",
    "SEATED",
    "LYING",
    "CROUCHING",
    "TRANSITION",
    "LOCOMOTION",
    "DANCE",
    "STATIONARY",
)


@dataclass(frozen=True)
class ActivityCohortMetric:
    activity: str
    samples: int
    duration_seconds: float
    mean_confidence: float
    baseline_error: float
    candidate_error: float
    baseline_false_correction: float
    candidate_false_correction: float
    baseline_jitter: float
    candidate_jitter: float


@dataclass(frozen=True)
class PromotionPolicy:
    minimum_samples: int = 100
    minimum_duration_seconds: float = 30.0
    minimum_activity_confidence: float = 0.6
    maximum_error_regression: float = 0.0
    maximum_false_correction_regression: float = 0.0
    maximum_jitter_regression: float = 0.0


@dataclass(frozen=True)
class ActivityGateResult:
    passed: bool
    covered_activities: tuple[str, ...]
    missing_activities: tuple[str, ...]
    regressions: Mapping[str, tuple[str, ...]]


def evaluate_activity_gate(metrics: Sequence[ActivityCohortMetric], policy: PromotionPolicy = PromotionPolicy()) -> ActivityGateResult:
    by_activity = {metric.activity.upper(): metric for metric in metrics if metric.activity.upper() != "UNKNOWN"}
    covered, missing = [], []
    regressions: dict[str, tuple[str, ...]] = {}
    for activity in REQUIRED_ACTIVITIES:
        metric = by_activity.get(activity)
        if metric is None or metric.samples < policy.minimum_samples or metric.duration_seconds < policy.minimum_duration_seconds or metric.mean_confidence < policy.minimum_activity_confidence:
            missing.append(activity)
            continue
        covered.append(activity)
        failures = []
        if metric.candidate_error - metric.baseline_error > policy.maximum_error_regression:
            failures.append("angular_error")
        if metric.candidate_false_correction - metric.baseline_false_correction > policy.maximum_false_correction_regression:
            failures.append("false_correction")
        if metric.candidate_jitter - metric.baseline_jitter > policy.maximum_jitter_regression:
            failures.append("jitter")
        if failures:
            regressions[activity] = tuple(failures)
    return ActivityGateResult(not missing and not regressions, tuple(covered), tuple(missing), regressions)


@dataclass(frozen=True)
class ArtifactPromotionPolicy:
    maximum_small_model_bytes: int = 15 * 1024 * 1024
    maximum_parity_error: float = 1e-5
    maximum_parity_percentile_99_error: float = 5e-6
    minimum_cohort_samples: int = 1
    minimum_cohort_duration_seconds: float = 0.0


@dataclass(frozen=True)
class ArtifactPromotionEvidence:
    maximum_parity_error: float
    percentile_99_parity_error: float
    finite_outputs: bool
    bounded_outputs: bool
    masked_slots_zero: bool
    activity_gate_passed: bool
    quality_non_regression_passed: bool


@dataclass(frozen=True)
class ArtifactPromotionResult:
    passed: bool
    failures: tuple[str, ...]


def evaluate_artifact_promotion(
    sidecar: ModelSidecar,
    evidence: ArtifactPromotionEvidence,
    policy: ArtifactPromotionPolicy = ArtifactPromotionPolicy(),
) -> ArtifactPromotionResult:
    failures = []
    if sidecar.performance_tier == "small" and sidecar.model_size_bytes > policy.maximum_small_model_bytes:
        failures.append("small_model_size")
    if evidence.maximum_parity_error > policy.maximum_parity_error:
        failures.append("maximum_parity_error")
    if evidence.percentile_99_parity_error > policy.maximum_parity_percentile_99_error:
        failures.append("percentile_99_parity_error")
    for name in ("finite_outputs", "bounded_outputs", "masked_slots_zero", "activity_gate_passed", "quality_non_regression_passed"):
        if not getattr(evidence, name):
            failures.append(name)
    return ArtifactPromotionResult(not failures, tuple(failures))


def _read_json(path: str | Path, expected_format: str) -> dict:
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"unable to read generated evidence {path}: {error}") from error
    if not isinstance(payload, dict) or payload.get("format") != expected_format:
        raise ValueError(f"generated evidence {path} has an unsupported format")
    return payload


def _write_json_atomic(path: str | Path, payload: object) -> Path:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(target)
    return target


def promotion_report_paths(decision_path: str | Path) -> dict[str, Path]:
    target = Path(decision_path)
    return {
        "safety": target.with_name(target.stem + ".safety.json"),
        "quality": target.with_name(target.stem + ".quality.json"),
        "cohort": target.with_name(target.stem + ".cohort.json"),
    }


def _verify_decision_evidence(
    model_path: str | Path,
    sidecar_path: str | Path,
    parity_path: str | Path,
    evaluation_path: str | Path,
    safety_path: str | Path,
    quality_path: str | Path,
    cohort_path: str | Path,
    decision_path: str | Path,
) -> tuple[ModelSidecar, dict, dict, dict, dict, dict]:
    sidecar = inspect_export(model_path, sidecar_path)
    parity = _read_json(parity_path, "nekovr-onnx-parity-v2")
    evaluation = _read_json(evaluation_path, "nekovr-checkpoint-evaluation-v1")
    safety = _read_json(safety_path, "nekovr-promotion-safety-v1")
    quality = _read_json(quality_path, "nekovr-promotion-quality-v1")
    cohort = _read_json(cohort_path, "nekovr-promotion-cohort-v1")
    decision = _read_json(decision_path, "nekovr-promotion-decision-v2")
    paths = {
        "sidecar": sidecar_path, "onnx_parity": parity_path, "validation_metrics": evaluation_path,
        "safety": safety_path, "quality": quality_path, "cohort": cohort_path,
    }
    expected_hashes = {name: file_sha256(path) for name, path in paths.items()}
    if decision.get("evidence_hashes") != expected_hashes:
        raise ValueError("promotion decision evidence hashes do not match generated report bytes")
    checkpoint = evaluation.get("checkpoint_sha256")
    for name, report in (("parity", parity), ("safety", safety), ("quality", quality), ("cohort", cohort)):
        if report.get("model_sha256") != sidecar.model_sha256 or report.get("checkpoint_sha256") != checkpoint:
            raise ValueError(f"{name} report identity does not match model/checkpoint")
    if decision.get("model_sha256") != sidecar.model_sha256 or decision.get("checkpoint_sha256") != checkpoint:
        raise ValueError("promotion decision identity does not match model/checkpoint")
    if evaluation.get("predictions_sha256") != canonical_hash(evaluation.get("records")):
        raise ValueError("validation metrics prediction hash does not match retained records")
    try:
        records = tuple(EvaluationRecord(**value) for value in evaluation["records"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError(f"validation metrics records are invalid: {error}") from error
    if canonical_hash(evaluation.get("candidate")) != canonical_hash(evaluate(records).to_dict()):
        raise ValueError("validation metrics were not derived from retained records")
    expected_rejections = {"feature_width", "feature_rank", "role_id", "slot_upper_bound", "context_upper_bound"}
    boundary_cases = parity.get("boundary_cases")
    if (
        parity.get("all_declared_outputs") != list(OUTPUT_NAMES)
        or set(parity.get("invalid_input_rejections", {})) != expected_rejections
        or not all(parity["invalid_input_rejections"].values())
        or not parity.get("deterministic_same_process")
        or not parity.get("deterministic_cross_process")
        or not isinstance(boundary_cases, list)
        or parity.get("boundary_cases_sha256") != canonical_hash(boundary_cases)
    ):
        raise ValueError("parity report does not contain the required contract matrix")
    if (
        {value.get("batch") for value in boundary_cases} != set(SUPPORTED_BATCH_BOUNDS.values())
        or {value.get("context") for value in boundary_cases} != set(sidecar.context_bounds.values())
        or {value.get("slots") for value in boundary_cases} != set(sidecar.slot_bounds.values())
    ):
        raise ValueError("parity report does not cover every declared boundary")
    if safety.get("source_evaluation_sha256") != expected_hashes["validation_metrics"] or safety.get("source_parity_sha256") != expected_hashes["onnx_parity"]:
        raise ValueError("safety report source hashes do not match")
    if cohort.get("source_evaluation_sha256") != expected_hashes["validation_metrics"]:
        raise ValueError("cohort report source hash does not match")
    safety_gates = safety.get("gates", {})
    if set(safety_gates) != {"finite_outputs", "bounded_outputs", "masked_slots_zero", "non_regression"} or safety.get("passed") != all(safety_gates.values()):
        raise ValueError("safety report gates are incomplete or inconsistent")
    decisions = quality.get("decisions")
    if not isinstance(decisions, list) or quality.get("passed") != (bool(decisions) and all(value.get("promoted") for value in decisions)):
        raise ValueError("quality report was not derived from feature decisions")
    cohort_gates = cohort.get("gates", {})
    if set(cohort_gates) != {"activity_coverage", "layout_coverage", "domain_coverage", "hardware_coverage"} or cohort.get("passed") != all(cohort_gates.values()):
        raise ValueError("cohort report gates are incomplete or inconsistent")
    coverage = cohort.get("coverage", {})
    if not set(REQUIRED_ACTIVITIES).issubset(coverage.get("activity", ())) or not coverage.get("layout") or not {"synthetic", "real"}.issubset(coverage.get("domain", ())):
        raise ValueError("cohort report coverage is incomplete")
    expected_gate_values = {
        "parity": bool(parity["passed"]), "activity_coverage": bool(cohort_gates["activity_coverage"]),
        "layout_coverage": bool(cohort_gates["layout_coverage"]), "domain_coverage": bool(cohort_gates["domain_coverage"]),
        "hardware_coverage": bool(cohort_gates["hardware_coverage"]), "non_regression": bool(safety_gates["non_regression"]),
        "feature_qualification": bool(quality["passed"]),
    }
    if any(decision.get("gates", {}).get(name) != value for name, value in expected_gate_values.items()):
        raise ValueError("promotion decision gates differ from generated reports")
    if not decision.get("passed") or not all(report.get("passed") for report in (parity, safety, quality, cohort)):
        raise ValueError("generated promotion evidence did not pass every gate")
    return sidecar, parity, evaluation, safety, quality, cohort


def stage_atomic_model_bundle(
    model_path: str | Path,
    sidecar_path: str | Path,
    parity_path: str | Path,
    evaluation_path: str | Path,
    decision_path: str | Path,
    output_root: str | Path,
) -> Path:
    """Commit all activatable bytes under one content address using one directory rename."""
    reports = promotion_report_paths(decision_path)
    _verify_decision_evidence(
        model_path, sidecar_path, parity_path, evaluation_path,
        reports["safety"], reports["quality"], reports["cohort"], decision_path,
    )
    sources = {
        "model.onnx": Path(model_path), "model.onnx.json": Path(sidecar_path), "parity.json": Path(parity_path),
        "validation-metrics.json": Path(evaluation_path), "promotion.json": Path(decision_path),
        "safety.json": reports["safety"], "quality.json": reports["quality"], "cohort.json": reports["cohort"],
    }
    artifacts = {name: {"sha256": file_sha256(path), "size_bytes": path.stat().st_size} for name, path in sorted(sources.items())}
    bundle_sha256 = canonical_hash({"format": "nekovr-atomic-model-bundle-v1", "artifacts": artifacts})
    root = Path(output_root)
    root.mkdir(parents=True, exist_ok=True)
    target = root / bundle_sha256
    if target.exists():
        load_atomic_model_bundle(target)
        return target
    staging = Path(tempfile.mkdtemp(prefix=f".{bundle_sha256}.partial-", dir=root))
    try:
        for name, source in sources.items():
            shutil.copyfile(source, staging / name)
        _write_json_atomic(staging / "manifest.json", {
            "format": "nekovr-atomic-model-bundle-v1", "bundle_sha256": bundle_sha256,
            "model_sha256": artifacts["model.onnx"]["sha256"], "artifacts": artifacts,
        })
        staging.replace(target)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    load_atomic_model_bundle(target)
    return target


def load_atomic_model_bundle(bundle_path: str | Path) -> dict:
    root = Path(bundle_path)
    manifest = _read_json(root / "manifest.json", "nekovr-atomic-model-bundle-v1")
    expected_names = {
        "model.onnx", "model.onnx.json", "parity.json", "validation-metrics.json", "promotion.json",
        "safety.json", "quality.json", "cohort.json",
    }
    if set(manifest.get("artifacts", {})) != expected_names or {path.name for path in root.iterdir()} != expected_names | {"manifest.json"}:
        raise ValueError("atomic model bundle members are incomplete or unexpected")
    actual = {
        name: {"sha256": file_sha256(root / name), "size_bytes": (root / name).stat().st_size}
        for name in sorted(expected_names)
    }
    expected_bundle = canonical_hash({"format": "nekovr-atomic-model-bundle-v1", "artifacts": actual})
    if manifest.get("artifacts") != actual or manifest.get("bundle_sha256") != expected_bundle or root.name != expected_bundle:
        raise ValueError("atomic model bundle content address or artifact hashes do not match")
    _verify_decision_evidence(
        root / "model.onnx", root / "model.onnx.json", root / "parity.json", root / "validation-metrics.json",
        root / "safety.json", root / "quality.json", root / "cohort.json", root / "promotion.json",
    )
    return manifest


def publish_catalog_entry(
    bundle_path: str | Path,
    catalog_path: str | Path,
    policy: ArtifactPromotionPolicy = ArtifactPromotionPolicy(),
) -> ArtifactPromotionResult:
    """Publish only a complete bundle whose generated report bytes pass revalidation."""
    manifest = load_atomic_model_bundle(bundle_path)
    root = Path(bundle_path)
    sidecar, parity, _evaluation, safety, quality, cohort = _verify_decision_evidence(
        root / "model.onnx", root / "model.onnx.json", root / "parity.json", root / "validation-metrics.json",
        root / "safety.json", root / "quality.json", root / "cohort.json", root / "promotion.json",
    )
    evidence = ArtifactPromotionEvidence(
        maximum_parity_error=float(parity["maximum_absolute_error"]),
        percentile_99_parity_error=float(parity["percentile_99_absolute_error"]),
        finite_outputs=bool(safety["gates"]["finite_outputs"]), bounded_outputs=bool(safety["gates"]["bounded_outputs"]),
        masked_slots_zero=bool(safety["gates"]["masked_slots_zero"]), activity_gate_passed=bool(cohort["passed"]),
        quality_non_regression_passed=bool(quality["passed"]),
    )
    result = evaluate_artifact_promotion(sidecar, evidence, policy)
    if not result.passed:
        raise ValueError(f"model failed publication gates: {', '.join(result.failures)}")
    target = Path(catalog_path)
    if target.exists():
        payload = json.loads(target.read_text(encoding="utf-8"))
        if payload.get("format") != "nekovr-local-model-catalog-v1" or not isinstance(payload.get("entries"), list):
            raise ValueError("unsupported local model catalog format")
    else:
        payload = {"format": "nekovr-local-model-catalog-v1", "entries": []}
    entry = {
        "model_id": sidecar.model_id, "model_version": sidecar.model_version,
        "model_sha256": sidecar.model_sha256, "model_size_bytes": sidecar.model_size_bytes,
        "bundle": root.name, "bundle_sha256": manifest["bundle_sha256"],
        "promotion_evidence_hashes": _read_json(root / "promotion.json", "nekovr-promotion-decision-v2")["evidence_hashes"],
    }
    payload["entries"] = [value for value in payload["entries"] if value.get("model_sha256") != sidecar.model_sha256] + [entry]
    payload["entries"].sort(key=lambda value: (value["model_id"], value["model_version"], value["model_sha256"]))
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(target)
    return result


def generate_promotion_decision(
    model_path: str | Path,
    sidecar_path: str | Path,
    evaluation_path: str | Path,
    parity_path: str | Path,
    feature_qualification_path: str | Path,
    output_path: str | Path,
    policy: ArtifactPromotionPolicy = ArtifactPromotionPolicy(),
) -> Mapping[str, object]:
    """Derive a hash-bound decision exclusively from generated evidence bytes."""
    sidecar = inspect_export(model_path, sidecar_path)
    evaluation = json.loads(Path(evaluation_path).read_text(encoding="utf-8"))
    parity = json.loads(Path(parity_path).read_text(encoding="utf-8"))
    features = json.loads(Path(feature_qualification_path).read_text(encoding="utf-8"))
    if evaluation.get("format") != "nekovr-checkpoint-evaluation-v1":
        raise ValueError("promotion requires generated checkpoint evaluation evidence")
    if parity.get("format") != "nekovr-onnx-parity-v2":
        raise ValueError("promotion requires generated ONNX parity evidence")
    if features.get("format") != "nekovr-feature-qualification-v1":
        raise ValueError("promotion requires generated feature qualification evidence")
    if evaluation.get("predictions_sha256") != canonical_hash(evaluation.get("records")):
        raise ValueError("evaluation prediction hash does not match retained replay records")
    try:
        executed_records = tuple(EvaluationRecord(**value) for value in evaluation["records"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError(f"evaluation replay records are invalid: {error}") from error
    recomputed_candidate = evaluate(executed_records).to_dict()
    if canonical_hash(evaluation.get("candidate")) != canonical_hash(recomputed_candidate):
        raise ValueError("evaluation metrics were not derived from retained replay records")
    identity_records = tuple(replace(value, predicted_correction_rotation_vector=(0.0, 0.0, 0.0), reset_kind=None) for value in executed_records)
    identity_report = evaluate(identity_records)
    candidate_overall = evaluate(executed_records).overall
    derived_identity = {
        "angular_error_delta_radians": candidate_overall.angular_error_after_radians - identity_report.overall.angular_error_after_radians,
        "false_correction_delta": candidate_overall.clean_false_correction_rate - identity_report.overall.clean_false_correction_rate,
        "jitter_delta_radians_per_second2": candidate_overall.temporal_jitter_radians_per_second2 - identity_report.overall.temporal_jitter_radians_per_second2,
    }
    supplied_identity = evaluation.get("comparisons", {}).get("identity", {})
    if canonical_hash(supplied_identity) != canonical_hash(derived_identity):
        raise ValueError("baseline comparison was not derived from retained replay records")
    checkpoint_sha = evaluation.get("checkpoint_sha256")
    if parity.get("checkpoint_sha256") != checkpoint_sha or parity.get("model_sha256") != sidecar.model_sha256:
        raise ValueError("evaluation, parity, checkpoint, and model identities do not agree")
    candidate = evaluation.get("candidate", {})
    cohorts = candidate.get("cohorts", {}) if isinstance(candidate, Mapping) else {}
    comparisons = evaluation.get("comparisons", {})
    identity = derived_identity
    activity = cohorts.get("activity", {}) if isinstance(cohorts, Mapping) else {}
    coverage = {
        "activity": sorted(activity),
        "layout": sorted((cohorts.get("layout", {}) if isinstance(cohorts, Mapping) else {})),
        "domain": sorted((cohorts.get("domain", {}) if isinstance(cohorts, Mapping) else {})),
        "hardware": sorted((cohorts.get("chipset", {}) if isinstance(cohorts, Mapping) else {})),
    }
    gates = {
        "parity": (
            bool(parity.get("passed"))
            and
            float(parity.get("maximum_absolute_error", float("inf"))) <= policy.maximum_parity_error
            and float(parity.get("percentile_99_absolute_error", float("inf"))) <= policy.maximum_parity_percentile_99_error
            and bool(parity.get("finite_outputs")) and bool(parity.get("bounded_outputs"))
            and bool(parity.get("masked_slots_zero")) and bool(parity.get("deterministic_cross_process"))
            and all(bool(value) for value in parity.get("invalid_input_rejections", {}).values())
        ),
        "activity_coverage": set(REQUIRED_ACTIVITIES).issubset(activity) and all(
            int(activity[name].get("samples", 0)) >= policy.minimum_cohort_samples
            and float(activity[name].get("duration_seconds", -1.0)) >= policy.minimum_cohort_duration_seconds
            for name in REQUIRED_ACTIVITIES
        ),
        "layout_coverage": bool(coverage["layout"]),
        "domain_coverage": {"synthetic", "real"}.issubset(coverage["domain"]),
        "hardware_coverage": bool(coverage["hardware"]) and "UNKNOWN" not in coverage["hardware"],
        "non_regression": all(float(identity.get(name, float("inf"))) <= 0.0 for name in (
            "angular_error_delta_radians", "false_correction_delta", "jitter_delta_radians_per_second2"
        )),
        "feature_qualification": bool(features.get("decisions")) and all(
            bool(value.get("promoted")) for value in features.get("decisions", ())
        ),
    }
    if sidecar.performance_tier == "small":
        gates["model_size"] = sidecar.model_size_bytes <= policy.maximum_small_model_bytes
    failures = sorted(name for name, passed in gates.items() if not passed)
    reports = promotion_report_paths(output_path)
    safety_gates = {name: value for name, value in {
        "finite_outputs": bool(parity.get("finite_outputs")), "bounded_outputs": bool(parity.get("bounded_outputs")),
        "masked_slots_zero": bool(parity.get("masked_slots_zero")), "non_regression": gates["non_regression"],
    }.items()}
    safety = {
        "format": "nekovr-promotion-safety-v1", "model_sha256": sidecar.model_sha256,
        "checkpoint_sha256": checkpoint_sha, "source_evaluation_sha256": file_sha256(evaluation_path),
        "source_parity_sha256": file_sha256(parity_path), "overall": candidate.get("overall", {}),
        "comparisons": comparisons, "gates": safety_gates, "passed": all(safety_gates.values()),
    }
    quality = {
        "format": "nekovr-promotion-quality-v1", "model_sha256": sidecar.model_sha256,
        "checkpoint_sha256": checkpoint_sha, "source_feature_qualification_sha256": file_sha256(feature_qualification_path),
        "decisions": features.get("decisions", []), "passed": gates["feature_qualification"],
    }
    cohort_gates = {name: gates[name] for name in ("activity_coverage", "layout_coverage", "domain_coverage", "hardware_coverage")}
    cohort = {
        "format": "nekovr-promotion-cohort-v1", "model_sha256": sidecar.model_sha256,
        "checkpoint_sha256": checkpoint_sha, "source_evaluation_sha256": file_sha256(evaluation_path),
        "coverage": coverage, "gates": cohort_gates, "passed": all(cohort_gates.values()),
    }
    _write_json_atomic(reports["safety"], safety)
    _write_json_atomic(reports["quality"], quality)
    _write_json_atomic(reports["cohort"], cohort)
    decision = {
        "format": "nekovr-promotion-decision-v2",
        "model_sha256": sidecar.model_sha256,
        "checkpoint_sha256": checkpoint_sha,
        "evidence_hashes": {
            "validation_metrics": file_sha256(evaluation_path),
            "onnx_parity": file_sha256(parity_path),
            "safety": file_sha256(reports["safety"]),
            "quality": file_sha256(reports["quality"]),
            "cohort": file_sha256(reports["cohort"]),
            "sidecar": file_sha256(sidecar_path),
        },
        "coverage": coverage,
        "gates": gates,
        "passed": not failures,
        "failures": failures,
    }
    _write_json_atomic(output_path, decision)
    return decision
