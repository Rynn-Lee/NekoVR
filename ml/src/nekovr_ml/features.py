from __future__ import annotations

from dataclasses import dataclass
import math
import random
from typing import Callable, Mapping, Sequence


CONTEXTUAL_KINDS = frozenset({"temperature", "network", "power", "hardware"})


@dataclass(frozen=True)
class FeatureSpec:
    name: str
    kind: str
    normalization_scope: str = "global"

    def __post_init__(self) -> None:
        if self.normalization_scope not in {"global", "device", "session"}:
            raise ValueError("normalization_scope must be global, device, or session")

    @property
    def contextual(self) -> bool:
        return self.kind in CONTEXTUAL_KINDS


@dataclass(frozen=True)
class FeatureRow:
    sample_id: str
    split: str
    device_id: str
    session_id: str
    values: Mapping[str, float | None]


@dataclass(frozen=True)
class FeatureStatistics:
    mean: float
    standard_deviation: float
    count: int


@dataclass(frozen=True)
class FeatureNormalization:
    specifications: tuple[FeatureSpec, ...]
    statistics: Mapping[str, Mapping[str, FeatureStatistics]]
    training_sample_ids: tuple[str, ...]

    def transform(self, row: FeatureRow) -> tuple[tuple[float, ...], tuple[bool, ...]]:
        values, valid = [], []
        for spec in self.specifications:
            raw = row.values.get(spec.name)
            is_valid = raw is not None and math.isfinite(float(raw))
            group = "*" if spec.normalization_scope == "global" else row.device_id if spec.normalization_scope == "device" else row.session_id
            stats = self.statistics[spec.name].get(group, self.statistics[spec.name]["*"])
            values.append((float(raw) - stats.mean) / stats.standard_deviation if is_valid else 0.0)
            valid.append(is_valid)
        return tuple(values), tuple(valid)


def _statistics(values: Sequence[float]) -> FeatureStatistics:
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    return FeatureStatistics(mean, math.sqrt(variance) if variance > 1e-12 else 1.0, len(values))


def fit_feature_normalization(specifications: Sequence[FeatureSpec], rows: Sequence[FeatureRow]) -> FeatureNormalization:
    training = [row for row in rows if row.split == "train"]
    if not training:
        raise ValueError("feature normalization requires training rows")
    result: dict[str, dict[str, FeatureStatistics]] = {}
    for spec in specifications:
        global_values = [float(row.values[spec.name]) for row in training if row.values.get(spec.name) is not None and math.isfinite(float(row.values[spec.name]))]
        if not global_values:
            raise ValueError(f"feature {spec.name} has no valid training values")
        groups: dict[str, list[float]] = {"*": global_values}
        if spec.normalization_scope != "global":
            for row in training:
                raw = row.values.get(spec.name)
                if raw is None or not math.isfinite(float(raw)):
                    continue
                group = row.device_id if spec.normalization_scope == "device" else row.session_id
                groups.setdefault(group, []).append(float(raw))
        result[spec.name] = {group: _statistics(values) for group, values in groups.items()}
    return FeatureNormalization(tuple(specifications), result, tuple(sorted(row.sample_id for row in training)))


def augment_missingness(
    values: Sequence[float],
    validity: Sequence[bool],
    specifications: Sequence[FeatureSpec],
    probability: float,
    seed: int,
) -> tuple[tuple[float, ...], tuple[bool, ...]]:
    if not 0.0 <= probability <= 1.0 or not (len(values) == len(validity) == len(specifications)):
        raise ValueError("missingness probability or feature widths are invalid")
    rng = random.Random(seed)
    output_values, output_validity = [], []
    for value, is_valid, spec in zip(values, validity, specifications):
        drop = spec.contextual and is_valid and rng.random() < probability
        output_values.append(0.0 if drop else float(value))
        output_validity.append(bool(is_valid and not drop))
    return tuple(output_values), tuple(output_validity)


@dataclass(frozen=True)
class ShortcutFinding:
    feature: str
    cohort_kind: str
    cohort_id: str
    baseline_error: float
    candidate_error: float
    seen_in_training: bool

    @property
    def regression(self) -> float:
        return self.candidate_error - self.baseline_error


@dataclass(frozen=True)
class FeaturePromotionDecision:
    feature: str
    promoted: bool
    reasons: tuple[str, ...]


def run_per_feature_ablation(
    feature_names: Sequence[str],
    evaluate_features: Callable[[frozenset[str]], float],
    lower_is_better: bool = True,
) -> dict[str, float]:
    """Return each feature's held-out contribution using one-feature removal."""
    complete = frozenset(feature_names)
    baseline = float(evaluate_features(complete))
    result = {}
    for feature in feature_names:
        without = float(evaluate_features(complete - {feature}))
        result[feature] = without - baseline if lower_is_better else baseline - without
    return result


def audit_unseen_shortcuts(
    feature: str,
    training_devices: set[str],
    training_sessions: set[str],
    baseline_errors: Mapping[tuple[str, str], float],
    candidate_errors: Mapping[tuple[str, str], float],
) -> tuple[ShortcutFinding, ...]:
    findings = []
    for key in sorted(set(baseline_errors) & set(candidate_errors)):
        cohort_kind, cohort_id = key
        if cohort_kind not in {"device", "session"}:
            raise ValueError("shortcut cohorts must be device or session")
        seen = cohort_id in (training_devices if cohort_kind == "device" else training_sessions)
        findings.append(ShortcutFinding(feature, cohort_kind, cohort_id, float(baseline_errors[key]), float(candidate_errors[key]), seen))
    return tuple(findings)


def decide_feature_promotion(
    specifications: Sequence[FeatureSpec],
    normalized_features: set[str],
    missingness_tested_features: set[str],
    ablation_improvement: Mapping[str, float],
    shortcut_findings: Sequence[ShortcutFinding],
    minimum_ablation_improvement: float = 0.0,
    maximum_unseen_regression: float = 0.0,
) -> tuple[FeaturePromotionDecision, ...]:
    decisions = []
    for spec in specifications:
        if not spec.contextual:
            decisions.append(FeaturePromotionDecision(spec.name, True, ()))
            continue
        reasons = []
        if spec.name not in normalized_features:
            reasons.append("not_normalized")
        if spec.name not in missingness_tested_features:
            reasons.append("missingness_not_tested")
        if ablation_improvement.get(spec.name, float("-inf")) < minimum_ablation_improvement:
            reasons.append("ablation_not_beneficial")
        if any(finding.feature == spec.name and not finding.seen_in_training and finding.regression > maximum_unseen_regression for finding in shortcut_findings):
            reasons.append("unseen_cohort_regression")
        decisions.append(FeaturePromotionDecision(spec.name, not reasons, tuple(reasons)))
    return tuple(decisions)
