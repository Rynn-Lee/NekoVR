from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from pathlib import Path
from typing import Mapping, Sequence

from .model_metadata import ModelSidecar, load_sidecar


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


def publish_catalog_entry(
    model_path: str | Path,
    sidecar_path: str | Path,
    catalog_path: str | Path,
    evidence: ArtifactPromotionEvidence,
    policy: ArtifactPromotionPolicy = ArtifactPromotionPolicy(),
) -> ArtifactPromotionResult:
    """Atomically publish only an integrity-checked model which passed every gate."""
    sidecar = load_sidecar(sidecar_path, model_path)
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
        "sidecar": Path(sidecar_path).name, "model": Path(model_path).name,
        "promotion_evidence": asdict(evidence),
    }
    payload["entries"] = [value for value in payload["entries"] if value.get("model_sha256") != sidecar.model_sha256] + [entry]
    payload["entries"].sort(key=lambda value: (value["model_id"], value["model_version"], value["model_sha256"]))
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(target)
    return result
