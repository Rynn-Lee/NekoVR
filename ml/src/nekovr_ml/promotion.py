from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Sequence


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

