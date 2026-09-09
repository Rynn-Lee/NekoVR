from __future__ import annotations

from dataclasses import asdict, dataclass, replace
import math
import os
from pathlib import Path
import tempfile
from typing import Callable, Mapping, Sequence

import numpy as np

from .evaluation import EvaluationRecord, MetricSet, evaluate
from .model import CompactCausalModel, ModelBatch, ModelOutput
from .model_metadata import load_sidecar, write_sidecar
from .onnx_export import export_model
from .onnx_validation import compare_framework_and_onnx
from .provenance import canonical_hash


@dataclass(frozen=True)
class PersonalValidationPolicy:
    minimum_cohort_samples: int = 2
    minimum_cohort_duration_seconds: float = 0.0
    maximum_reset_target_error_regression_radians: float = 0.0
    maximum_resets_per_hour_regression: float = 0.0
    maximum_time_to_first_reset_regression_seconds: float = 0.0
    maximum_clean_false_correction_rate_regression: float = 0.0
    maximum_clean_false_correction_magnitude_regression_radians: float = 0.0
    maximum_temporal_jitter_regression_radians_per_second2: float = 0.0
    maximum_correction_magnitude_radians: float = math.radians(30.0)

    def __post_init__(self) -> None:
        values = asdict(self).values()
        if self.minimum_cohort_samples <= 0 or any(
            not math.isfinite(float(value)) or float(value) < 0 for value in values
        ):
            raise ValueError("personal validation thresholds must be finite and non-negative")


@dataclass(frozen=True)
class PersonalValidationFinding:
    scope: str
    metric: str
    base_value: float | None
    candidate_value: float | None
    limit: float
    message: str


@dataclass(frozen=True)
class PersonalValidationReport:
    format: str
    schema_version: int
    passed: bool
    held_out_sample_keys_sha256: str
    base: Mapping[str, object]
    candidate: Mapping[str, object]
    evaluated_cohorts: tuple[str, ...]
    findings: tuple[PersonalValidationFinding, ...]
    policy: PersonalValidationPolicy

    def to_dict(self) -> dict[str, object]:
        return asdict(self)

    @property
    def sha256(self) -> str:
        return canonical_hash(self.to_dict())


def _record_key(record: EvaluationRecord) -> tuple[object, ...]:
    return (
        record.sequence_id, record.timestamp_s, record.layout, record.domain, record.person_id,
        record.chipset, record.transport, record.activity.upper(), record.activity_confidence,
        record.drift_severity, record.observed_xyzw, record.target_xyzw, record.clean_motion,
        record.reset_kind,
    )


def _metric_findings(
    scope: str,
    base: MetricSet,
    candidate: MetricSet,
    policy: PersonalValidationPolicy,
) -> list[PersonalValidationFinding]:
    findings: list[PersonalValidationFinding] = []

    def upper(metric: str, base_value: float | None, candidate_value: float | None, allowance: float) -> None:
        if base_value is None or candidate_value is None:
            if base_value != candidate_value:
                findings.append(PersonalValidationFinding(scope, metric, base_value, candidate_value, allowance, f"{scope} lacks comparable {metric} evidence"))
            return
        limit = base_value + allowance
        if candidate_value > limit:
            findings.append(PersonalValidationFinding(scope, metric, base_value, candidate_value, limit, f"{scope} regressed {metric}"))

    upper("reset_target_error_radians", base.reset_target_error_radians, candidate.reset_target_error_radians, policy.maximum_reset_target_error_regression_radians)
    upper("yaw_resets_per_hour", base.yaw_resets_per_hour, candidate.yaw_resets_per_hour, policy.maximum_resets_per_hour_regression)
    upper("full_resets_per_hour", base.full_resets_per_hour, candidate.full_resets_per_hour, policy.maximum_resets_per_hour_regression)
    upper("clean_false_correction_rate", base.clean_false_correction_rate, candidate.clean_false_correction_rate, policy.maximum_clean_false_correction_rate_regression)
    upper("clean_false_correction_magnitude_radians", base.clean_false_correction_magnitude_radians, candidate.clean_false_correction_magnitude_radians, policy.maximum_clean_false_correction_magnitude_regression_radians)
    upper("temporal_jitter_radians_per_second2", base.temporal_jitter_radians_per_second2, candidate.temporal_jitter_radians_per_second2, policy.maximum_temporal_jitter_regression_radians_per_second2)
    if candidate.correction_magnitude_radians > policy.maximum_correction_magnitude_radians:
        findings.append(PersonalValidationFinding(scope, "correction_magnitude_radians", base.correction_magnitude_radians, candidate.correction_magnitude_radians, policy.maximum_correction_magnitude_radians, f"{scope} exceeds the correction bound"))
    if base.time_to_first_reset_seconds is not None:
        minimum = base.time_to_first_reset_seconds - policy.maximum_time_to_first_reset_regression_seconds
        candidate_time = candidate.time_to_first_reset_seconds
        # No reset in the held-out interval is right-censored improvement, not missing evidence.
        comparable_time = candidate.duration_seconds if candidate_time is None else candidate_time
        if comparable_time < minimum:
            findings.append(PersonalValidationFinding(scope, "time_to_first_reset_seconds", base.time_to_first_reset_seconds, candidate_time, minimum, f"{scope} regressed time to first reset"))
    return findings


def validate_personal_model(
    base_records: Sequence[EvaluationRecord],
    candidate_records: Sequence[EvaluationRecord],
    policy: PersonalValidationPolicy = PersonalValidationPolicy(),
) -> PersonalValidationReport:
    if not base_records or len(base_records) != len(candidate_records):
        raise ValueError("base and candidate need the same non-empty held-out replay")
    base_keys = tuple(_record_key(record) for record in base_records)
    candidate_keys = tuple(_record_key(record) for record in candidate_records)
    if base_keys != candidate_keys:
        raise ValueError("base and candidate held-out replay observations must match in order")
    base_report = evaluate(base_records)
    candidate_report = evaluate(candidate_records)
    findings = _metric_findings("overall", base_report.overall, candidate_report.overall, policy)
    maximum_candidate_correction = max(
        math.sqrt(sum(value * value for value in record.predicted_correction_rotation_vector))
        for record in candidate_records
    )
    if maximum_candidate_correction > policy.maximum_correction_magnitude_radians:
        findings.append(PersonalValidationFinding(
            "overall", "maximum_correction_magnitude_radians", None,
            maximum_candidate_correction, policy.maximum_correction_magnitude_radians,
            "candidate exceeds the per-sample correction bound",
        ))
    evaluated: list[str] = []
    for dimension in ("activity", "layout"):
        for name, base_metrics in base_report.cohorts[dimension].items():
            if base_metrics.samples < policy.minimum_cohort_samples or base_metrics.duration_seconds < policy.minimum_cohort_duration_seconds:
                continue
            candidate_metrics = candidate_report.cohorts[dimension].get(name)
            scope = f"{dimension}:{name}"
            evaluated.append(scope)
            if candidate_metrics is None:
                findings.append(PersonalValidationFinding(scope, "coverage", float(base_metrics.samples), None, float(policy.minimum_cohort_samples), f"candidate lacks sufficiently represented {scope} cohort"))
            else:
                findings.extend(_metric_findings(scope, base_metrics, candidate_metrics, policy))
    return PersonalValidationReport(
        "nekovr-personal-validation-v1", 1, not findings,
        canonical_hash(base_keys), base_report.to_dict(), candidate_report.to_dict(),
        tuple(evaluated), tuple(findings), policy,
    )


@dataclass(frozen=True)
class PersonalExportProvenance:
    base_model_sha256: str
    profile_id: str
    selected_session_sha256: tuple[str, ...]
    split_manifest_sha256: str
    checkpoint_sha256: str

    def __post_init__(self) -> None:
        hashes = (self.base_model_sha256, self.split_manifest_sha256, self.checkpoint_sha256, *self.selected_session_sha256)
        if not self.profile_id or not self.selected_session_sha256 or any(len(value) != 64 or any(character not in "0123456789abcdef" for character in value) for value in hashes):
            raise ValueError("personal export provenance requires a profile and lowercase SHA-256 hashes")


@dataclass(frozen=True)
class RuntimeParityReport:
    cases: int
    maximum_framework_training_error: float
    maximum_framework_inference_error: float
    tolerance: float
    inference_provider: str
    passed: bool


@dataclass(frozen=True)
class PersonalExportResult:
    model_path: Path
    sidecar_path: Path
    validation_sha256: str
    metrics_sha256: str
    parity: RuntimeParityReport


def _flatten_output(output: ModelOutput) -> np.ndarray:
    values = (
        np.asarray(output.correction_rotation_vectors, dtype=np.float64).reshape(-1),
        np.asarray(output.confidence, dtype=np.float64).reshape(-1),
        np.asarray(output.drift_rate, dtype=np.float64).reshape(-1),
    )
    combined = np.concatenate(values)
    if not np.isfinite(combined).all():
        raise ValueError("training runtime returned non-finite personal model output")
    return combined


def export_validated_personal_model(
    model: CompactCausalModel,
    output: str | Path,
    *,
    validation: PersonalValidationReport,
    personal: PersonalExportProvenance,
    parity_batches: Sequence[ModelBatch],
    training_runtime: Callable[[ModelBatch], ModelOutput],
    parity_tolerance: float = 1e-5,
    model_id: str,
    model_version: str,
    feature_schema: Mapping[str, object],
    normalization: Mapping[str, object],
    supported_roles: Sequence[int],
    minimum_slots: int,
    minimum_context: int,
    maximum_context: int,
    provenance: Mapping[str, object],
    performance_tier: str = "small",
) -> PersonalExportResult:
    if not validation.passed:
        raise ValueError("a personal model cannot be exported before held-out validation passes")
    if not parity_batches or parity_tolerance <= 0 or not math.isfinite(parity_tolerance):
        raise ValueError("personal export requires finite parity cases and tolerance")
    metrics_sha256 = canonical_hash(validation.candidate)
    personal_provenance = {
        **dict(provenance),
        "personal_profile_id": personal.profile_id,
        "base_model_sha256": personal.base_model_sha256,
        "selected_session_sha256": sorted(personal.selected_session_sha256),
        "split_manifest_sha256": personal.split_manifest_sha256,
        "checkpoint_sha256": personal.checkpoint_sha256,
        "metrics_sha256": metrics_sha256,
        "personal_validation_sha256": validation.sha256,
    }
    target = Path(output).absolute()
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nekovr-personal-export-", dir=target.parent) as temporary:
        staged_model = Path(temporary) / target.name
        staged_model, staged_sidecar = export_model(
            model, staged_model, model_id=model_id, model_version=model_version,
            feature_schema=feature_schema, normalization=normalization,
            supported_roles=supported_roles, minimum_slots=minimum_slots,
            minimum_context=minimum_context, maximum_context=maximum_context,
            provenance=personal_provenance, validation_metrics=validation.candidate,
            performance_tier=performance_tier,
        )
        training_error = 0.0
        for batch in parity_batches:
            framework = _flatten_output(model.forward(batch))
            runtime = _flatten_output(training_runtime(batch))
            if framework.shape != runtime.shape:
                raise ValueError("training runtime output shape differs from framework")
            training_error = max(training_error, float(np.max(np.abs(framework - runtime))))
        inference = compare_framework_and_onnx(model, staged_model, parity_batches, parity_tolerance)
        parity = RuntimeParityReport(len(parity_batches), training_error, inference.maximum_absolute_error, parity_tolerance, inference.provider, training_error <= parity_tolerance and inference.passed)
        if not parity.passed:
            raise ValueError("framework/training-runtime/inference-runtime parity failed")
        sidecar = load_sidecar(staged_sidecar, staged_model)
        write_sidecar(replace(
            sidecar,
            model_kind="personal",
            profile_id=personal.profile_id,
            provenance={**sidecar.provenance, "runtime_parity": asdict(parity)},
            validation_metrics={**sidecar.validation_metrics, "runtime_parity": asdict(parity)},
        ), staged_sidecar)
        target_sidecar = target.with_suffix(target.suffix + ".json")
        os.replace(staged_model, target)
        os.replace(staged_sidecar, target_sidecar)
    return PersonalExportResult(target, target_sidecar, validation.sha256, metrics_sha256, parity)
