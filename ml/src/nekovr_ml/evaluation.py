from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from statistics import fmean
from typing import Callable, Iterable, Mapping, Sequence

from .math3d import IDENTITY, Quat, angular_distance, inverse, multiply, rotation_vector_to_quat


@dataclass(frozen=True)
class EvaluationRecord:
    sequence_id: str
    timestamp_s: float
    layout: str
    domain: str
    person_id: str
    chipset: str
    transport: str
    activity: str
    activity_confidence: float
    drift_severity: str
    observed_xyzw: Quat
    target_xyzw: Quat
    predicted_correction_rotation_vector: tuple[float, float, float]
    confidence: float
    clean_motion: bool
    reset_kind: str | None = None

    def __post_init__(self) -> None:
        if not math.isfinite(self.timestamp_s) or not 0.0 <= self.confidence <= 1.0:
            raise ValueError("timestamp must be finite and confidence must be in [0, 1]")


@dataclass(frozen=True)
class MetricSet:
    samples: int
    duration_seconds: float
    angular_error_before_radians: float
    angular_error_after_radians: float
    reset_target_error_radians: float | None
    confidence_brier: float
    clean_false_correction_rate: float
    clean_false_correction_magnitude_radians: float
    correction_magnitude_radians: float
    correction_rate_radians_per_second: float
    temporal_jitter_radians_per_second2: float
    discontinuities: int
    yaw_resets_per_hour: float
    full_resets_per_hour: float
    time_to_first_reset_seconds: float | None
    longest_valid_no_reset_seconds: float


@dataclass(frozen=True)
class EvaluationReport:
    overall: MetricSet
    cohorts: Mapping[str, Mapping[str, MetricSet]]

    def to_dict(self) -> dict:
        return {"overall": asdict(self.overall), "cohorts": {dimension: {name: asdict(metrics) for name, metrics in values.items()} for dimension, values in self.cohorts.items()}}


def _safe_mean(values: Sequence[float]) -> float:
    return fmean(values) if values else 0.0


def _metrics(records: Sequence[EvaluationRecord], false_correction_threshold: float, discontinuity_rate_threshold: float) -> MetricSet:
    if not records:
        raise ValueError("cannot evaluate an empty cohort")
    ordered = sorted(records, key=lambda value: (value.sequence_id, value.timestamp_s))
    before, after, magnitudes, reset_errors, brier = [], [], [], [], []
    clean_magnitudes = []
    per_sequence: dict[str, list[tuple[EvaluationRecord, Quat]]] = {}
    for record in ordered:
        predicted = rotation_vector_to_quat(record.predicted_correction_rotation_vector)
        corrected = multiply(predicted, record.observed_xyzw)
        error_before = angular_distance(record.observed_xyzw, record.target_xyzw)
        error_after = angular_distance(corrected, record.target_xyzw)
        magnitude = angular_distance(predicted, IDENTITY)
        before.append(error_before)
        after.append(error_after)
        magnitudes.append(magnitude)
        success = 1.0 if error_after < error_before else 0.0
        brier.append((record.confidence - success) ** 2)
        if record.clean_motion:
            clean_magnitudes.append(magnitude)
        if record.reset_kind:
            target_correction = multiply(record.target_xyzw, inverse(record.observed_xyzw))
            reset_errors.append(angular_distance(predicted, target_correction))
        per_sequence.setdefault(record.sequence_id, []).append((record, predicted))
    rates, jitter, discontinuities = [], [], 0
    sequence_durations = []
    first_reset_values = []
    longest_intervals = []
    yaw_resets = full_resets = 0
    for values in per_sequence.values():
        values.sort(key=lambda item: item[0].timestamp_s)
        start, end = values[0][0].timestamp_s, values[-1][0].timestamp_s
        duration = max(0.0, end - start)
        sequence_durations.append(duration)
        reset_times = []
        previous_rate = None
        for index, (record, predicted) in enumerate(values):
            kind = (record.reset_kind or "").upper()
            if kind:
                reset_times.append(record.timestamp_s)
                yaw_resets += int(kind == "YAW")
                full_resets += int(kind == "FULL")
            if index == 0:
                continue
            previous_record, previous_predicted = values[index - 1]
            dt = record.timestamp_s - previous_record.timestamp_s
            if dt <= 0:
                continue
            rate = angular_distance(previous_predicted, predicted) / dt
            rates.append(rate)
            discontinuities += int(rate > discontinuity_rate_threshold)
            if previous_rate is not None:
                jitter.append(abs(rate - previous_rate) / dt)
            previous_rate = rate
        if reset_times:
            first_reset_values.append(reset_times[0] - start)
        boundaries = [start, *reset_times, end]
        longest_intervals.append(max((right - left for left, right in zip(boundaries, boundaries[1:])), default=duration))
    duration_seconds = sum(sequence_durations)
    duration_hours = max(duration_seconds, len(per_sequence) / 50.0) / 3600.0
    clean_false = sum(value > false_correction_threshold for value in clean_magnitudes) / len(clean_magnitudes) if clean_magnitudes else 0.0
    return MetricSet(
        samples=len(records),
        duration_seconds=duration_seconds,
        angular_error_before_radians=_safe_mean(before),
        angular_error_after_radians=_safe_mean(after),
        reset_target_error_radians=_safe_mean(reset_errors) if reset_errors else None,
        confidence_brier=_safe_mean(brier),
        clean_false_correction_rate=clean_false,
        clean_false_correction_magnitude_radians=_safe_mean(clean_magnitudes),
        correction_magnitude_radians=_safe_mean(magnitudes),
        correction_rate_radians_per_second=_safe_mean(rates),
        temporal_jitter_radians_per_second2=_safe_mean(jitter),
        discontinuities=discontinuities,
        yaw_resets_per_hour=yaw_resets / duration_hours,
        full_resets_per_hour=full_resets / duration_hours,
        time_to_first_reset_seconds=min(first_reset_values) if first_reset_values else None,
        longest_valid_no_reset_seconds=max(longest_intervals, default=0.0),
    )


def evaluate(
    records: Sequence[EvaluationRecord],
    activity_confidence_threshold: float = 0.6,
    false_correction_threshold_radians: float = math.radians(1.0),
    discontinuity_rate_threshold: float = math.radians(90.0),
) -> EvaluationReport:
    if not records:
        raise ValueError("evaluation requires records")
    selectors: dict[str, Callable[[EvaluationRecord], str | None]] = {
        "layout": lambda record: record.layout,
        "domain": lambda record: record.domain,
        "person": lambda record: record.person_id,
        "chipset": lambda record: record.chipset,
        "transport": lambda record: record.transport,
        "activity": lambda record: record.activity.upper() if record.activity.upper() != "UNKNOWN" and record.activity_confidence >= activity_confidence_threshold else None,
        "drift_severity": lambda record: record.drift_severity,
    }
    cohorts: dict[str, dict[str, MetricSet]] = {}
    for dimension, selector in selectors.items():
        grouped: dict[str, list[EvaluationRecord]] = {}
        for record in records:
            key = selector(record)
            if key:
                grouped.setdefault(key, []).append(record)
        cohorts[dimension] = {
            key: _metrics(values, false_correction_threshold_radians, discontinuity_rate_threshold)
            for key, values in sorted(grouped.items())
        }
    return EvaluationReport(
        _metrics(records, false_correction_threshold_radians, discontinuity_rate_threshold),
        cohorts,
    )
