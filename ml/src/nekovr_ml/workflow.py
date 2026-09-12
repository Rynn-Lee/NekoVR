from __future__ import annotations

from dataclasses import asdict, dataclass, replace
import copy
import hashlib
import json
import math
from typing import Any, Mapping, Sequence

from .features import FeatureSpec, FeatureStatistics, ShortcutFinding, augment_missingness, decide_feature_promotion
from .model import CompactCausalModel, SequenceSample
from .splits import SPLIT_NAMES, SampleMetadata, grouped_split
from .training import TrainingExample, global_loss


TRAINING_PLAN_FORMAT = "nekovr-training-plan-v1"


@dataclass(frozen=True)
class TrainingPlan:
    assignments: Mapping[str, str]
    split_audit: Mapping[str, Any]
    normalization: Mapping[str, Any]
    feature_specifications: tuple[FeatureSpec, ...]
    examples: tuple[Mapping[str, Any], ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "format": TRAINING_PLAN_FORMAT,
            "assignments": dict(self.assignments),
            "split_audit": dict(self.split_audit),
            "normalization": dict(self.normalization),
            "feature_specifications": [asdict(value) for value in self.feature_specifications],
            "examples": list(self.examples),
        }


def _feature_kind(name: str) -> str:
    lowered = name.lower()
    if "temperature" in lowered:
        return "temperature"
    if any(token in lowered for token in ("network", "rssi", "packet", "stale")):
        return "network"
    if any(token in lowered for token in ("battery", "power", "charging")):
        return "power"
    if any(token in lowered for token in ("hardware", "chipset", "provenance")):
        return "hardware"
    return "motion"


def feature_specifications(schema: Sequence[str]) -> tuple[FeatureSpec, ...]:
    if not schema or len(set(schema)) != len(schema):
        raise ValueError("feature schema must contain unique names")
    return tuple(FeatureSpec(str(name), _feature_kind(str(name))) for name in schema)


def _metadata(value: Mapping[str, Any]) -> SampleMetadata:
    group = value.get("group")
    if not isinstance(group, Mapping):
        raise ValueError(f"example {value.get('sample_id', '<unknown>')} has no immutable group")
    sample_id = str(value.get("sample_id", ""))
    domain = str(value.get("domain", ""))
    source = str(group.get("source_id") or value.get("source_sha256") or "")
    session = str(group.get("session_id") or "")
    subject = str(group.get("subject_id") or source)
    device = str(group.get("device_cohort") or group.get("device_id") or "UNKNOWN")
    if not sample_id or domain not in {"synthetic", "real"} or not source:
        raise ValueError("canonical examples require sample_id, synthetic/real domain, and source identity")
    return SampleMetadata(
        sample_id, domain, subject, source, session, device,
        str(group.get("layout") or value.get("layout") or "UNKNOWN"),
        str(group.get("chipset") or value.get("chipset") or "UNKNOWN"),
    )


def _fit_normalization(
    examples: Sequence[Mapping[str, Any]], assignments: Mapping[str, str], schema: Sequence[str]
) -> dict[str, Any]:
    width = len(schema)
    values: list[list[float]] = [[] for _ in schema]
    training_groups: set[str] = set()
    training_samples: list[str] = []
    for example in examples:
        sample_id = str(example["sample_id"])
        if assignments[sample_id] != "train":
            continue
        training_samples.append(sample_id)
        training_groups.add(json.dumps(example["group"], sort_keys=True, separators=(",", ":")))
        for frame, validity in zip(example["features"], example["channel_validity"]):
            for slot, slot_validity in zip(frame, validity):
                if len(slot) != width or len(slot_validity) != width:
                    raise ValueError("feature and validity width differs from feature schema")
                for index, (raw, valid) in enumerate(zip(slot, slot_validity)):
                    number = float(raw)
                    if valid and math.isfinite(number):
                        values[index].append(number)
    statistics = []
    for column in values:
        if column:
            mean = sum(column) / len(column)
            variance = sum((value - mean) ** 2 for value in column) / len(column)
            statistics.append(FeatureStatistics(mean, math.sqrt(variance) if variance > 1e-12 else 1.0, len(column)))
        else:
            statistics.append(FeatureStatistics(0.0, 1.0, 0))
    return {
        "scope": "training-groups-only",
        "mean": [value.mean for value in statistics],
        "standard_deviation": [value.standard_deviation for value in statistics],
        "counts": [value.count for value in statistics],
        "fitted_features": [schema[index] for index, value in enumerate(statistics) if value.count > 0],
        "unavailable_training_features": [schema[index] for index, value in enumerate(statistics) if value.count == 0],
        "training_sample_ids": sorted(training_samples),
        "training_group_ids": sorted(training_groups),
    }


def _window(
    source: Mapping[str, Any], split: str, normalization: Mapping[str, Any],
    specifications: Sequence[FeatureSpec], window_frames: int, missingness_probability: float, seed: int,
) -> dict[str, Any]:
    raw_frames = source["features"]
    raw_validity = source["channel_validity"]
    if not raw_frames or len(raw_frames) != len(raw_validity):
        raise ValueError("canonical example needs aligned non-empty temporal features and validity")
    start = max(0, len(raw_frames) - window_frames)
    frames = copy.deepcopy(raw_frames[start:])
    validity = copy.deepcopy(raw_validity[start:])
    means = normalization["mean"]
    scales = normalization["standard_deviation"]
    for time_index, (frame, validity_frame) in enumerate(zip(frames, validity)):
        for slot_index, (slot, slot_validity) in enumerate(zip(frame, validity_frame)):
            normalized = tuple(
                (float(value) - means[index]) / scales[index] if slot_validity[index] and math.isfinite(float(value)) else 0.0
                for index, value in enumerate(slot)
            )
            normalized_validity = tuple(bool(value) for value in slot_validity)
            if split == "train":
                digest = hashlib.sha256(f"{seed}:{source['sample_id']}:{time_index}:{slot_index}".encode()).digest()
                normalized, normalized_validity = augment_missingness(
                    normalized, normalized_validity, specifications, missingness_probability,
                    int.from_bytes(digest[:8], "big"),
                )
            frame[slot_index] = list(normalized)
            validity_frame[slot_index] = list(normalized_validity)
    result = copy.deepcopy(dict(source))
    result.update({
        "parent_sample_id": source["sample_id"],
        "sample_id": f"{source['sample_id']}:window:{start}:{len(raw_frames)}",
        "split": split,
        "window": {"start": start, "end": len(raw_frames), "assigned_before_extraction": True},
        "raw_features": copy.deepcopy(raw_frames[start:]),
        "features": frames,
        "channel_validity": validity,
        "time_deltas_s": list(source["time_deltas_s"][start:]),
    })
    return result


def build_training_plan(payload: Mapping[str, Any], configuration: Mapping[str, Any], seed: int) -> TrainingPlan:
    if payload.get("format") != "nekovr-training-input-v1" or payload.get("preparation_format") != "nekovr-canonical-preparation-v1":
        raise ValueError("global training requires canonical nekovr preparation input")
    raw_examples = payload.get("examples")
    schema = payload.get("feature_schema")
    if not isinstance(raw_examples, list) or not raw_examples or not isinstance(schema, list):
        raise ValueError("canonical training input needs examples and feature_schema")
    if any(value.get("split", "unassigned") != "unassigned" for value in raw_examples):
        raise ValueError("global training does not trust caller-supplied split labels")
    metadata = tuple(_metadata(value) for value in raw_examples)
    split_values = configuration.get("splits", {})
    fractions = {name: float(split_values.get(name, default)) for name, default in zip(SPLIT_NAMES, (0.7, 0.15, 0.15))}
    configured_fields = tuple(value for value in split_values.get("group_fields", ("subject_id", "source_id", "session_id", "device_cohort")) if value != "domain")
    split = grouped_split(metadata, seed, fractions, configured_fields)
    empty = [name for name in SPLIT_NAMES if split.audit["coverage"][name]["samples"] == 0]
    if empty:
        raise ValueError(f"group split produced unusable empty holdouts: {', '.join(empty)}")
    normalization = _fit_normalization(raw_examples, split.assignments, schema)
    specifications = feature_specifications(schema)
    model_values = configuration.get("model", {})
    receptive_field = 1 + max(0, int(model_values.get("kernel_size", 3)) - 1) * (2 ** int(model_values.get("temporal_layers", 1)) - 1)
    feature_values = configuration.get("features", {})
    missingness = float(feature_values.get("contextual_missingness_probability", 0.0))
    windows = tuple(
        _window(value, split.assignments[str(value["sample_id"])], normalization, specifications, receptive_field, missingness, seed)
        for value in raw_examples
    )
    audit = {
        **split.audit,
        "phase_order": ["group_assignment", "window_extraction", "normalization_application"],
        "parent_assignments": dict(sorted(split.assignments.items())),
        "window_assignments": {str(value["sample_id"]): str(value["split"]) for value in windows},
    }
    return TrainingPlan(dict(split.assignments), audit, normalization, specifications, windows)


def _without_feature(example: TrainingExample, index: int) -> TrainingExample:
    sequence = example.sequence
    features = tuple(
        tuple(tuple(0.0 if feature == index else value for feature, value in enumerate(slot)) for slot in frame)
        for frame in sequence.features
    )
    validity = tuple(
        tuple(tuple(False if feature == index else value for feature, value in enumerate(slot)) for slot in frame)
        for frame in sequence.channel_validity
    )
    return replace(example, sequence=SequenceSample(features, sequence.role_ids, sequence.slot_mask, validity, sequence.time_deltas_s))


def qualify_context_features(
    model: CompactCausalModel,
    examples: Sequence[TrainingExample],
    specifications: Sequence[FeatureSpec],
    normalization: Mapping[str, Any],
    policy: Mapping[str, Any],
) -> dict[str, Any]:
    heldout = tuple(value for value in examples if value.split in {"validation", "test"})
    if not heldout:
        raise ValueError("feature qualification requires held-out examples")
    baseline_error, _ = global_loss(model, heldout)
    contextual = [value for value in specifications if value.contextual]
    contributions: dict[str, float] = {}
    findings: list[ShortcutFinding] = []
    training_devices = {str((value.metadata or {}).get("group", {}).get("device_cohort", "UNKNOWN")) for value in examples if value.split == "train"}
    training_sessions = {str((value.metadata or {}).get("group", {}).get("session_id", "")) for value in examples if value.split == "train"}
    for index, spec in enumerate(specifications):
        if not spec.contextual:
            continue
        ablated = tuple(_without_feature(value, index) for value in heldout)
        ablated_error, _ = global_loss(model, ablated)
        contributions[spec.name] = ablated_error - baseline_error
        for kind in ("device", "session"):
            ids = sorted({
                str((value.metadata or {}).get("group", {}).get("device_cohort" if kind == "device" else "session_id", "UNKNOWN"))
                for value in heldout
            })
            for cohort_id in ids:
                selected = tuple(
                    value for value in heldout
                    if str((value.metadata or {}).get("group", {}).get("device_cohort" if kind == "device" else "session_id", "UNKNOWN")) == cohort_id
                )
                candidate, _ = global_loss(model, selected)
                without, _ = global_loss(model, tuple(_without_feature(value, index) for value in selected))
                seen = cohort_id in (training_devices if kind == "device" else training_sessions)
                findings.append(ShortcutFinding(spec.name, kind, cohort_id, without, candidate, seen))
    contextual_names = {value.name for value in contextual}
    normalized_names = contextual_names & set(normalization.get("fitted_features", ()))
    minimum = 0.0 if bool(policy.get("require_ablation_improvement", True)) else float("-inf")
    decisions = decide_feature_promotion(
        specifications, normalized_names, contextual_names, contributions, findings,
        minimum_ablation_improvement=minimum,
        maximum_unseen_regression=float(policy.get("maximum_unseen_cohort_regression", 0.0)),
    )
    return {
        "format": "nekovr-feature-qualification-v1",
        "normalization_scope": normalization.get("scope"),
        "normalization_training_sample_ids": list(normalization.get("training_sample_ids", ())),
        "missingness_probability": float(policy.get("contextual_missingness_probability", 0.0)),
        "missingness_executed": sorted(contextual_names),
        "ablation_baseline_error": baseline_error,
        "ablation_improvement": contributions,
        "unseen_checks": [asdict(value) | {"regression": value.regression} for value in findings],
        "decisions": [asdict(value) for value in decisions],
        "all_contextual_features_promoted": all(value.promoted for value in decisions if next(spec for spec in specifications if spec.name == value.feature).contextual),
    }
